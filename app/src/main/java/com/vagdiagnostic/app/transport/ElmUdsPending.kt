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
    suspend fun read(engine: ElmEngine, target: VagOemReadOnly.Route, request: String,
        initialRaw: String?, timeoutMs: Long, initialPending: Int): List<Int> {
        val rx = requireNotNull(target.udsRx)
        requireNotNull(target.udsTx)
        val frames = VagIsoTpCollection(setOf(rx), request)
        if (initialRaw == null) frames.recordPending(rx, initialPending) else frames.accept(initialRaw)
        suspend fun ok(cmd: String) {
            val raw = engine.command(cmd, deferJournal = true).uppercase().replace(cmd, "").filterNot(Char::isWhitespace)
            if (raw != "OK") throw IOException("ELM отклонил настройку ожидания $cmd")
        }
        try {
            val result = withTimeoutOrNull(20000) {
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
                        frames.accept(engine.command(flowControl, timeoutMs, deferJournal = true), flowControl)
                    } else {
                        if (++receives > (9 - initialPending).coerceAtLeast(1)) throw VagProtocolFailure("Превышен предел ожидания UDS")
                        frames.accept(engine.receivePendingCanFrames(setOf(rx), timeoutMs, deferJournal = true), "ATMA")
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
