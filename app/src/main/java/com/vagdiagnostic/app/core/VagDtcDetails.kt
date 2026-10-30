package com.vagdiagnostic.app.core

/** Additional records belong only to a DTC already read from this ECU.
 * Manufacturer record layouts remain opaque until a matching decoder exists. */
object VagDtcDetails {
    enum class Protocol { KWP, UDS }
    enum class State { RAW_RESPONSE, NO_DATA, REJECTED }
    data class Reply(val state: State, val raw: List<Int> = emptyList(), val nrc: Int? = null)
    class Request internal constructor(val protocol: Protocol, val codeBytes: List<Int>) {
        init {
            require(codeBytes.size == if (protocol == Protocol.KWP) 2 else 3)
            require(codeBytes.all { it in 0..255 })
        }
        val command: String get() = when (protocol) {
            Protocol.KWP -> "120004" + VagOemReadOnly.hex(codeBytes)
            Protocol.UDS -> "1906" + VagOemReadOnly.hex(codeBytes) + "FF"
        }
    }
    fun request(protocol: Protocol, fault: Dtc): Request {
        require(fault.kind == DtcKind.OEM) { "Дополнительные OEM-записи требуют фактического OEM DTC" }
        require(Regex("[0-9A-F]{6}").matches(fault.raw)) { "Нет полного исходного OEM DTC" }
        val bytes = VagOemReadOnly.bytes(fault.raw)
        return Request(protocol, if (protocol == Protocol.KWP) bytes.take(2) else bytes)
    }
    fun decode(request: Request, payload: List<Int>?): Reply {
        if (payload == null) return Reply(State.NO_DATA)
        require(payload.all { it in 0..255 })
        val sid = if (request.protocol == Protocol.KWP) 0x12 else 0x19
        if (payload.firstOrNull() == 0x7F) {
            require(payload.size == 3 && payload[1] == sid && payload[2] != 0x78) { "Незавершённый или чужой ответ дополнительных записей" }
            return Reply(State.REJECTED, nrc = payload[2])
        }
        require(payload.firstOrNull() == sid + 0x40) { "Дополнительные записи другого сервиса" }
        if (request.protocol == Protocol.UDS) {
            require(payload.take(5) == listOf(0x59, 0x06) + request.codeBytes && payload.size >= 6) {
                "Дополнительные записи другого DTC или неполный заголовок"
            }
        } else require(payload.size >= 5) { "Неполный заголовок KWP дополнительных записей" }
        // Transport and request envelope are checked. Do not guess individual
        // record lengths, units or meanings from an unknown ECU/ASAM layout.
        return Reply(State.RAW_RESPONSE, raw = payload.toList())
    }
}
