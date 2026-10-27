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
                private val clockNanos: () -> Long = System::nanoTime,
                private val timestampedLog: (suspend (String, String, Long, String, Long) -> Unit)? = null) {
    private data class JournalRecord(val command: String, val raw: String, val elapsed: Long, val status: String, val timestamp: Long)
    private val deferredJournal = ArrayDeque<JournalRecord>()
    /** Flush after an ECU ACK/disconnect boundary, never while an ACK is due.
     * Original wire timestamps are retained even if persistence is delayed. */
    suspend fun flushJournal() {
        while (deferredJournal.isNotEmpty()) persist(deferredJournal.removeFirst())
    }
    private suspend fun persist(record: JournalRecord) {
        try {
            val detailed = timestampedLog
            if (detailed != null) detailed(record.command, record.raw, record.elapsed, record.status, record.timestamp)
            else log(record.command, record.raw, record.elapsed, record.status)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Preserve the existing best-effort journal contract. */ }
    }
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
    // Null means do not attempt a mode-dependent DTC decode.
    var isCanVehicle: Boolean? = null
        private set
    /** ECU CAN IDs confirmed by a real positive 41 00 reply. */
    var observedCanResponderIds: Set<String> = emptySet()
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
            // An unsupported ATAT1 must still finish with the ELM '>' prompt.
            // Swallowing a transport timeout here would desynchronize the next 0100.
            val adaptive = command("ATAT1")
            if (!adaptive.contains("OK", ignoreCase = true) &&
                !adaptive.contains("?", ignoreCase = true)) {
                throw IOException("Ответ ATAT1 не подтверждён: ${adaptive.take(100)}")
            }
            val response = command("0100", 9000)
            val parsed = ElmFrames.parse("0100", response)
            vehicleReady = ObdDecoder.hasValidVehicleResponse(parsed)
            observedCanResponderIds = if (vehicleReady) {
                parsed.messagesFor(0x41).filterValues { responses ->
                    responses.any { it.size >= 6 && it[1].toInt() == 0 }
                }.keys.filter {
                    Regex("^(7E[8-F]|18D[AB][0-9A-F]{4})$").matches(it)
                }.toSet()
            } else emptySet()
            carError = if (vehicleReady) "" else
                "Адаптер отвечает, но OBD-II связь с автомобилем не подтверждена: " +
                    parsed.problems.joinToString().ifBlank { response.take(160) }
            if (vehicleReady) {
                // ATDPN is informational, and a completed '?' means unsupported.
                // It must not turn an already verified 0100 vehicle reply into a failure.
                val selected = command("ATDPN").replace("ATDPN", "", true)
                    .replace("\r", "").replace("\n", "").trim()
                protocol = selected.takeUnless { it == "?" || it.isBlank() }
                    ?: "Не определён"
                // ELM automatic-protocol reply A6..A9 is ISO 15765-4 CAN.
                // A missing ATDPN can still be resolved from concrete CAN IDs
                // in the verified 0100 response, but never from ELM version text.
                val confirmedCanHeaders = parsed.messagesFor(0x41).keys.any {
                    Regex("^(7E[8-F]|18D[AB][0-9A-F]{4})$").matches(it)
                }
                isCanVehicle = when {
                    // Real message framing beats an inconsistent ATDPN from a clone.
                    confirmedCanHeaders -> true
                    Regex("^A?[6-9]$").matches(protocol) -> true
                    Regex("^A?[1-5]$").matches(protocol) -> false
                    else -> null
                }
            }
        } catch (error: Throwable) { close(); throw error }
    }

    suspend fun command(text: String, timeoutMs: Long = 5500, deferJournal: Boolean = false): String = lock.withLock {
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
            val record = JournalRecord(text, collected.toString(), elapsed, status, System.currentTimeMillis())
            if (deferJournal) {
                deferredJournal.addLast(record)
                if (deferredJournal.size > 512) throw IOException("Превышен буфер протокольного журнала")
            } else persist(record)
        }
    }
    fun close() { vehicleReady = false; adapterReady = false; isCanVehicle = null; observedCanResponderIds = emptySet(); link.close() }
}
