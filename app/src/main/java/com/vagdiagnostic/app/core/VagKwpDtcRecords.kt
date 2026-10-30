package com.vagdiagnostic.app.core

/** Fixed KWP extended-record byte layouts. Raw field values are not physical
 * units or interpreted fault states. Unknown layouts remain opaque. */
object VagKwpDtcRecords {
    data class Field(val offset: Int, val mask: List<Int>, val value: Long)
    data class Record(val id: Int, val raw: List<Int>, val fields: List<Field>, val textBytes: List<Int>? = null)
    data class Parsed(val records: List<Record>, val opaqueTail: List<Int>) {
        val complete: Boolean get() = opaqueTail.isEmpty()
    }
    private data class Spec(val offset: Int, val mask: List<Int>)
    private val layouts = mapOf(
        0x01 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x02 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x03 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x05 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x06 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x07 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x0E to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x12 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x13 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x14 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x15 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x16 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x17 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x18 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x19 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x1A to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x21 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x24 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x25 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x28 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x2B to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x31 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x34 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x36 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x37 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x42 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x4B to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x51 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x53 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x58 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x5C to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x5D to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x5E to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x67 to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x6A to listOf(Spec(1, listOf(0xFF, 0xFF))),
        0x6C to listOf(Spec(3, listOf(0xFF)), Spec(4, listOf(0xFF)), Spec(5, listOf(0xFF)), Spec(6, listOf(0xFF, 0xFF, 0xFF)), Spec(10, listOf(0x01, 0xFC)), Spec(11, listOf(0x03, 0xC0)), Spec(12, listOf(0x3E)), Spec(12, listOf(0x01, 0xF0)), Spec(13, listOf(0x0F, 0xC0)), Spec(14, listOf(0x3F))),
        0x74 to listOf(Spec(1, listOf(0xFF, 0xFF)))
    )
    private val textIds = setOf(0x5F, 0x76)
    val knownIds: Set<Int> get() = layouts.keys + textIds

    /** Full52 response has one leading byte and three trailing bytes outside
     * its record stream. Their meanings are not inferred by this decoder. */
    fun decode(reply: List<Int>): Parsed {
        require(reply.firstOrNull() == 0x52 && reply.size >= 5 && reply.all { it in 0..255 })
        val data = reply.drop(2).dropLast(3)
        val records = mutableListOf<Record>()
        var position = 0
        var fieldCount = 0
        while (position < data.size) {
            require(data.size - position >= 3) { "Неполный заголовок KWP дополнительной записи" }
            val id = data[position]
            val layout = layouts[id]
            if (layout == null && id !in textIds) return Parsed(records, data.drop(position))
            val size = if (id in textIds) 2 + data[position + 1] else requireNotNull(layout).maxOf { it.offset + it.mask.size }
            require(size >= 3 && size <= data.size - position) { "Неполная KWP дополнительная запись" }
            val raw = data.subList(position, position + size).toList()
            val fields = layout.orEmpty().map { spec ->
                val mask = spec.mask.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
                require(mask != 0L)
                val bits = raw.drop(spec.offset).take(spec.mask.size).fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
                Field(spec.offset, spec.mask, (bits and mask) ushr java.lang.Long.numberOfTrailingZeros(mask))
            }
            fieldCount += fields.size.coerceAtLeast(1)
            require(fieldCount <= 113) { "Слишком много полей KWP дополнительных записей" }
            records += Record(id, raw, fields, if (id in textIds) raw.drop(2) else null)
            position += size
        }
        return Parsed(records, emptyList())
    }
}
