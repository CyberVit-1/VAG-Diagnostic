package com.vagdiagnostic.app.core

/** Bounded, owner-separated raw ISO-TP collection for a single read request.
 * Caller supplies the fixed receive IDs and emits FC only for the corresponding
 * known transmit route. An unknown responder never creates an installed ECU. */
class VagIsoTpCollection(val expectedIds: Set<Int>, val request: String) {
    private data class Partial(val length: Int, val data: MutableList<Int>, var sequence: Int = 1,
        var flowSent: Boolean = false)
    private val partial = linkedMapOf<Int, Partial>()
    private val complete = linkedMapOf<Int, List<Int>>()
    private val pendingCounts = mutableMapOf<Int, Int>()
    private val unknown = linkedSetOf<Int>()
    private val invalid = linkedSetOf<Int>()
    private var frames = 0
    private var totalBytes = 0
    private val requestBytes = VagOemReadOnly.bytes(request)
    init {
        require(expectedIds.isNotEmpty() && expectedIds.size <= 32 && expectedIds.all { it in 0..0x1FFFFFFF })
        require(requestBytes.size >= 2)
    }
    val payloads: Map<Int, List<Int>> get() = complete.toMap()
    val unknownIds: Set<Int> get() = unknown.toSet()
    val invalidIds: Set<Int> get() = invalid.toSet()
    val flowControlNeeded: Set<Int> get() = partial.filterValues { !it.flowSent }.keys.toSet()
    val unfinished: Set<Int> get() = partial.keys.toSet()
    val pending: Set<Int> get() = pendingCounts.keys.filter { it !in complete && it !in partial }.toSet()
    fun flowControlSent(id: Int) {
        val state = requireNotNull(partial[id]) { "Нет FirstFrame для flow control" }
        check(!state.flowSent) { "Повторный flow control" }
        state.flowSent = true
    }
    private fun finish(id: Int, data: List<Int>) {
        require(data.firstOrNull() == requestBytes[0] + 0x40 ||
            (data.size == 3 && data.take(2) == listOf(0x7F, requestBytes[0]))) { "Ответ другому сервису" }
        if (data == listOf(0x7F, requestBytes[0], 0x78)) {
            require(id !in complete && id !in partial) { "ResponsePending после начала финального ответа" }
            val count = pendingCounts.getOrDefault(id, 0) + 1
            require(count <= 8) { "Превышен предел ResponsePending" }
            pendingCounts[id] = count
            return
        }
        if (data.first() != 0x7F) {
            val echo = requestBytes.drop(1).take(if (requestBytes[0] == 0x22) 2 else 1)
            require(data.drop(1).take(echo.size) == echo) { "Ответ другому DID или подфункции" }
        }
        val old = complete[id]
        require(old == null || old == data) { "Противоречащие финальные ответы ECU" }
        complete[id] = data.toList()
    }
    fun accept(raw: String, echo: String = request) {
        for (line in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
            val clean = line.filterNot(Char::isWhitespace)
            if (clean.isEmpty() || clean == echo || clean == "OK" || clean == "NODATA") continue
            require(clean.all { it in "0123456789ABCDEF" }) { "Неверная строка RAW CAN" }
            val headerSize = if (clean.length % 2 == 1) 3 else 8
            require(clean.length >= headerSize + 2) { "Неполный CAN кадр" }
            val id = clean.take(headerSize).toLong(16)
            require(id in 0..0x1FFFFFFF && (headerSize != 3 || id <= 0x7FF)) { "Неверный CAN ID" }
            val frame = VagOemReadOnly.bytes(clean.drop(headerSize))
            require(frame.size in 1..8) { "Неверная длина CAN кадра" }
            require(++frames <= 256) { "Превышен предел CAN кадров" }
            val owner = id.toInt()
            if (owner !in expectedIds) { unknown += owner; continue }
            require(owner !in invalid) { "Ответ уже признан противоречивым" }
            try { when (frame[0] ushr 4) {
                0 -> {
                    val length = frame[0] and 15
                    require(length in 1..7 && frame.size >= length + 1 && owner !in partial) { "Неполный или неожиданный SingleFrame" }
                    finish(owner, frame.drop(1).take(length))
                }
                1 -> {
                    require(frame.size == 8 && owner !in partial && owner !in complete) { "Неполный или повторный FirstFrame" }
                    val length = ((frame[0] and 15) shl 8) or frame[1]
                    require(length in 8..4095) { "Неверная длина ISO-TP" }
                    totalBytes += length
                    require(totalBytes <= 16384) { "Превышен суммарный размер ISO-TP" }
                    partial[owner] = Partial(length, frame.drop(2).toMutableList())
                }
                2 -> {
                    val state = requireNotNull(partial[owner]) { "ConsecutiveFrame без FirstFrame" }
                    require(state.flowSent && frame.size >= 2 && (frame[0] and 15) == state.sequence) { "Неверный sequence или CF до flow control" }
                    require(state.length - state.data.size <= 7 || frame.size == 8) { "Короткий промежуточный ConsecutiveFrame" }
                    state.sequence = (state.sequence + 1) and 15
                    state.data += frame.drop(1).take(state.length - state.data.size)
                    if (state.data.size == state.length) {
                        partial.remove(owner)
                        finish(owner, state.data)
                    }
                }
                else -> error("Неподдержанный ISO-TP кадр")
            } } catch (failure: RuntimeException) {
                complete.remove(owner)
                partial.remove(owner)
                pendingCounts.remove(owner)
                invalid += owner
                throw failure
            }
        }
    }
}
