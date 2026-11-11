package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly as Wire
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Legacy CAN connection over the shared ELM stream.
 * Caller owns a disposable engine and always restores standard OBD afterwards.
 * No arbitrary CAN addresses, retransmission after transport failure or writes.
 */
class ElmTp20Channel(private var engine: ElmEngine, private val clockNanos: () -> Long = System::nanoTime,
    private val setupBackoff: suspend (Long) -> Unit = { delay(it) },
    private val recoverEngine: (suspend (Throwable) -> ElmEngine)? = null) {
    /** Acknowledged request, no application packet, and matching live keepalives.
     * Caller may terminate this channel before choosing another read path. */
    class NoApplicationResponse(val request: String? = null) : IllegalStateException("ECU не дал прикладного ответа при работающем TP2.0 канале")
    open class ConnectionUnavailable(message: String) : IllegalStateException(message)
    class NoChannelResponse : ConnectionUnavailable("ECU не ответил на CHANNEL SETUP")
    /** Complete negative setup, not an ambiguous/broken stream. No channel exists. */
    class ChannelRejected(val opcode: Int) : ConnectionUnavailable("ECU отклонил CHANNEL SETUP: 0x%02X".format(opcode))
    class ParametersUnavailable(val setupOpcode: Int? = null) : ConnectionUnavailable(
        "ECU не подтвердил параметры TP2.0 после ограниченных повторов" +
            (setupOpcode?.let { "; последний CHANNEL SETUP: 0x%02X".format(it) } ?: ""))
    /** Valid full application reply followed by a clean peer close. The stream
     * boundary is known; another request needs a new channel, not a retransmit. */
    class GracefullyClosedChannel : IllegalStateException("ECU завершил канал после полного ответа")
    /** Explicit peer close after ACK, before any application response. The
     * caller must reset the transport before another diagnostic transaction. */
    class PeerClosedChannel : IOException("ECU закрыл TP2.0 канал до прикладного ответа")
    private var tx = 0
    private var rx = 0
    private var sendSeq = 0
    private var receiveSeq = 0
    private var active = false
    private var firstRequest = true
    private var peerClosedAfterReply = false
    private var gapMs = 20L
    private var responseMs = 200L
    private var frameReceiveTicks = 0x10
    private var receiveAckDueNanos: Long? = null
    private var peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)
    private var negotiatedPeerParameters: List<Int> = emptyList()
    private var destination: Int? = null

    suspend fun open(destination: Int) {
        this.destination = destination
        repeat(4) { attempt ->
            try { openOnce(destination); return }
            catch (failure: VagProtocolFailure) {
                if (recoverEngine == null || attempt == 3) throw failure
                engine = recoverEngine.invoke(failure)
                if (!engine.vehicleReady) throw IOException("Связь после восстановления не подтверждена")
                active = false
            }
        }
    }
    private suspend fun openOnce(destination: Int) {
        peerClosedAfterReply = false
        require(destination in Wire.routes.values.mapNotNull { it.tp }) { "TP2.0 адрес не в профиле" }
        try { engine.nativeSegmentation?.disable(); openChecked(destination) }
        catch (cancel: CancellationException) { throw cancel }
        catch (unavailable: ConnectionUnavailable) { throw unavailable }
        catch (failure: RuntimeException) {
            // A malformed setup/A1 is not a completed absence response and must
            // not trigger a second protocol on an ambiguous receive stream.
            throw VagProtocolFailure("Повреждённое согласование TP2.0 канала", failure)
        }
    }
    private suspend fun openChecked(destination: Int) {
        check(!active)
        for (connectionAttempt in 0 until 2) {
            val d = setup(destination)
            // A complete negative setup may carry a usable channel pair.
            // It is not a connected ECU: A1 and the diagnostic session must
            // still be independently confirmed before application reads.
            val validPair = d[3] and 0xF0 == 0 && d[5] and 0xF0 == 0 &&
                (d[2] or (d[3] shl 8)) == 0x300 &&
                (d[4] or (d[5] shl 8)) in 1..0x7FF &&
                (d[4] or (d[5] shl 8)) !in setOf(0x200, 0x300)
            if (!validPair && d[1] != 0xD0) throw ChannelRejected(d[1])
            check(validPair) { "Неверная пара CAN канала" }
            rx = d[2] or (d[3] shl 8)
            tx = d[4] or (d[5] shl 8)
            active = true
            ok("ATSH%03X".format(tx)); ok("ATCRA%03X".format(rx)); ok("ATST10")
            val parameters = negotiateParameters(destination, d)
            if (parameters == null) {
                close()
                if (connectionAttempt == 1) throw ParametersUnavailable(d[1])
                continue
            }
            responseMs = timingMs(parameters[2])
            gapMs = timingMs(parameters[4])
            check(responseMs in 1..2000 && gapMs in 0..1000) { "Неподдержанные тайминги TP2.0" }
            peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)
            negotiatedPeerParameters = parameters.toList()
            // Finish the ELM receive window before the peer's ACK deadline.
            val receiveTicks = ((gapMs + 24 + 3) / 4).coerceAtLeast(1)
            check(receiveTicks * 4 + gapMs <= 160) { "T3 не позволяет подтвердить кадр в объявленный T1" }
            ok("ATST%02X".format(receiveTicks))
            frameReceiveTicks = receiveTicks.toInt()
            sendSeq = 0; receiveSeq = 0
            receiveAckDueNanos = null
            firstRequest = true
            return
        }
        error("Исчерпан предел подключения TP2.0")
    }
    private suspend fun setup(destination: Int): List<Int> {
        // Configure variable-length raw frames in the profile's CAN mode.
        for (at in listOf("ATH1", "ATCAF0", "ATCFC0", "ATSP6", "ATD0", "ATV1", "ATCEA", "ATAT0", "ATST10",
            "ATSH200", "ATCRA%03X".format(0x200 or destination))) ok(at)
        val dpn = command("ATDPN").trim().uppercase()
        check(dpn in setOf("6", "A6")) { "ELM не подтвердил CAN 6: $dpn" }
        val request = "%02XC00010000301".format(destination)
        var negative: List<Int>? = null
        repeat(4) {
            coroutineContext.ensureActive()
            val setup = command(request + "1", 5500)
            if (setup.trim().uppercase() == "NO DATA") return@repeat
            val frames = frames(setup, 0x200 or destination, request + "1")
            check(frames.size == 1) { "Неоднозначный ответ CHANNEL SETUP" }
            val reply = frames.single()
            check(reply.size == 7 && reply[0] == 0 && reply[6] == 1) { "Неверная структура CHANNEL SETUP" }
            // All setup attempts precede application traffic. D7 additionally
            // requires backoff; malformed/foreign frames never enter a retry.
            if (reply[1] == 0xD0) return reply
            check(reply[1] in 0xD6..0xD8) { "Неверный opcode CHANNEL SETUP" }
            negative = reply
            if (reply[1] == 0xD7) {
                setupBackoff(2000)
                coroutineContext.ensureActive()
            }
        }
        return negative ?: throw NoChannelResponse()
    }
    private suspend fun negotiateParameters(destination: Int, d: List<Int>): List<Int>? {
        // Some adapters replay the exact D0 setup frame once after changing
        // the receive filter. Ignore only that identical negotiated frame here;
        // every other foreign ID/data remains an error, including later traffic.
        val setupRow = "%03X".format(0x200 or destination) + Wire.hex(d)
        repeat(4) {
            coroutineContext.ensureActive()
            val parametersRaw = command("A00194FF82FF")
            if (parametersRaw.trim().uppercase() == "NO DATA") return@repeat
            val rows = parametersRaw.replace('>', '\n').replace('\r', '\n').split('\n')
            var duplicateSetup = false
            val filtered = rows.filter { row ->
                if (row.filterNot { it.isWhitespace() }.uppercase() == setupRow) {
                    check(!duplicateSetup) { "Повторные CHANNEL SETUP после согласования" }
                    duplicateSetup = true
                    false
                } else true
            }.joinToString("\n")
            if (filtered.isBlank()) return@repeat
            val parameters = frames(filtered, rx, "A00194FF82FF").singleOrNull()
                ?: error("Нет единственного A1 ответа" )
            if (parameters == listOf(0xA3) || parameters == listOf(0xA8)) return@repeat
            check(parameters.size == 6 && parameters[0] == 0xA1 && parameters[1] in 1..15) { "Нет валидного согласования A0/A1" }
            return parameters
        }
        return null
    }

    /** Only recovered read/session/identification requests; no arbitrary payload entry point. */
    suspend fun request(command: String): List<Int> = requestPackets(command, false).single()

    suspend fun requestDetails(request: com.vagdiagnostic.app.core.VagDtcDetails.Request): List<Int> {
        require(request.protocol == com.vagdiagnostic.app.core.VagDtcDetails.Protocol.KWP)
        return requestPackets(request.command, false).single()
    }

    /** Recovered list, identification and DTC services can advertise multiple packets. */
    suspend fun requestMessages(command: String): List<List<Int>> {
        require(command in setOf("1A9F", "2204A1", "1A9B", "1800FF00", "1802FF00"))
        return requestPackets(command, true)
    }

    private suspend fun requestPackets(command: String, allowMultiple: Boolean): List<List<Int>> =
        VagReadRetry.run(command, classify = { result: Result<List<List<Int>>> ->
            if (result.exceptionOrNull() is ElmCompletedCanError) VagReadRetry.Outcome.CAN_ERROR
            else if (result.exceptionOrNull() is VagProtocolFailure)
                if ((result.exceptionOrNull() as VagProtocolFailure).noData) VagReadRetry.Outcome.NO_DATA else VagReadRetry.Outcome.PROTOCOL_ERROR
            else if (result.isFailure) VagReadRetry.Outcome.NO_DATA
            else VagReadRetry.response(command, result.getOrThrow().singleOrNull() ?: emptyList())
        }, beforeRetry = { result ->
            (result.exceptionOrNull() as? VagProtocolFailure)?.let { recover(it) }
        }) {
            try { Result.success(requestPacketsOnce(command, allowMultiple)) }
            catch (missing: NoApplicationResponse) { Result.failure(missing) }
            catch (can: ElmCompletedCanError) { Result.failure(can) }
            catch (failure: VagProtocolFailure) {
                if (recoverEngine == null) throw failure
                Result.failure(failure)
            }
        }.getOrThrow()

    private suspend fun recover(cause: Throwable) {
        engine = requireNotNull(recoverEngine).invoke(cause)
        if (!engine.vehicleReady) throw IOException("Связь после восстановления не подтверждена")
        active = false
        // A new negotiated channel is a new transaction boundary. No request,
        // ACK or disconnect is sent to the abandoned stream.
        open(requireNotNull(destination))
    }

    private suspend fun requestPacketsOnce(command: String, allowMultiple: Boolean): List<List<Int>> {
        require(Regex("120004[0-9A-F]{4}").matches(command) || command in setOf("1089", "1A9F", "2204A1", "1800FF00", "1802FF00", "19028D", "1A9B", "22F187", "31B80000") ||
            command in com.vagdiagnostic.app.core.VagUdsInfo.scanFields.map { it.request })
        if (!active && peerClosedAfterReply) throw GracefullyClosedChannel()
        check(active)
        coroutineContext.ensureActive()
        val bytes = Wire.bytes(command)
        val transactionStarted = clockNanos()
        delay(gapMs)
        val sentSeq = sendSeq
        val wire = "%02X%04X".format(0x10 or sentSeq, bytes.size) + command
        val queue = ArrayDeque<List<Int>>()
        sendSeq = (sendSeq + 1) and 15
        val result = mutableListOf<Int>()
        var expected = -1
        var moreMessages = false
        val messages = mutableListOf<List<Int>>()
        var totalBytes = 0
        var awaitingContinuation = false
        var acknowledged = false
        var livePeerConfirmed = false
        var polls = 0
        var lastDataNanos = clockNanos()
        var pendingReplies = 0
        var count = 0
        var completedMessages = false
        var lastDataFrame: List<Int>? = null
        var duplicates = 0
        var sequenceRecovery = false
        try {
            if (!firstRequest) checkIdleChannel()
            firstRequest = false
            var incoming = exchange(wire, allowEmpty = true)
            if (incoming.isEmpty()) {
                // Only a completed entirely empty exchange permits the source
                // transport's second identical send. ACK, data, pending, IO and
                // malformed responses never enter this retry path.
                delay(50)
                coroutineContext.ensureActive()
                incoming = exchange(wire, allowEmpty = true)
                if (incoming.isEmpty()) throw VagProtocolFailure("Нет ответа TP2.0 после двух отправок", noData = true)
            }
            queue.addAll(incoming)
            while (true) {
                coroutineContext.ensureActive()
                if (completedMessages && queue.isEmpty()) return messages
                if ((pendingReplies > 0 || awaitingContinuation || completedMessages || sequenceRecovery) && (clockNanos() - transactionStarted) / 1_000_000 >= 20000)
                    throw VagProtocolFailure("Не получен финальный TP2.0 ответ после ResponsePending за общий срок")
                if (++count > 256) {
                    if (pendingReplies > 0 || awaitingContinuation) throw VagProtocolFailure("Превышен предел кадров незавершённого TP2.0 ответа")
                    error("Слишком много кадров TP2.0")
                }
                if (queue.isEmpty()) {
                    // A transport ACK is not the application reply. Allow a bounded
                    // receive period using channel keepalives, never retransmitting
                    // the diagnostic request. Individual command/IO timeouts still abort.
                    val maxPolls = if (pendingReplies > 0) 64 else 8
                    val maxIdleMs = if (pendingReplies > 0) 5250 else 1500
                    val waitAllowed = polls < maxPolls && (clockNanos() - lastDataNanos) / 1_000_000 < maxIdleMs
                    polls++
                    if (!waitAllowed && (pendingReplies > 0 || awaitingContinuation || sequenceRecovery))
                        throw VagProtocolFailure("Не получен финальный TP2.0 ответ после ResponsePending")
                    if (!waitAllowed && acknowledged && expected < 0 && messages.isEmpty() && livePeerConfirmed)
                        throw NoApplicationResponse(command)
                    check(waitAllowed) {
                        "Неполный ответ TP2.0: получено ${result.size}, ожидается ${if (expected < 0) "начало ответа" else expected.toString()}"
                    }
                    // Avoid exhausting a count limit in a tight loop on fast adapters.
                    delay(if (pendingReplies > 0) gapMs.coerceAtLeast(50) else gapMs)
                    queue.addAll(receiveMore())
                    continue
                }
                val frame = queue.removeFirst()
                check(frame.isNotEmpty())
                val op = frame[0] and 0xF0
                when {
                    op == 0xB0 -> {
                        check(frame.size == 1 && frame[0] and 15 == sendSeq) { "Неверный ACK TP2.0" }
                        acknowledged = true
                    }
                    op == 0x90 -> error("Отрицательное подтверждение TP2.0; запрос не повторяется")
                    frame[0] == 0xA8 -> {
                        if (frame.size == 1 && !completedMessages && acknowledged && expected < 0 &&
                            result.isEmpty() && messages.isEmpty() && pendingReplies == 0 && !awaitingContinuation && queue.isEmpty()) {
                            active = false
                            throw PeerClosedChannel()
                        }
                        check(completedMessages && frame.size == 1) { "ECU закрыл незавершённый канал TP2.0" }
                        active = false
                        peerClosedAfterReply = true
                    }
                    frame[0] == 0xA3 -> {
                        check(frame.size == 1) { "Неверный keepalive A3" }
                        if (active) queue.addAll(exchange(Wire.hex(peerParameters), allowEmpty = true))
                    }
                    frame[0] == 0xA1 -> {
                        check(frame.size == 6) { "Неверный keepalive A1" }
                        livePeerConfirmed = frame == negotiatedPeerParameters
                    }
                    op in listOf(0x00, 0x10, 0x20, 0x30) -> {
                        // A lost ACK may repeat the immediately preceding frame.
                        // Accept only identical bytes within the unfinished packet;
                        // never append twice, advance sequence, or replay a request.
                        if (!completedMessages && expected > result.size && result.isNotEmpty() &&
                            frame == lastDataFrame && (frame[0] and 15) == ((receiveSeq + 15) and 15)) {
                            if (++duplicates > 3) throw VagProtocolFailure("Превышен предел точных повторов TP2.0 кадра")
                            if (queue.isEmpty() && op in listOf(0x00, 0x10)) {
                                delay(gapMs)
                                checkReceiveAckDeadline()
                                queue.addAll(exchange("%02X".format(0xB0 or receiveSeq), allowEmpty = true))
                            }
                            continue
                        }
                        check(!completedMessages) { "Неожиданный второй ответ TP2.0" }
                        check(acknowledged) { "Данные до подтверждения запроса TP2.0" }
                        // A complete matching NRC78 has the source protocol's
                        // explicit sequence resynchronisation exception. Never
                        // apply it to positive data, partial packets or another SID.
                        val resyncPending = expected < 0 && result.isEmpty() && !awaitingContinuation &&
                            op in listOf(0x10, 0x30) && frame.size == 6 &&
                            frame.drop(1) == listOf(0, 3, 0x7F, bytes[0], 0x78)
                        if (frame[0] and 15 != receiveSeq && !resyncPending) {
                            // Request the missing sequence, not another DTC read.
                            // No bytes from the out-of-order frame are published.
                            sequenceRecovery = true
                            delay(gapMs)
                            checkReceiveAckDeadline()
                            queue.addAll(exchange("%02X".format(0x90 or receiveSeq), allowEmpty = true))
                            continue
                        }
                        sequenceRecovery = false
                        receiveSeq = ((frame[0] and 15) + 1) and 15
                        val data = if (expected < 0) {
                            check(frame.size >= 3) { "Нет длины TP2.0" }
                            check(frame[1] and 0x70 == 0) { "Неизвестные флаги длины TP2.0" }
                            moreMessages = frame[1] and 0x80 != 0
                            // Reject before ACK: an unrequested multi-reply transaction
                            // must not be mistaken for a complete single DTC reply.
                            if (moreMessages && !allowMultiple) throw VagProtocolFailure("Неожиданный составной TP2.0 ответ")
                            expected = ((frame[1] and 0x0F) shl 8) or frame[2]
                            check(expected in 1..4095) { "Неверная длина TP2.0" }
                            frame.drop(3)
                        } else frame.drop(1)
                        check(result.size + data.size <= expected) { "Лишние данные TP2.0" }
                        result += data
                        lastDataFrame = frame.toList()
                        lastDataNanos = clockNanos()
                        val last = op == 0x10 || op == 0x30
                        if (last) check(result.size == expected) { "Обрезанный TP2.0 пакет" }
                        if (op == 0x00 || op == 0x10) {
                            delay(gapMs)
                            checkReceiveAckDeadline()
                            // Odd 0 is ELM expected-response limit, not a CAN data byte.
                            val pending = result.size == 3 && result.take(2) == listOf(0x7F, bytes[0]) && result[2] == 0x78
                            if (last && !moreMessages && !pending) queue.addAll(quiet("%02X0".format(0xB0 or receiveSeq)))
                            else queue.addAll(exchange("%02X".format(0xB0 or receiveSeq), allowEmpty = last && (pending || moreMessages)))
                        }
                        if (last) {
                            if (result.size == 3 && result[0] == 0x7F && result[2] == 0x78) {
                                if (result[1] != bytes[0] || ++pendingReplies > 8)
                                    throw VagProtocolFailure("Неверный или повторяющийся TP2.0 ResponsePending")
                                // ACK the pending packet, then receive a new application
                                // packet with the next TP sequence; never repeat the request
                                // or close the session before its final result.
                                result.clear()
                                expected = -1
                                polls = 0
                                continue
                            }
                            totalBytes += result.size
                            check(result.firstOrNull() == bytes[0] + 0x40 ||
                                (result.size == 3 && result.take(2) == listOf(0x7F, bytes[0]))) { "TP2.0 ответ другому сервису" }
                            if (messages.isEmpty() && result.firstOrNull() != 0x7F) {
                                val echo = when (command) {
                                    "1089", "1A9F", "1A9B", "19028D", "31B80000" -> bytes.drop(1).take(1)
                                    "2204A1", "22F187", "22F197", "22F189", "22F19E" -> bytes.drop(1).take(2)
                                    else -> emptyList()
                                }
                                check(result.drop(1).take(echo.size) == echo) { "TP2.0 ответ другой подфункции или DID" }
                            }
                            if (messages.size >= 16 || totalBytes > 16384)
                                throw VagProtocolFailure("Превышен предел составного TP2.0 ответа")
                            messages += result.toList()
                            if (moreMessages) {
                                if (messages.size == 16) throw VagProtocolFailure("Слишком много сообщений TP2.0")
                                awaitingContinuation = true
                                result.clear()
                                expected = -1
                                polls = 0
                                continue
                            }
                            check(queue.none { it.firstOrNull()?.and(0xF0) in listOf(0, 0x10, 0x20, 0x30) }) { "Неожиданный второй ответ TP2.0" }
                            completedMessages = true
                        }
                    }
                    else -> error("Неожиданный кадр TP2.0: ${Wire.hex(frame)}")
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (noResponse: NoApplicationResponse) {
            throw noResponse
        } catch (failure: RuntimeException) {
            // The old transaction is unusable. Only the caller-provided fresh
            // verified transport may establish another command boundary.
            throw VagProtocolFailure("Неполный или повреждённый TP2.0 обмен", failure)
        }
    }

    suspend fun close() {
        if (!active) return
        active = false
        // Never reconnect to send a disconnect; caller disposes failed streams.
        ok("ATSH%03X".format(tx))
        quiet("A80")
    }
    private suspend fun checkIdleChannel() {
        val controls = ArrayDeque(exchange("A3", allowEmpty = true))
        var count = 0
        while (controls.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (++count > 8) throw VagProtocolFailure("Не завершена проверка свободного TP2.0 канала")
            when (val frame = controls.removeFirst()) {
                negotiatedPeerParameters -> Unit
                listOf(0xA3) -> controls.addAll(exchange(Wire.hex(peerParameters), allowEmpty = true))
                listOf(0xA8) -> {
                    if (controls.isNotEmpty()) throw VagProtocolFailure("Лишние данные после закрытия свободного TP2.0 канала")
                    active = false
                    throw PeerClosedChannel()
                }
                else -> throw VagProtocolFailure("Неожиданные данные перед следующим TP2.0 запросом: opcode %02X".format(frame.first()))
            }
        }
    }
    private suspend fun quiet(command: String): List<List<Int>> {
        val raw = command(command, 5500).uppercase()
        val rows = raw.replace('\r', '\n').split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && it != command.uppercase() }
        if (rows.all { it in setOf("OK", "NO DATA") }) return emptyList()
        if (command == "A80") throw VagProtocolFailure("Неожиданный ответ при закрытии TP2.0")
        return frames(raw, rx, command)
    }
    private suspend fun ok(at: String) {
        val raw = command(at)
        check(raw.uppercase().replace(at, "").filterNot { it.isWhitespace() } == "OK") { "ELM отклонил $at" }
    }
    private suspend fun exchange(command: String, allowEmpty: Boolean = false): List<List<Int>> {
        val raw = command(command, 5500)
        // A1 acknowledges the peer's keepalive; no CAN reply is required.
        // Retain queued data even if the adapter ends that exchange with NO DATA.
        if (allowEmpty && raw.trim().uppercase() in setOf("", "NO DATA")) return emptyList()
        return frames(raw, rx, command)
    }
    private suspend fun receiveMore(): List<List<Int>> {
        try {
            // Separate the application receive window from the short ACK window.
            // Two replies permit the peer's A1 followed by application data.
            ok("ATST80")
            val reply = engine.receiveTp20More(rx)
            val raw = reply.raw
            receiveAckDueNanos = reply.ackDueNanos
            ElmCompletedCanError.check(raw, "A32")
            val incoming = if (raw.trim().uppercase() in setOf("", "NO DATA")) emptyList()
                else frames(raw, rx, "A32")
            if (receiveAckDueNanos?.let { System.nanoTime() >= it } == true)
                throw VagProtocolFailure("ELM не завершил приём в срок подтверждения TP2.0")
            ok("ATST%02X".format(frameReceiveTicks))
            return incoming
        } catch (failure: Throwable) {
            // Do not send a restoration setting into an unfinished receive.
            engine.close()
            throw failure
        }
    }
    private fun checkReceiveAckDeadline() {
        val due = receiveAckDueNanos ?: return
        receiveAckDueNanos = null
        if (System.nanoTime() >= due) {
            engine.close()
            throw VagProtocolFailure("Истёк срок подтверждения полученного TP2.0 кадра")
        }
    }
    private suspend fun command(text: String, timeoutMs: Long = 5500): String =
        engine.command(text, timeoutMs, deferJournal = true).also { ElmCompletedCanError.check(it, text) }

    companion object {
        fun timingMs(value: Int): Long {
            require(value in 0..254) { "Неопределённый тайминг TP2.0" }
            val micros = (value and 63) * longArrayOf(100, 1000, 10000, 100000)[value ushr 6]
            return (micros + 999) / 1000
        }
        fun frames(raw: String, expectedId: Int, echo: String): List<List<Int>> {
            val result = mutableListOf<List<Int>>()
            for (row in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
                val clean = row.filterNot { it.isWhitespace() }
                if (clean.isEmpty() || clean == echo) continue
                check(clean.length >= 5 && clean.length % 2 == 1 && clean.all { it in "0123456789ABCDEF" }) { "Некорректный RAW CAN: $row" }
                check(clean.take(3).toInt(16) == expectedId) { "Ответ от незапрошенного CAN ID" }
                val payload = Wire.bytes(clean.drop(3))
                check(payload.size in 1..8) { "Неверная длина CAN" }
                result += payload
            }
            check(result.isNotEmpty()) { "Нет CAN ответа" }
            return result
        }
    }
}
