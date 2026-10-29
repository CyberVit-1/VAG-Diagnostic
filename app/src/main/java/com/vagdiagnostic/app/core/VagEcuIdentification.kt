package com.vagdiagnostic.app.core

/** A small, validated subset of identification, never VIN/serial/coding data. */
object VagEcuIdentification {
    data class Identity(val partNumber: String, val softwareVersion: String?, val request: String) {
        fun summary(): String = "Gateway: $partNumber" + (softwareVersion?.let { "; ПО $it" } ?: "")
    }
    private fun text(bytes: List<Int>): String? {
        val value = bytes.dropLastWhile { it == 0 || it == 0x20 }
        if (value.isEmpty() || value.any { it !in 0x20..0x7E }) return null
        return value.map { it.toChar() }.joinToString("").trim().takeIf { it.isNotEmpty() }
    }
    private fun part(bytes: List<Int>): String? = text(bytes)?.takeIf {
        it.length in 5..16 && it.any(Char::isDigit) && it.all { c -> c in 'A'..'Z' || c.isDigit() || c == ' ' || c == '-' }
    }
    /** Main-module 1A9B body: 12-byte part, 4-byte SW, then fixed fields.
     * Do not parse coding/workshop/serial/submodule data or guess short layouts. */
    fun kwp(reply: List<Int>): Identity? {
        if (reply.size == 3 && reply.take(2) == listOf(0x7F, 0x1A)) return null
        require(reply.take(2) == listOf(0x5A, 0x9B)) { "Нет echo идентификации 1A9B" }
        val body = reply.drop(2)
        if (body.size < 26) return null
        val number = part(body.take(12)) ?: return null
        val software = text(body.drop(12).take(4))
        return Identity(number, software, "1A9B")
    }
    fun udsPartNumber(reply: List<Int>): Identity? {
        if (reply.size == 3 && reply.take(2) == listOf(0x7F, 0x22)) return null
        require(reply.take(3) == listOf(0x62, 0xF1, 0x87)) { "Нет echo идентификации F187" }
        val body = reply.drop(3)
        if (body.size !in 5..16) return null
        return part(body)?.let { Identity(it, null, "22F187") }
    }
}
