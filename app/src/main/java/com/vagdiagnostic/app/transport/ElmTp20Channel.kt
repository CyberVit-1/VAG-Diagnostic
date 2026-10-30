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
class ElmTp20Channel(private val engine: ElmEngine, private val clockNanos: () -> Long = System::nanoTime) {
    /** Acknowledged request, no application packet, and matching live keepalives.
     * Caller may terminate this channel before choosing another read path. */
    class NoApplicationResponse : IllegalStateException("ECU не дал прикладного ответа при работающем TP2.0 канале")
    class NoChannelResponse : IllegalStateException("ECU не ответил на CHANNEL SETUP")
    /** Explicit peer close after ACK, before any application response. The
     * caller must reset the transport before another diagnostic transaction. */
    class PeerClosedChannel : IOException("ECU закрыл TP2.0 канал до прикладного ответа")
    private var tx = 0
    private var rx = 0
    private var sendSeq = 0
    private var receiveSeq = 0
    private var active = false
    private var gapMs = 20L
    private var responseMs = 200L
    private var peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)
    private var negotiatedPeerParameters: List<Int> = emptyList()

    suspend fun open(destination: Int) {
        require(destination in Wire.routes.values.mapNotNull { it.tp }) { "TP2.0 адрес не в профиле" }
        try { openChecked(destination) }
        catch (cancel: CancellationException) { throw cancel }
        catch (absent: NoChannelResponse) { throw absent }
        catch (failure: RuntimeException) {
            // A malformed setup/A1 is not a completed absence response and must
            // not trigger a second protocol on an ambiguous receive stream.
            throw IOException("Повреждённое согласование TP2.0 канала", failure)
        }
    }
    private suspend fun openChecked(destination: Int) {
        check(!active)
        // Configure variable-length raw frames in the profile's CAN mode.
        for (at in listOf("ATH1", "ATCAF0", "ATCFC0", "ATSP6", "ATD0", "ATV1", "ATCEA", "ATAT0", "ATST10",
            "ATSH200", "ATCRA%03X".format(0x200 or destination))) ok(at)
        val dpn = command("ATDPN").trim().uppercase()
        check(dpn in setOf("6", "A6")) { "ELM не подтвердил CAN 6: $dpn" }
        val request = "%02XC00010000301".format(destination)
        val setup = command(request + "1", 5500)
        if (setup.trim().uppercase() == "NO DATA") throw NoChannelResponse()
        val frames = frames(setup, 0x200 or destination, request + "1")
        check(frames.size == 1) { "Неоднозначный ответ CHANNEL SETUP" }
        val d = frames.single()
        check(d.size == 7 && d[0] == 0 && d[6] == 1) { "Неверная структура CHANNEL SETUP" }
        check(d[1] == 0xD0) { "TP2.0 отклонил канал: %02X".format(d[1]) }
        check(d[3] and 0xF0 == 0 && d[5] and 0xF0 == 0) { "Неверные флаги CAN канала" }
        rx = d[2] or (d[3] shl 8)
        tx = d[4] or (d[5] shl 8)
        check(rx == 0x300 && tx in 1..0x7FF && tx != rx && tx != 0x200) { "Неверная пара CAN канала" }
        // D0 is already a live negotiated channel; disconnect even if A1 fails.
        active = true
        ok("ATSH%03X".format(tx)); ok("ATCRA%03X".format(rx)); ok("ATST10")
        // Some adapters replay the exact D0 setup frame once after changing
        // the receive filter. Ignore only that identical negotiated frame here;
        // every other foreign ID/data remains an error, including later traffic.
        val parametersRaw = command("A00194FF82FF")
        val setupRow = "%03X".format(0x200 or destination) + Wire.hex(d)
        val rows = parametersRaw.replace('>', '\n').replace('\r', '\n').split('\n')
        var duplicateSetup = false
        val filtered = rows.filter { row ->
            if (row.filterNot { it.isWhitespace() }.uppercase() == setupRow) {
                check(!duplicateSetup) { "Повторные CHANNEL SETUP после согласования" }
                duplicateSetup = true
                false
            } else true
        }.joinToString("\n")
        val parameters = frames(filtered, rx, "A00194FF82FF").singleOrNull()
            ?: error("Нет единственного A1 ответа" )
        check(parameters.size == 6 && parameters[0] == 0xA1 && parameters[1] in 1..15) { "Нет валидного согласования A0/A1" }
        responseMs = timingMs(parameters[2])
        gapMs = timingMs(parameters[4])
        check(responseMs in 1..2000 && gapMs in 0..1000) { "Неподдержанные тайминги TP2.0" }
        peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)
        negotiatedPeerParameters = parameters.toList()
        // The ELM prompt must arrive BEFORE the peer's ACK deadline. A long
        // T1-sized receive window would delay our ACK until the ECU gave up.
        val receiveTicks = ((gapMs + 24 + 3) / 4).coerceAtLeast(1)
        check(receiveTicks * 4 + gapMs <= 160) { "T3 не позволяет подтвердить кадр в объявленный T1" }
        ok("ATST%02X".format(receiveTicks))
        sendSeq = 0; receiveSeq = 0
    }

    /** Only recovered read/session/identification requests; no arbitrary payload entry point. */
    suspend fun request(command: String): List<Int> = requestPackets(command, false).single()

    /** Recovered list, identification and DTC services can advertise multiple packets. */
    suspend fun requestMessages(command: String): List<List<Int>> {
        require(command in setOf("1A9F", "2204A1", "1A9B", "1800FF00", "1802FF00"))
        return requestPackets(command, true)
    }

    private suspend fun requestPackets(command: String, allowMultiple: Boolean): List<List<Int>> {
        require(command in setOf("1089", "1A9F", "2204A1", "1800FF00", "1802FF00", "19028D", "1A9B", "22F187", "31B80000"))
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
        try {
            queue.addAll(exchange(wire))
            while (true) {
                coroutineContext.ensureActive()
                if (completedMessages && queue.isEmpty()) return messages
                if ((pendingReplies > 0 || awaitingContinuation || completedMessages) && (clockNanos() - transactionStarted) / 1_000_000 >= 20000)
                    throw IOException("Не получен финальный TP2.0 ответ после ResponsePending за общий срок")
                if (++count > 256) {
                    if (pendingReplies > 0 || awaitingContinuation) throw IOException("Превышен предел кадров незавершённого TP2.0 ответа")
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
                    if (!waitAllowed && (pendingReplies > 0 || awaitingContinuation))
                        throw IOException("Не получен финальный TP2.0 ответ после ResponsePending")
                    if (!waitAllowed && acknowledged && expected < 0 && messages.isEmpty() && livePeerConfirmed)
                        throw NoApplicationResponse()
                    check(waitAllowed) {
                        "Неполный ответ TP2.0: получено ${result.size}, ожидается ${if (expected < 0) "начало ответа" else expected.toString()}"
                    }
                    // Avoid exhausting a count limit in a tight loop on fast adapters.
                    delay(if (pendingReplies > 0) gapMs.coerceAtLeast(50) else gapMs)
                    queue.addAll(exchange("A3", allowEmpty = true))
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
                        check(!completedMessages) { "Неожиданный второй ответ TP2.0" }
                        check(acknowledged) { "Данные до подтверждения запроса TP2.0" }
                        check(frame[0] and 15 == receiveSeq) { "Пропущен/перепутан TP2.0 sequence" }
                        receiveSeq = (receiveSeq + 1) and 15
                        val data = if (expected < 0) {
                            check(frame.size >= 3) { "Нет длины TP2.0" }
                            check(frame[1] and 0x70 == 0) { "Неизвестные флаги длины TP2.0" }
                            moreMessages = frame[1] and 0x80 != 0
                            // Reject before ACK: an unrequested multi-reply transaction
                            // must not be mistaken for a complete single DTC reply.
                            if (moreMessages && !allowMultiple) throw IOException("Неожиданный составной TP2.0 ответ")
                            expected = ((frame[1] and 0x0F) shl 8) or frame[2]
                            check(expected in 1..4095) { "Неверная длина TP2.0" }
                            frame.drop(3)
                        } else frame.drop(1)
                        check(result.size + data.size <= expected) { "Лишние данные TP2.0" }
                        result += data
                        lastDataNanos = clockNanos()
                        val last = op == 0x10 || op == 0x30
                        if (last) check(result.size == expected) { "Обрезанный TP2.0 пакет" }
                        if (op == 0x00 || op == 0x10) {
                            delay(gapMs)
                            // Odd 0 is ELM expected-response limit, not a CAN data byte.
                            val pending = result.size == 3 && result.take(2) == listOf(0x7F, bytes[0]) && result[2] == 0x78
                            if (last && !moreMessages && !pending) queue.addAll(quiet("%02X0".format(0xB0 or receiveSeq)))
                            else queue.addAll(exchange("%02X".format(0xB0 or receiveSeq), allowEmpty = last && (pending || moreMessages)))
                        }
                        if (last) {
                            if (result.size == 3 && result[0] == 0x7F && result[2] == 0x78) {
                                if (result[1] != bytes[0] || ++pendingReplies > 8)
                                    throw IOException("Неверный или повторяющийся TP2.0 ResponsePending")
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
                                    "2204A1", "22F187" -> bytes.drop(1).take(2)
                                    else -> emptyList()
                                }
                                check(result.drop(1).take(echo.size) == echo) { "TP2.0 ответ другой подфункции или DID" }
                            }
                            if (messages.size >= 16 || totalBytes > 16384)
                                throw IOException("Превышен предел составного TP2.0 ответа")
                            messages += result.toList()
                            if (moreMessages) {
                                if (messages.size == 16) throw IOException("Слишком много сообщений TP2.0")
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
            // A sent request with invalid/missing framing has no trustworthy
            // transaction boundary. Do not close/reopen or try another protocol.
            throw IOException("Неполный или повреждённый TP2.0 обмен", failure)
        }
    }

    suspend fun close() {
        if (!active) return
        active = false
        // Never reconnect to send a disconnect; caller disposes failed streams.
        ok("ATSH%03X".format(tx))
        quiet("A80")
    }
    private suspend fun quiet(command: String): List<List<Int>> {
        val raw = command(command, 5500).uppercase()
        val rows = raw.replace('\r', '\n').split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && it != command.uppercase() }
        if (rows.all { it in setOf("OK", "NO DATA") }) return emptyList()
        if (command == "A80") throw IOException("Неожиданный ответ при закрытии TP2.0")
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
    private suspend fun command(text: String, timeoutMs: Long = 5500): String =
        engine.command(text, timeoutMs, deferJournal = true)

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
