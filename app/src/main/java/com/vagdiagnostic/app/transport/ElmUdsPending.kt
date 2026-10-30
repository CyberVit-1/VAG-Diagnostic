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
        initialRaw: String, timeoutMs: Long, initialPending: Int): List<Int> {
        val rx = requireNotNull(target.udsRx)
        requireNotNull(target.udsTx)
        val frames = VagIsoTpCollection(setOf(rx), request)
        frames.accept(initialRaw)
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
                        frames.accept(engine.command("3000100000000000", timeoutMs, deferJournal = true), "3000100000000000")
                    } else {
                        if (++receives > (9 - initialPending).coerceAtLeast(1)) throw IOException("Превышен предел ожидания UDS")
                        frames.accept(engine.receivePendingCanFrames(setOf(rx), timeoutMs, deferJournal = true), "ATMA")
                    }
                }
                @Suppress("UNREACHABLE_CODE") emptyList<Int>()
            } ?: throw IOException("Истёк общий срок ожидания UDS")
            return result
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (failure: Exception) { engine.close(); throw IOException("UDS продолжение не завершено: ${failure.message}", failure) }
        finally { withContext(NonCancellable) { engine.flushJournal() } }
    }
}
