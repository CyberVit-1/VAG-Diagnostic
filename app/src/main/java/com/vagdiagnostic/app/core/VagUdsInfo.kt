package com.vagdiagnostic.app.core

/** Fixed read-only identification requests. No VIN, serial, coding or unlock
 * requests are part of this reader. Each optional field retains its own result. */
object VagUdsInfo {
    enum class Field(val did: Int, val maxBytes: Int) {
        COMPONENT(0xF197, 128), SOFTWARE(0xF189, 32), ASAM(0xF19E, 128),
        ASAM_REVISION(0xF1A2, 16), ACCESS_STATUS(0x0174, 4);
        val request: String get() = "22%04X".format(did)
    }
    enum class State { PRESENT, NO_DATA, REJECTED, INVALID }
    data class Value(val state: State, val text: String? = null, val raw: List<Int>? = null, val nrc: Int? = null)
    val scanFields = listOf(Field.COMPONENT, Field.SOFTWARE, Field.ASAM)

    fun decode(field: Field, reply: List<Int>?): Value {
        if (reply == null) return Value(State.NO_DATA)
        require(reply.all { it in 0..255 })
        if (reply.firstOrNull() == 0x7F) {
            require(reply.size == 3 && reply[1] == 0x22 && reply[2] != 0x78) { "Незавершённый или чужой ответ идентификации" }
            return Value(State.REJECTED, nrc = reply[2])
        }
        require(reply.take(3) == listOf(0x62, field.did ushr 8, field.did and 255)) { "Ответ другому DID идентификации" }
        val body = reply.drop(3)
        if (field == Field.ACCESS_STATUS)
            return if (body.size == 4) Value(State.PRESENT, raw = body.toList()) else Value(State.INVALID)
        if (body.isEmpty() || body.size > field.maxBytes) return Value(State.INVALID)
        val data = body.dropLastWhile { it == 0 || it == 0x20 }
        val whitespace = setOf(9, 10, 13)
        if (data.isEmpty() || data.any { it !in 0x20..0x7E && it !in 0xA0..0xFF && it !in whitespace }) return Value(State.INVALID)
        val text = data.map { it.toChar() }.joinToString("").replace(Regex("\\s+"), " ").trim()
        return if (text.isEmpty()) Value(State.INVALID) else Value(State.PRESENT, text = text)
    }

    suspend fun collect(fields: List<Field>, onValue: suspend (Field, Value) -> Unit = { _, _ -> },
        read: suspend (String) -> List<Int>?): Map<Field, Value> {
        require(fields.distinct().size == fields.size)
        val result = linkedMapOf<Field, Value>()
        for (field in fields) {
            val value = decode(field, read(field.request))
            result[field] = value
            onValue(field, value)
        }
        return result
    }

    fun merge(identity: VagEcuIdentification.Identity, details: Map<Field, Value>): VagEcuIdentification.Identity =
        identity.copy(component = details[Field.COMPONENT]?.text ?: identity.component,
            softwareVersion = details[Field.SOFTWARE]?.text ?: identity.softwareVersion,
            asam = details[Field.ASAM]?.text ?: identity.asam,
            asamRevision = details[Field.ASAM_REVISION]?.text ?: identity.asamRevision)
}
