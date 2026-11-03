package com.vagdiagnostic.app.core

/** Complete messages from an explicitly enabled adapter segmentation mode.
 * Never guess this format from a raw CAN reply: its PCI bytes are already
 * removed by the adapter, while the exact physical owner header remains. */
internal object VagUdsNativeTransaction {
    fun decode(raw: String, expectedId: Int, request: String): VagUdsTransaction.Reply {
        require(expectedId in 0..0x1FFFFFFF)
        val command = VagOemReadOnly.bytes(request)
        require(command.size >= 2)
        val headerLength = if (expectedId <= 0x7FF) 3 else 8
        var pending = 0
        var final: List<Int>? = null
        var messages = 0
        var totalBytes = 0
        for (row in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
            val clean = row.filterNot(Char::isWhitespace)
            if (clean.isEmpty() || clean == request) continue
            require(++messages <= 16 && clean.length >= headerLength + 2 &&
                clean.all { it in "0123456789ABCDEF" }) { "Неверный native UDS кадр" }
            require(clean.take(headerLength).toLong(16) == expectedId.toLong()) { "Native UDS от другого ECU" }
            val payload = VagOemReadOnly.bytes(clean.drop(headerLength))
            require(payload.size in 1..4095)
            totalBytes += payload.size
            require(totalBytes <= 16384)
            if (payload == listOf(0x7F, command[0], 0x78)) {
                require(final == null && ++pending <= 8) { "Неверный native UDS ResponsePending" }
                continue
            }
            require(payload.first() == command[0] + 0x40 ||
                (payload.size == 3 && payload.take(2) == listOf(0x7F, command[0]))) { "Native UDS другого сервиса" }
            if (payload.first() != 0x7F) {
                val length = if (command[0] == 0x22) 2 else 1
                require(payload.drop(1).take(length) == command.drop(1).take(length)) { "Native UDS другого DID или подфункции" }
            }
            require(final == null || final == payload) { "Противоречащие native UDS ответы" }
            final = payload
        }
        require(final != null || pending > 0) { "Нет native UDS ответа" }
        return VagUdsTransaction.Reply(final, pending)
    }
}
