package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly as Wire
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Legacy CAN connection over the shared ELM stream.
 * Caller owns a disposable engine and always restores standard OBD afterwards.
 * No arbitrary CAN addresses, retransmission after transport failure or writes.
 */
class ElmTp20Channel(private val engine: ElmEngine) {
    private var tx = 0
    private var rx = 0
    private var sendSeq = 0
    private var receiveSeq = 0
    private var active = false
    private var gapMs = 20L
    private var responseMs = 200L
    private var peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)

    suspend fun open(destination: Int) {
        require(destination in Wire.routes.values.mapNotNull { it.tp }) { "TP2.0 адрес не в профиле" }
        check(!active)
        // Configure variable-length raw frames in the profile's CAN mode.
        for (at in listOf("ATH1", "ATCAF0", "ATCFC0", "ATSP6", "ATD0", "ATV1", "ATCEA", "ATAT0", "ATST10",
            "ATSH200", "ATCRA%03X".format(0x200 or destination))) ok(at)
        val dpn = command("ATDPN").trim().uppercase()
        check(dpn in setOf("6", "A6")) { "ELM не подтвердил CAN 6: $dpn" }
        val request = "%02XC00010000301".format(destination)
        val frames = frames(command(request + "1", 5500), 0x200 or destination, request + "1")
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
        val parameters = exchange("A00194FF82FF").singleOrNull()
            ?: error("Нет единственного A1 ответа" )
        check(parameters.size == 6 && parameters[0] == 0xA1 && parameters[1] in 1..15) { "Нет валидного согласования A0/A1" }
        responseMs = timingMs(parameters[2])
        gapMs = timingMs(parameters[4])
        check(responseMs in 1..2000 && gapMs in 0..1000) { "Неподдержанные тайминги TP2.0" }
        peerParameters = listOf(0xA1, 1, 0x94, 0xFF, 0x82, 0xFF)
        // The ELM prompt must arrive BEFORE the peer's ACK deadline. A long
        // T1-sized receive window would delay our ACK until the ECU gave up.
        val receiveTicks = ((gapMs + 24 + 3) / 4).coerceAtLeast(1)
        check(receiveTicks * 4 + gapMs <= 160) { "T3 не позволяет подтвердить кадр в объявленный T1" }
        ok("ATST%02X".format(receiveTicks))
        sendSeq = 0; receiveSeq = 0
    }

    /** Only recovered read/session/identification requests; no arbitrary payload entry point. */
    suspend fun request(command: String): List<Int> {
        require(command in setOf("1089", "1A9F", "2204A1", "1800FF00", "1802FF00", "19028D", "1A9B"))
        check(active)
        coroutineContext.ensureActive()
        val bytes = Wire.bytes(command)
        delay(gapMs)
        val sentSeq = sendSeq
        val wire = "%02X%04X".format(0x10 or sentSeq, bytes.size) + command
        val queue = ArrayDeque(exchange(wire))
        sendSeq = (sendSeq + 1) and 15
        val result = mutableListOf<Int>()
        var expected = -1
        var acknowledged = false
        var polls = 0
        var count = 0
        while (true) {
            coroutineContext.ensureActive()
            check(++count <= 256) { "Слишком много кадров TP2.0" }
            if (queue.isEmpty()) {
                // Keep the channel alive with A3. Bound this to one probe;
                // never retransmit an application request after uncertain delivery.
                check(polls++ == 0) { "Неполный ответ TP2.0 ($expected/${result.size})" }
                queue.addAll(exchange("A3"))
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
                frame[0] == 0xA8 -> error("ECU закрыл канал TP2.0")
                frame[0] == 0xA3 -> queue.addAll(exchange(Wire.hex(peerParameters), allowEmpty = true))
                frame[0] == 0xA1 -> check(frame.size == 6) { "Неверный keepalive A1" }
                op in listOf(0x00, 0x10, 0x20, 0x30) -> {
                    check(acknowledged) { "Данные до подтверждения запроса TP2.0" }
                    check(frame[0] and 15 == receiveSeq) { "Пропущен/перепутан TP2.0 sequence" }
                    receiveSeq = (receiveSeq + 1) and 15
                    val data = if (expected < 0) {
                        check(frame.size >= 3) { "Нет длины TP2.0" }
                        expected = (frame[1] shl 8) or frame[2]
                        check(expected in 1..4096) { "Неверная длина TP2.0" }
                        frame.drop(3)
                    } else frame.drop(1)
                    check(result.size + data.size <= expected) { "Лишние данные TP2.0" }
                    result += data
                    val last = op == 0x10 || op == 0x30
                    if (last) check(result.size == expected) { "Обрезанный TP2.0 пакет" }
                    if (op == 0x00 || op == 0x10) {
                        delay(gapMs)
                        // Odd 0 is ELM expected-response limit, not a CAN data byte.
                        if (last) quiet("%02X0".format(0xB0 or receiveSeq))
                        else queue.addAll(exchange("%02X".format(0xB0 or receiveSeq)))
                    }
                    if (last) {
                        check(queue.none { it.firstOrNull()?.and(0xF0) in listOf(0, 0x10, 0x20, 0x30) }) { "Неожиданный второй ответ TP2.0" }
                        return result
                    }
                }
                else -> error("Неожиданный кадр TP2.0: ${Wire.hex(frame)}")
            }
        }
    }
    suspend fun close() {
        if (!active) return
        active = false
        // Never reconnect to send a disconnect; caller disposes failed streams.
        ok("ATSH%03X".format(tx))
        quiet("A80")
    }
    private suspend fun quiet(command: String) {
        val raw = command(command, 5500).uppercase()
        check(listOf("?", "CAN ERROR", "BUS ERROR", "STOPPED", "BUFFER FULL", "UNABLE TO CONNECT").none { it in raw }) {
            "ELM не выполнил подтверждение/закрытие TP2.0: $raw"
        }
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
