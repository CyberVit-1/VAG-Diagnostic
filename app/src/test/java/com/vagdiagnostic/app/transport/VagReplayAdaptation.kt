package com.vagdiagnostic.app.transport

/** Explicit synthetic adapter setup around unchanged recorded ECU payloads.
 * This is software verification, never a recording of the new implementation. */
internal fun withExplicitPhysicalFlowControl(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    var protocol = "6"
    var uds = false
    var tx = ""
    var custom = false
    return buildList {
        for ((command, raw) in withoutLegacySilentProbes(rows)) {
            if (command.startsWith("ATSP")) protocol = command.removePrefix("ATSP")
            if (command == "ATCAF0") uds = false
            if (command == "ATCAF1") uds = true
            if (command == "ATFCSM0") custom = false
            if (command == "ATFCSM1") custom = true
            if (command.startsWith("ATSH")) {
                if (uds && protocol == "6" && custom) { add("ATFCSM0" to "OK"); custom = false }
                tx = command.removePrefix("ATSH")
            }
            if (command == "ATCFC1" && uds && protocol == "6") {
                add("ATFCSH$tx" to "OK")
                add("ATFCSD300010" to "OK")
                add("ATFCSM1" to "OK")
                custom = true
            }
            add((if (command == "ATFCSD300000") "ATFCSD300010" else command) to raw)
        }
    }
}

/** Omit only entire historical setup/probe blocks whose application replies
 * were all NO DATA. Resources remain unchanged; this is an explicit synthetic
 * adaptation to the fixed catalog, not physical evidence for the new routes. */
private fun withoutLegacySilentProbes(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    val removed = mutableSetOf<Int>()
    for (target in rows.indices.filter { rows[it].first in setOf("ATSH70D", "ATSH718") }) {
        val start = (target downTo 0).first { rows[it].first == "ATSP6" }
        val end = (target + 1 until rows.size).firstOrNull {
            rows[it].first in setOf("ATSP6", "ATSP7", "ATZ") ||
                (rows[it].first == "ATH1" && rows.getOrNull(it + 1)?.first == "ATCAF0")
        } ?: error("No end to historical silent UDS probe")
        val requests = rows.subList(start, end).filterNot { it.first.startsWith("AT") }
        check(requests.isNotEmpty() && requests.all { it.first in setOf("3E00", "1003", "22F187") && it.second.trim() == "NO DATA" }) {
            "Cannot omit a historical UDS probe with actual data or an unknown request"
        }
        removed += start until end
    }
    return rows.filterIndexed { index, _ -> index !in removed }
}
