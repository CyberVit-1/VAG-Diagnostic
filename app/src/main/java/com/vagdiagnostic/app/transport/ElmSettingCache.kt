package com.vagdiagnostic.app.transport

/** State of settings acknowledged by this exact adapter connection. Never
 * cache vehicle requests or infer support from an adapter version string. */
internal class ElmSettingCache {
    private val values = mutableMapOf<String, String>()
    fun clear() = values.clear()
    fun confirmed(command: String): Boolean = key(command)?.let { values[it] == command } == true
    fun setting(command: String): Boolean = key(command) != null
    fun beforeCommand(command: String) {
        val key = key(command)
        val compact = command.filterNot(Char::isWhitespace)
        if (compact.matches(Regex("STCSEGR[01]|STCSEGT[01]|VTSWGP(?:GR1,BZF1|GR0|GT[01])"))) return
        if (compact == "STCTOR1000,1000") return // Separate native FC/CF timer; not the response timeout.
        if (command == "STCFCPC" || command.startsWith("STCFCPA") || command.startsWith("VTFCTRA ")) {
            values.remove("ATFCSH"); values.remove("ATFCSD"); values.remove("ATFCSM")
        } else if (compact.matches(Regex("STPTO[0-9]+|VTTOSTREP:[0-9]+"))) {
            values.remove("ATST")
        } else if (key == "ATSP") {
            values.keys.removeAll { it !in setOf("ATE", "ATL", "ATS", "ATH", "ATD") }
        } else if (key == null && command !in queries && !command.all { it in "0123456789ABCDEF" }) clear()
    }
    fun observe(command: String, raw: String) {
        val key = key(command) ?: return
        val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
            .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != command }
        if (rows != listOf("OK")) { values.remove(key); return }
        if (key == "ATCP" && values[key] != command) values.remove("ATSH")
        if (key == "ATSH" && command.length == 7) values.remove("ATCP")
        if (key == "ATFCSM" && values[key] != command) {
            values.remove("ATFCSH"); values.remove("ATFCSD")
        }
        values[key] = command
    }
    private fun key(command: String): String? = formats.entries.firstOrNull {
        command.startsWith(it.key) && it.value.matches(command.removePrefix(it.key))
    }?.key
    companion object {
        private val queries = setOf("ATI", "ATDPN", "ATDP", "ATRV", "STI", "STDI", "STIX", "STPR", "STPRS", "VTI")
        private val formats = linkedMapOf(
            "ATFCSH" to Regex("(?:[0-9A-F]{3}|[0-9A-F]{8})"),
            "ATFCSD" to Regex("(?:[0-9A-F]{2}){1,5}"), "ATFCSM" to Regex("[012]"),
            "ATCAF" to Regex("[01]"), "ATCFC" to Regex("[01]"),
            "ATCRA" to Regex("(?:[0-9A-F]{3}|[0-9A-F]{8})"), "ATCEA" to Regex("(?:[0-9A-F]{2})?"),
            "ATCP" to Regex("[0-9A-F]{2}"), "ATSH" to Regex("(?:[0-9A-F]{3}|[0-9A-F]{6})"),
            "ATSP" to Regex("[0-9A-C]"), "ATST" to Regex("[0-9A-F]{2}"), "ATAT" to Regex("[012]"),
            "ATV" to Regex("[01]"), "ATH" to Regex("[01]"), "ATD" to Regex("[01]"),
            "ATE" to Regex("[01]"), "ATL" to Regex("[01]"), "ATS" to Regex("[01]"))
    }
}
