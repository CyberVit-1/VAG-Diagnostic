package com.vagdiagnostic.app.transport

/** Explicit synthetic adapter setup around unchanged recorded ECU payloads.
 * This is software verification, never a recording of the new implementation. */
internal fun withExplicitPhysicalFlowControl(rows: List<Pair<String, String>>, minimalListed: Boolean = false): List<Pair<String, String>> {
    var protocol = "6"
    var uds = false
    var tx = ""
    var custom = false
    var silentPresence = false
    var silentSession = false
    return withExplicitUdsTiming(withSyntheticTpKeepalives(buildList {
        // AF requests below are synthetic extensions of historical 8D traces.
        // Recorded ECU payloads are retained, not evidence of a physical AF read.
        val statusCoverageRows = rows.map { (command, raw) ->
            (if (command == "19028D") "1902AF" else command) to
                (if (command == "19028D") raw.lineSequence().joinToString("\n") {
                    if (it.trim() == command) "1902AF" else it
                } else raw)
        }
        for ((command, raw) in withExplicit29BitHeaderOrder(withoutLegacySilentProbes(statusCoverageRows))) {
            if (command.startsWith("ATSP")) protocol = command.removePrefix("ATSP")
            if (command == "ATCAF0") uds = false
            if (command == "ATCAF1") uds = true
            // UDS starts fixed; per-request timing is added explicitly below.
            // The original TP ACK windows and ECU payloads remain unchanged.
            if (command == "ATAT0" && uds) continue
            if (command == "ATFCSM0") custom = false
            if (command == "ATFCSM1") custom = true
            if (command.startsWith("ATSH")) {
                silentPresence = false
                silentSession = false
                if (uds && protocol == "6" && custom) { add("ATFCSM0" to "OK"); custom = false }
                tx = command.removePrefix("ATSH")
            }
            if (command == "ATCFC1" && uds && protocol == "6") {
                add("ATFCSH$tx" to "OK")
                add("ATFCSD300010" to "OK")
                add("ATFCSM1" to "OK")
                custom = true
            }
            // These old fixture probes are explicitly synthetic all-NO-DATA
            // extensions. The minimal listed path now goes straight to DTC.
            // Never omit a real positive, negative, pending or partial reply.
            if (minimalListed && uds && command == "22F187" && raw.trim() == "NO DATA" && silentPresence && silentSession) {
                repeat(2) { add("1902AF" to "NO DATA") }
                continue
            }
            // Explicitly synthetic ACK for the researched 1000ms UDS window;
            // retain the original recording and every ECU payload unchanged.
            val probeWire = if (uds && command in setOf("3E00", "1003")) command + "1" else command
            val probeRaw = if (probeWire != command) raw.replace('\r', '\n').split('\n')
                .joinToString("\r") { if (it.filterNot(Char::isWhitespace) == command) probeWire else it } else raw
            if (command == "ATSTFF" && uds) {
                check(raw.replace(command, "").replace(">", "").trim() == "OK")
                add("ATSTFA" to "OK")
            } else add((if (command == "ATFCSD300000") "ATFCSD300010" else probeWire) to probeRaw)
            if (command == "ATSTFF" && uds) add("ATAT0" to "OK")
            // Historic resources contain one completed silent CHANNEL SETUP.
            // Additional attempts are explicitly synthetic silence, not a new
            // physical recording or evidence that the ECU remains absent.
            if (!uds && command.matches(Regex("[0-9A-F]{2}C000100003011")) && raw.trim() == "NO DATA")
                repeat(3) { add(command to "NO DATA") }
            // Three completed silent physical presence attempts are synthetic
            // extensions; no extra request is inserted after pending/partial data.
            if (!minimalListed && uds && command == "3E00" && raw.trim() == "NO DATA")
                repeat(2) { add(probeWire to "NO DATA") }
            if (uds && command == "3E00") silentPresence = raw.trim() == "NO DATA"
            if (uds && command == "1003") silentSession = raw.trim() == "NO DATA"
            // Completed command-level silence gets one additional synthetic
            // attempt. Never extend a recorded pending or partial payload.
            if (uds && command != "1003" && VagReadRetry.enabled(command) && raw.trim() == "NO DATA")
                add(command to "NO DATA")
            // These confirmed-list recordings stopped before a DTC request
            // when all preflight services were silent. Extend only that case
            // with explicitly SYNTHETIC silence; never invent a DTC or edit
            // any recorded ECU payload. The old physical result stays unknown.
            if (uds && command == "22F187" && raw.trim() == "NO DATA" && silentPresence && silentSession)
                repeat(2) { add("1902AF" to "NO DATA") }
        }
    }))
}

