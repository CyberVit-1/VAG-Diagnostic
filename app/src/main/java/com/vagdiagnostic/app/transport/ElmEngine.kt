package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.ElmFrames
import com.vagdiagnostic.app.core.ObdDecoder
import com.vagdiagnostic.app.core.VagAddressedCan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
    // Custom FC from a 29-bit OEM route must not leak into later 11-bit UDS.
    internal var needsFlowControlReset = false
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
            needsFlowControlReset = false
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
                    if (current == -2) { yield(); continue }
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
    /** Receive a delayed UDS single frame without retransmitting the request.
     * Monitor mode cannot send ISO-TP flow control: reject a First Frame
     * explicitly rather than pretending that an incomplete reply is valid.
     * Any failure disposes the stream; no session-close request can consume
     * the still outstanding diagnostic response. */
    suspend fun receivePendingUdsFrame(expectedId: Int, timeoutMs: Long): String = lock.withLock {
        require(expectedId in 0..0x1FFFFFFF && timeoutMs in 1..6000)
        val collected = StringBuilder()
        val line = StringBuilder()
        val start = System.nanoTime()
        var status = "OK"
        try {
            link.write("ATMA\r".toByteArray(Charsets.US_ASCII))
            var frame: String? = null
            withTimeoutOrNull(timeoutMs) {
                while (frame == null) {
                    coroutineContext.ensureActive()
                    val b = link.readByte()
                    if (b == -2) { yield(); continue }
                    if (b == -1) throw EOFException("Соединение закрыто при ожидании UDS")
                    if (b == '>'.code) throw IOException("ELM завершил ожидание отложенного UDS без ответа")
                    if (collected.length >= 32768) throw IOException("Переполнение отложенного UDS")
                    if (b in 0..127) collected.append(b.toChar())
                    if (b == '\r'.code || b == '\n'.code) {
                        val row = line.toString().trim().uppercase()
                        line.clear()
                        if (row.isEmpty() || row == "ATMA") continue
                        val decoded = VagAddressedCan.decodeSingleEcu(row, expectedId)
                        if (!decoded.valid)
                            throw IOException("Некорректный отложенный UDS: ${decoded.problems.joinToString()}; монитор ELM не выполняет ISO-TP flow control")
                        frame = row
                    } else if (b in 0..127) line.append(b.toChar())
                }
            } ?: throw IOException("Истёк срок ожидания отложенного UDS")
            // Any received character stops ATMA. A lone CR is consumed as the
            // stop character, not a repeat of the preceding diagnostic request.
            link.write(byteArrayOf('\r'.code.toByte()))
            withTimeoutOrNull(1500) {
                while (true) {
                    coroutineContext.ensureActive()
                    val b = link.readByte()
                    if (b == -2) { yield(); continue }
                    if (b == -1) throw EOFException("ELM закрыл поток при завершении ATMA")
                    if (b == '>'.code) break
                    if (collected.length >= 32768) throw IOException("Переполнение ATMA")
                    if (b in 0..127) collected.append(b.toChar())
                }
            } ?: throw IOException("ELM не завершил ATMA")
            // STOPPED is expected here; a second frame would be unassociated.
            val tail = collected.toString().uppercase().substringAfter(frame!!).trim()
            if (tail.isNotEmpty() && tail != "STOPPED") throw IOException("Лишние данные после отложенного UDS: $tail")
            lastCommandNanos = clockNanos()
            frame!!
        } catch (error: Throwable) {
            status = error::class.java.simpleName + ": " + (error.message ?: "")
            close()
            if (error is CancellationException || error is IOException) throw error
            throw IOException("Некорректный отложенный UDS: ${error.message}", error)
        } finally {
            val record = JournalRecord("ATMA", collected.toString(), (System.nanoTime() - start) / 1_000_000,
                status, System.currentTimeMillis())
            withContext(NonCancellable) { persist(record) }
        }
    }
    /** Receive raw, strictly identified CAN frames without resending a pending
     * read. Stop ATMA before the caller emits any required physical FC. */
    suspend fun receivePendingCanFrames(expectedIds: Set<Int>, timeoutMs: Long,
        deferJournal: Boolean = false): String = lock.withLock {
        require(expectedIds.isNotEmpty() && expectedIds.all { it in 0..0x1FFFFFFF } && timeoutMs in 1..6000)
        val collected = StringBuilder()
        val line = StringBuilder()
        val received = mutableListOf<String>()
        val start = System.nanoTime()
        var status = "OK"
        fun accept(row: String, stopping: Boolean) {
            val clean = row.uppercase().filterNot(Char::isWhitespace)
            if (clean.isEmpty() || clean == "ATMA" || (stopping && clean == "STOPPED")) return
            require(clean.all { it in "0123456789ABCDEF" }) { "Неверная строка отложенного CAN" }
            val headerSize = if (clean.length % 2 == 1) 3 else 8
            require(clean.length >= headerSize + 2 && clean.length <= headerSize + 16) { "Неверная длина отложенного CAN" }
            val id = clean.take(headerSize).toLong(16)
            require(id in 0..0x1FFFFFFF && id.toInt() in expectedIds) { "Незапрошенный CAN ID при ожидании" }
            require((headerSize == 3) == (id <= 0x7FF)) { "Не совпал тип отложенного CAN адреса" }
            received += clean
        }
        try {
            link.write("ATMA\r".toByteArray(Charsets.US_ASCII))
            withTimeoutOrNull(timeoutMs) {
                while (received.isEmpty()) {
                    coroutineContext.ensureActive()
                    val b = link.readByte()
                    if (b == -2) { yield(); continue }
                    if (b == -1) throw EOFException("Поток закрыт при ожидании CAN")
                    if (b == '>'.code) throw IOException("ELM завершил ожидание без CAN ответа")
                    if (collected.length >= 32768) throw IOException("Переполнение отложенного CAN")
                    if (b in 0..127) collected.append(b.toChar())
                    if (b == '\r'.code || b == '\n'.code) { accept(line.toString(), false); line.clear() }
                    else if (b in 0..127) line.append(b.toChar())
                }
            } ?: throw IOException("Истёк срок ожидания CAN ответа")
            link.write(byteArrayOf('\r'.code.toByte()))
            withTimeoutOrNull(1500) {
                while (true) {
                    coroutineContext.ensureActive()
                    val b = link.readByte()
                    if (b == -2) { yield(); continue }
                    if (b == -1) throw EOFException("Поток закрыт при завершении CAN мониторинга")
                    if (b == '>'.code) { accept(line.toString(), true); line.clear(); break }
                    if (collected.length >= 32768) throw IOException("Переполнение CAN мониторинга")
                    if (b in 0..127) collected.append(b.toChar())
                    if (b == '\r'.code || b == '\n'.code) { accept(line.toString(), true); line.clear() }
                    else if (b in 0..127) line.append(b.toChar())
                }
            } ?: throw IOException("ELM не завершил CAN мониторинг")
            lastCommandNanos = clockNanos()
            received.joinToString("\r")
        } catch (error: Throwable) {
            status = error::class.java.simpleName + ": " + (error.message ?: "")
            close()
            if (error is CancellationException || error is IOException) throw error
            throw IOException("Некорректный отложенный CAN: ${error.message}", error)
        } finally {
            val record = JournalRecord("ATMA", collected.toString(), (System.nanoTime() - start) / 1_000_000,
                status, System.currentTimeMillis())
            if (deferJournal) {
                deferredJournal.addLast(record)
                if (deferredJournal.size > 512) throw IOException("Превышен буфер протокольного журнала")
            } else withContext(NonCancellable) { persist(record) }
        }
    }

    fun close() { vehicleReady = false; adapterReady = false; isCanVehicle = null; observedCanResponderIds = emptySet(); needsFlowControlReset = false; link.close() }
}
