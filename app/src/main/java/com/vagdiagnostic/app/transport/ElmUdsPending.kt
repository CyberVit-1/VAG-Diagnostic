package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagIsoTpCollection
import com.vagdiagnostic.app.core.VagOemReadOnly
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Receive a pending physical UDS reply without reissuing its request. Raw
 * reception permits a late FirstFrame followed by explicit physical FC. */
internal object ElmUdsPending {
    private const val TRANSACTION_TIMEOUT_MS = 20000L
    suspend fun read(engine: ElmEngine, target: VagOemReadOnly.Route, request: String,
        initialRaw: String?, timeoutMs: Long, initialPending: Int): List<Int> {
        val rx = requireNotNull(target.udsRx)
        requireNotNull(target.udsTx)
        val frames = VagIsoTpCollection(setOf(rx), request)
        fun accept(raw: String, echo: String = request) {
            val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
                .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != echo }
            require(rows.none { it == "OK" || it == "STOPPED" } &&
                ("NODATA" !in rows || rows == listOf("NODATA"))) { "Противоречивый статус UDS продолжения" }
            frames.accept(raw, echo)
            require(frames.unknownIds.isEmpty() && frames.invalidIds.isEmpty()) { "Ответ неподтверждённого ECU или повреждённый CAN кадр" }
        }
        suspend fun ok(cmd: String) {
            val raw = engine.command(cmd, deferJournal = true).uppercase().replace(cmd, "").filterNot(Char::isWhitespace)
            if (raw != "OK") throw IOException("ELM отклонил настройку ожидания $cmd")
        }
        try {
            if (initialRaw == null) frames.recordPending(rx, initialPending) else accept(initialRaw)
            val result = withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                ok("ATCFC0"); ok("ATCAF0")
                var receives = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    frames.payloads[rx]?.let {
                        // No request is outstanding before returning to automatic FC.
                        ok("ATCAF1"); ok("ATCFC1")
                        return@withTimeoutOrNull it
                    }
                    if (rx in frames.flowControlNeeded) {
                        // The current physical TX/header was established by Uds.open.
                        // Retain the same 11/29-bit address, never use functional FC.
                        frames.flowControlSent(rx)
                        val flowControl = if (engine.manualUds) ElmUdsRawTransaction.FLOW_CONTROL else "3000100000000000"
                        // P2* bounds waiting for the next response to start.
                        // Once its FirstFrame is received, the complete CF train
                        // shares the existing overall transaction deadline.
                        accept(engine.command(flowControl, TRANSACTION_TIMEOUT_MS, deferJournal = true), flowControl)
                    } else {
                        if (++receives > (9 - initialPending).coerceAtLeast(1)) throw VagProtocolFailure("Превышен предел ожидания UDS")
                        accept(engine.receivePendingCanFrames(setOf(rx), timeoutMs, deferJournal = true), "ATMA")
                    }
                }
                @Suppress("UNREACHABLE_CODE") emptyList<Int>()
            } ?: throw VagProtocolFailure("Истёк общий срок ожидания UDS")
            return result
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (failure: VagProtocolFailure) {
            engine.close()
            // An expired pending transaction needs a fresh connection, not the
            // completed-NO-DATA budget or a repeat on the old stream.
            throw VagProtocolFailure("UDS продолжение не завершено: ${failure.message}", failure)
        }
        catch (failure: RuntimeException) { engine.close(); throw VagProtocolFailure("Повреждённое UDS продолжение", failure) }
        catch (failure: Exception) { engine.close(); throw IOException("UDS продолжение не завершено: ${failure.message}", failure) }
        finally { withContext(NonCancellable) { engine.flushJournal() } }
    }
}