/** Synthetic AT confirmations only. The eight adaptive command options are
 * independently listed from the audited read constructors; no ECU response,
 * DTC record, pending frame or timing observation is synthesized here. */
private fun withExplicitUdsTiming(rows: List<Pair<String, String>>): List<Pair<String, String>> = buildList {
    var timing: String? = null
    val adaptive = setOf("1003", "1001", "22F187", "22F197", "22F189", "22F19E", "22F1A2", "220174")
    val fixed = setOf("3E00", "1902AF", "2204A1", "222A26", "222A28")
    for ((command, raw) in rows) {
        val logical = if (command in setOf("3E001", "10031")) command.dropLast(1)
        else if (command.matches(Regex("0[1-7][0-9A-F]{14}"))) {
            val count = command.take(2).toInt(16)
            command.substring(2, 2 + count * 2)
        } else command
        if (logical in adaptive || logical in fixed || logical.matches(Regex("1906[0-9A-F]{6}FF"))) {
            val required = if (logical in adaptive && command != "10031") "ATAT1" else "ATAT0"
            if (timing != required) { add(required to "OK"); timing = required }
        }
        add(command to raw)
        if (command.startsWith("ATSP") || command in setOf("ATZ", "ATD", "ATWS")) timing = null
        if (command in setOf("ATAT0", "ATAT1")) timing = command.takeIf { raw.trim() == "OK" }
    }
}

/** Recorded responses are unchanged. Added idle A3/A1 exchanges explicitly
 * model the verified control flow; they are not new vehicle observations. */
internal fun withSyntheticTpKeepalives(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    var rawTp = true
    var first = true
    var peer = ""
    return withSyntheticTpReceiveWindows(buildList {
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
    })
}

/** Historical polls are consecutive A3s or terminate with data/B ACK or a peer
 * close after another poll. Preserve every raw ECU reply, but model settings/count hints
 * explicitly as software-only adaptation. The final A3 before a retried request
 * is an idle check, not a receive poll, and remains unchanged. */
internal fun withSyntheticTpReceiveWindows(rows: List<Pair<String, String>>): List<Pair<String, String>> = buildList {
    var shortWindow = ""
    for ((index, row) in rows.withIndex()) {
        val (command, reply) = row
        if (command.startsWith("ATST")) shortWindow = command
        val next = rows.getOrNull(index + 1)?.first.orEmpty()
        val receivedData = reply.replace('\r', '\n').split('\n').any {
            Regex("300[0-3][0-9A-F][0-9A-F]{2,14}").matches(it.trim())
        }
        val closedAfterPoll = rows.getOrNull(index - 1)?.first == "A3" &&
            reply.replace('\r', '\n').split('\n').any { it.trim() == "300A8" }
        if (command == "A3" && (next == "A3" || (receivedData && next.startsWith("B")) || closedAfterPoll)) {
            check(shortWindow.isNotEmpty())
            check(reply.trim() == "NO DATA" || reply.replace('\r', '\n').split('\n').filter { it.isNotBlank() }.all {
                Regex("300(?:A1[0-9A-F]{10}|A8|[0-3][0-9A-F][0-9A-F]{2,14})").matches(it.trim())
            })
            add("ATST80" to "OK")
            add("A32" to reply)
            add(shortWindow to "OK")
        } else add(row)
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
