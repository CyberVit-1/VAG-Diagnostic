package com.vagdiagnostic.app.core

/** ISO-TP framing only; the diagnostic caller must authorize its read command.
 * The generic CAN driver uses eight-byte frames padded with AA. */
internal object VagIsoTpOutgoing {
    fun frames(payload: List<Int>): List<List<Int>> {
        require(payload.size in 1..4095 && payload.all { it in 0..255 })
        fun padded(bytes: List<Int>) = bytes + List(8 - bytes.size) { 0xAA }
        if (payload.size <= 7) return listOf(padded(listOf(payload.size) + payload))
        val result = mutableListOf(padded(listOf(0x10 or (payload.size ushr 8), payload.size and 255) + payload.take(6)))
        var sequence = 1
        for (part in payload.drop(6).chunked(7)) {
            result += padded(listOf(0x20 or sequence) + part)
            sequence = (sequence + 1) and 15
        }
        return result
    }
}
