package com.vagdiagnostic.app.transport

/** Explicit synthetic adapter setup around unchanged recorded ECU payloads.
 * This is software verification, never a recording of the new implementation. */
internal fun withExplicitPhysicalFlowControl(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    var protocol = "6"
    var uds = false
    var tx = ""
    var custom = false
    return withSyntheticTpKeepalives(buildList {
        for ((command, raw) in withExplicit29BitHeaderOrder(withoutLegacySilentProbes(rows))) {
            if (command.startsWith("ATSP")) protocol = command.removePrefix("ATSP")
            if (command == "ATCAF0") uds = false
            if (command == "ATCAF1") uds = true
            // Physical UDS now uses adaptive timing. The recorded raw TP ACK
            // windows remain fixed; only adapter setup is synthetic here.
            if (command == "ATAT0" && uds) continue
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
            if (command == "ATSTFF" && uds) add("ATAT1" to "OK")
            // Historic resources contain one completed silent CHANNEL SETUP.
            // Additional attempts are explicitly synthetic silence, not a new
            // physical recording or evidence that the ECU remains absent.
            if (!uds && command.matches(Regex("[0-9A-F]{2}C000100003011")) && raw.trim() == "NO DATA")
                repeat(3) { add(command to "NO DATA") }
            // Three completed silent physical presence attempts are synthetic
            // extensions; no extra request is inserted after pending/partial data.
            if (uds && command == "3E00" && raw.trim() == "NO DATA")
                repeat(2) { add(command to "NO DATA") }
            // Completed command-level silence gets one additional synthetic
            // attempt. Never extend a recorded pending or partial payload.
            if (uds && VagReadRetry.enabled(command) && raw.trim() == "NO DATA")
                add(command to "NO DATA")
        }
    })
}

/** Recorded responses are unchanged. Added idle A3/A1 exchanges explicitly
 * model the verified control flow; they are not new vehicle observations. */
internal fun withSyntheticTpKeepalives(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    var rawTp = true
    var first = true
    var peer = ""
    return buildList {
        for ((command, reply) in rows) {
            if (command == "ATCAF0") rawTp = true
            if (command == "ATCAF1") rawTp = false
            if (command == "A00194FF82FF") {
                peer = Regex("300(A1[0-9A-F]{10})").find(reply.filterNot { it.isWhitespace() })?.groupValues?.get(1).orEmpty()
                first = true
            }
            if (rawTp && command.length % 2 == 0 &&
                command.matches(Regex("1[0-9A-F][0-9A-F]{4}(10|1A|18|19|22|31|12)[0-9A-F]*"))) {
                if (!first) {
                    check(peer.isNotEmpty()) { "No recorded A1 for synthetic idle check" }
                    add("A3" to "300$peer")
                }
                first = false
            }
            add(command to reply)
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

/** Synthetic configuration order only; recorded command/reply rows and ECU
 * payloads are not edited. Swap only adjacent successful CP/SH confirmations. */
internal fun withExplicit29BitHeaderOrder(rows: List<Pair<String, String>>): List<Pair<String, String>> = buildList {
    var i = 0
    while (i < rows.size) {
        val first = rows[i]
        val second = rows.getOrNull(i + 1)
        if (first.first.matches(Regex("ATCP[0-9A-F]{2}")) &&
            second?.first?.matches(Regex("ATSH[0-9A-F]{6}")) == true &&
            first.second.trim() == "OK" && second.second.trim() == "OK") {
            add(second); add(first); i += 2
        } else { add(first); i++ }
    }
}
