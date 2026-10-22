package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.ElmFrames
import com.vagdiagnostic.app.core.ObdDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Single writer / single reader. End-of-reply is a mandatory ELM '>' prompt. */
class ElmEngine(private val link: ElmLink, private val log: suspend (String, String, Long, String) -> Unit,
                private val clockNanos: () -> Long = System::nanoTime) {
    private var lastCommandNanos: Long? = null
    /** Monotonic age of the last completed ELM reply. */
    fun idleMs(): Long = lastCommandNanos?.let { (clockNanos() - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L
    private val lock = Mutex()
    var protocol: String = "Не определён"
        private set
    var vehicleReady: Boolean = false
        private set
    var adapterReady: Boolean = false
        private set
    var carError: String = ""
        private set

    suspend fun connect() {
        link.open()
        try {
            val reset = command("ATZ", 8500)
            check(reset.contains("ELM", true) || reset.contains("OK", true)) { "Нет ответа на ATZ" }
            adapterReady = true
            for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")) {
                val result = command(cmd)
                check(result.contains("OK", true)) { "Адаптер не подтвердил $cmd: $result" }
            }
            // Adaptive timing is optional: clone capabilities cannot be assumed.
            try { command("ATAT1") } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* missing optional AT command */ }
            try {
                val response = command("0100", 9000)
                val parsed = ElmFrames.parse("0100", response)
                vehicleReady = ObdDecoder.hasValidVehicleResponse(parsed)
                carError = if (vehicleReady) "" else
                    "Адаптер отвечает, но OBD-II связь с автомобилем не подтверждена: " +
                        parsed.problems.joinToString().ifBlank { response.take(160) }
                if (vehicleReady) {
                    protocol = command("ATDPN").replace("ATDPN", "", true)
                        .replace("\r", "").replace("\n", "").trim().takeUnless { it == "?" || it.isBlank() }
                        ?: "Не определён"
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                vehicleReady = false
                carError = "Адаптер отвечает, но ECU не подтверждён: ${error.message}"
            }
        } catch (error: Throwable) { close(); throw error }
    }

    suspend fun command(text: String, timeoutMs: Long = 5500): String = lock.withLock {
        require(text.matches(Regex("[A-Z0-9]+", RegexOption.IGNORE_CASE))) { "Недопустимая команда" }
        val start = System.nanoTime()
        val collected = StringBuilder()
        var status = "OK"
        try {
            link.write((text.uppercase() + "\r").toByteArray(Charsets.US_ASCII))
            withTimeout(timeoutMs) {
                while (true) {
                    coroutineContext.ensureActive()
                    val current = link.readByte()
                    if (current == -2) continue
                    if (current == -1) throw EOFException("Соединение закрыто до окончания ответа")
                    if (current == '>'.code) break
                    if (collected.length >= 32768) error("Ответ превышает допустимый размер")
                    if (current in 0..127) collected.append(current.toChar())
                }
            }
            val raw = collected.toString()
            lastCommandNanos = clockNanos()
            if (raw.contains("BUFFER FULL", true) || raw.contains("STOPPED", true) || raw.contains("CAN ERROR", true)) {
                status = "ADAPTER_ERROR"
            }
            return@withLock raw
        } catch (error: TimeoutCancellationException) {
            status = "TIMEOUT: ${text} did not end with ELM > prompt"
            throw IOException("Тайм-аут ответа ELM на $text", error)
        } catch (error: Throwable) {
            status = error::class.java.simpleName + ": " + (error.message ?: "")
            throw error
        } finally {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            try { log(text, collected.toString(), elapsed, status) }
            catch (error: CancellationException) { /* preserve cancellation */ throw error }
            catch (_: Exception) { /* a full log must not break car communication */ }
        }
    }
    fun close() { vehicleReady = false; adapterReady = false; link.close() }
}