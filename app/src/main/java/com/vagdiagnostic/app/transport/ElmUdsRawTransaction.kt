package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Generic CAN receive/send path. Caller configures the physical route with
 * CAF0 and the selected FC mode first. This helper never chooses another ECU
 * or retries a request. With CFC1, only the adapter emits flow control. */
internal object ElmUdsRawTransaction {
    /** Complete adapter rejection before any ECU/ISO-TP response. */
    class UnsupportedCommand(val request: String? = null) : IllegalStateException("Адаптер отклонил CAN запрос" + (request?.let { ": $it" } ?: ""))
    const val FLOW_CONTROL = "300010AAAAAAAAAA"
    private const val TRANSACTION_TIMEOUT_MS = 20000L
    suspend fun read(engine: ElmEngine, target: VagOemReadOnly.Route, request: String,
        pendingMs: Long = 5250, automaticFlowControl: Boolean = false): List<Int>? {
        require(ElmUdsReadRequests.allowed(request))
        check(engine.vehicleReady)
        requireNotNull(target.udsTx)
        val rx = requireNotNull(target.udsRx)
        val outgoing = VagIsoTpOutgoing.frames(VagOemReadOnly.bytes(request))
        require(outgoing.size == 1) { "Текущее read-only чтение требует один исходящий CAN кадр" }
        // The establishment probe has a separate one-response contract.
        // Ordinary reads retain their padded frame and unlimited response count:
        // a count hint there could truncate a multi-frame DTC response.
        val wire = if (request == "3E00") "023E001"
            else outgoing.single().joinToString("") { "%02X".format(it) }
        val frames = VagIsoTpCollection(setOf(rx), request, automaticFlowControl)
        var completedSilence = false
        var monitorMode = false
        // 586 frames at the configured STmin=0x10 already exceed 8.5 seconds.
        // Share the existing outer deadline instead of cutting off a legal
        // long reply; this does not extend the total transaction budget.
        suspend fun send(command: String): String = engine.command(command, TRANSACTION_TIMEOUT_MS, deferJournal = true)
            .also { ElmCompletedCanError.check(it, command) }
        fun accept(raw: String, echo: String) {
            val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != echo }
            require(rows.none { it == "OK" || it == "STOPPED" } &&
                ("NODATA" !in rows || rows == listOf("NODATA"))) { "Противоречивый статус RAW UDS" }
            frames.accept(raw, echo)
        }
        try {
            return withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                val initial = send(wire)
                val rows = initial.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                    .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != wire }
                if (rows == listOf("?")) throw UnsupportedCommand(request)
                if (rows == listOf("NODATA")) { completedSilence = true; return@withTimeoutOrNull null }
                accept(initial, wire)
                var receives = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    require(frames.unknownIds.isEmpty() && frames.invalidIds.isEmpty()) { "Ответ неподтверждённого ECU или повреждённый CAN кадр" }
                    frames.payloads[rx]?.let {
                        if (monitorMode && automaticFlowControl) {
                            val raw = send("ATCFC1").uppercase().replace("ATCFC1", "").filterNot(Char::isWhitespace)
                            if (raw != "OK") throw IOException("Не восстановлен автоматический Flow Control после RAW UDS")
                        }
                        return@withTimeoutOrNull it
                    }
                    if (rx in frames.flowControlNeeded) {
                        frames.flowControlSent(rx)
                        accept(send(FLOW_CONTROL), FLOW_CONTROL)
                    } else {
                        if (++receives > 8) throw VagProtocolFailure("Превышен предел RAW UDS продолжения")
                        if (!monitorMode && automaticFlowControl) {
                            val raw = send("ATCFC0").uppercase().replace("ATCFC0", "").filterNot(Char::isWhitespace)
                            if (raw != "OK") throw IOException("Не отключён автоматический Flow Control перед RAW мониторингом")
                            frames.automaticFlowControlForNewMessages(false)
                        }
                        monitorMode = true
                        frames.accept(engine.receivePendingCanFrames(setOf(rx), pendingMs, deferJournal = true), "ATMA")
                    }
                }
                @Suppress("UNREACHABLE_CODE") emptyList<Int>()
            } ?: if (completedSilence) null
                else throw VagProtocolFailure("Не завершён RAW UDS ответ")
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (unsupported: UnsupportedCommand) { throw unsupported }
        catch (can: ElmCompletedCanError) {
            if (frames.pending.isNotEmpty() || frames.unfinished.isNotEmpty()) {
                engine.close()
                throw VagProtocolFailure("CAN ERROR во время незавершённого RAW UDS ответа", can)
            }
            throw can
        }
        catch (failure: VagProtocolFailure) { engine.close(); throw VagProtocolFailure(failure.message.orEmpty(), failure) }
        catch (failure: IOException) { engine.close(); throw failure }
        catch (failure: RuntimeException) { engine.close(); throw VagProtocolFailure("Повреждённый RAW UDS ответ", failure) }
        finally { withContext(NonCancellable) { engine.flushJournal() } }
    }
}
