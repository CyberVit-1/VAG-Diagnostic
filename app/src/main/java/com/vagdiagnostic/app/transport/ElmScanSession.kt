package com.vagdiagnostic.app.transport

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Limits Wi-Fi scan reconnects. A session that has been idle may be stale even
 * when Socket.isConnected is true. Read-only OBD commands only.
 * No Android dependencies: can be checked with scripted ELM byte streams.
 */
class ElmScanSession(
    first: ElmEngine,
    private val wifi: Boolean,
    private val newEngine: () -> ElmEngine,
    private val onEngineChanged: (ElmEngine?) -> Unit,
    private val idleReconnectMs: Long = 8_000L,
    private val onEvent: suspend (String, String) -> Unit = { _, _ -> }
) {
    var current: ElmEngine = first
        private set
    var hasReactiveRecovery: Boolean = false
        private set

    private suspend fun note(event: String, detail: String) {
        try { onEvent(event, detail) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* journaling must not break a live ELM session */ }
    }

    /** Refresh an old Wi-Fi session before starting OBD discovery. */
    suspend fun prepare() {
        if (wifi && current.idleMs() >= idleReconnectMs) {
            note("WIFI_IDLE_REFRESH", "Last complete ELM reply was ${current.idleMs()} ms ago")
            reopen()
        }
    }

    suspend fun read(command: String, timeoutMs: Long): String {
        require(command in listOf("0100", "03", "07", "0A")) {
            "Автовосстановление разрешено только для read-only OBD"
        }
        try {
            return current.command(command, timeoutMs)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (!wifi || failure !is IOException) throw failure
            if (hasReactiveRecovery) {
                note("WIFI_RECOVERY_EXHAUSTED", failure::class.java.simpleName + ": " + failure.message)
                throw failure
            }
            note("WIFI_IO_RECOVERY", failure::class.java.simpleName + ": " + failure.message)
            hasReactiveRecovery = true
            reopen()
            // One reactive recovery maximum: further failures propagate.
            return current.command(command, timeoutMs)
        }
    }

    private suspend fun reopen() {
        currentCoroutineContext().ensureActive()
        current.close()
        onEngineChanged(null)
        val candidate = newEngine()
        try {
            candidate.connect()
            currentCoroutineContext().ensureActive()
            if (!candidate.vehicleReady) throw IOException(
                "Сокет открыт, но OBD-II ответ не подтверждён: ${candidate.carError}"
            )
            current = candidate
            onEngineChanged(candidate)
            note("WIFI_SESSION_RESTORED", "ELM and OBD-II reply confirmed")
        } catch (failure: Throwable) {
            candidate.close()
            note("WIFI_SESSION_RESTORE_FAILED", failure::class.java.simpleName + ": " + failure.message)
            throw failure
        }
    }

    fun close() {
        current.close()
        onEngineChanged(null)
    }
}
