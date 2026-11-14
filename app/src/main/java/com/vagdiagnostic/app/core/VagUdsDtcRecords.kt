package com.vagdiagnostic.app.core

/** Manufacturer record framing after5906/code24/status. Raw masked values
 * are deliberately separate from fault codes and physical-unit descriptions. */
object VagUdsDtcRecords {
    data class Field(val offset: Int, val width: Int, val mask: Long, val value: Long)
    data class Record(val id: Int, val raw: List<Int>, val fields: List<Field>)
    data class Parsed(val status: Int, val records: List<Record>, val opaqueTail: List<Int>) {
        val complete: Boolean get() = opaqueTail.isEmpty()
    }
    fun asamKey(name: String?, revision: String?): String? {
        val id = name?.trim()?.takeIf { Regex("[A-Za-z0-9_]{1,128}").matches(it) } ?: return null
        val rev = revision?.trim()?.takeIf { Regex("[A-Za-z0-9]{1,16}").matches(it) } ?: return null
        return id + "_" + rev.take(3)
    }
    /** Caller validates the echoed DTC against the actual requested code. */
    fun decode(reply: List<Int>, asamKey: String): Parsed {
        require(reply.size in 6..4095 && reply.take(2) == listOf(0x59, 0x06) && reply.all { it in 0..255 })
        require(asamKey.isNotBlank() && asamKey.length <= 145)
        val layout = VagUdsRecordLayouts.Selection(asamKey)
        val data = reply.drop(6)
        val records = mutableListOf<Record>()
        val seen = mutableSetOf<Int>()
        var position = 0
        while (position < data.size) {
            val id = data[position]
            require(seen.add(id)) { "Повтор номера UDS дополнительной записи" }
            require(records.size < 256) { "Слишком много UDS дополнительных записей" }
            val fields = layout.forRecord(id)
            if (fields.isEmpty()) return Parsed(reply[5], records, data.drop(position))
            val size = fields.maxOf { it.offset + it.width }
            require(size in 1..4094 && size <= data.size - position - 1) { "Неполная UDS дополнительная запись" }
            val body = data.subList(position + 1, position + 1 + size)
            val values = fields.map { spec ->
                val value = body.drop(spec.offset).take(spec.width).fold(0L) { n, byte -> (n shl 8) or byte.toLong() }
                Field(spec.offset, spec.width, spec.mask, (value and spec.mask) ushr java.lang.Long.numberOfTrailingZeros(spec.mask))
            }
            records += Record(id, listOf(id) + body, values)
            position += 1 + size
        }
        return Parsed(reply[5], records, emptyList())
    }
}
