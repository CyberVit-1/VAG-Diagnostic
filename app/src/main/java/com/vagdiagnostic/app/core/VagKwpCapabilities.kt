package com.vagdiagnostic.app.core

/** Fixed VAG supported-function query used to choose the read-DTC format.
 * A failed capability query is not proof of SAE support. No arbitrary routine
 * request or ECU actuation API is exposed. */
object VagKwpCapabilities {
    const val REQUEST = "31B80000"
    fun supportsSaeDtc(reply: List<Int>): Boolean? {
        if (reply.size == 3 && reply.take(2) == listOf(0x7F, 0x31)) return null
        require(reply.take(2) == listOf(0x71, 0xB8)) { "Неверный ответ списка поддерживаемых функций" }
        val pairs = reply.drop(2)
        if (pairs.size < 2 || pairs.size % 2 != 0) return null
        return pairs.chunked(2).any { it == listOf(1, 0x18) }
    }
}
