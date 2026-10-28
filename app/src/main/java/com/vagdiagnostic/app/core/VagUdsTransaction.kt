package com.vagdiagnostic.app.core

/** Separates pending SFs from the final, strictly addressed ISO-TP message.
 * Never removes a malformed frame, another ECU's frame or a different SID. */
object VagUdsTransaction {
    data class Reply(val payload: List<Int>?, val pendingCount: Int)
    fun decode(raw: String, expectedId: Int, request: String): Reply {
        val sid = request.take(2).toInt(16)
        val finalRows = mutableListOf<String>()
        var pending = 0
        for (row in raw.replace('\r', '\n').split('\n').filter { it.isNotBlank() }) {
            val one = VagAddressedCan.decodeSingleEcu(row, expectedId)
            val bytes = one.payload?.map { it.toInt() and 255 }
            if (one.valid && bytes == listOf(0x7F, sid, 0x78)) {
                require(finalRows.isEmpty()) { "UDS pending после начала финального ответа" }
                pending++
            }
            else finalRows += row
        }
        if (finalRows.isEmpty() && pending > 0) return Reply(null, pending)
        val decoded = VagAddressedCan.decodeSingleEcu(finalRows.joinToString("\n"), expectedId)
        require(decoded.valid) { "Неполный адресный UDS: ${decoded.problems.joinToString()}" }
        val bytes = requireNotNull(decoded.payload).map { it.toInt() and 255 }
        require(bytes.firstOrNull() == sid + 0x40 ||
            (bytes.size == 3 && bytes[0] == 0x7F && bytes[1] == sid)) { "UDS ответ другому запросу" }
        if (bytes[0] == sid + 0x40) {
            val echoLength = if (sid == 0x22) 2 else 1
            require(bytes.drop(1).take(echoLength) == VagOemReadOnly.bytes(request).drop(1).take(echoLength)) {
                "UDS ответ другой подфункции или DID"
            }
        }
        return Reply(bytes, pending)
    }
}
