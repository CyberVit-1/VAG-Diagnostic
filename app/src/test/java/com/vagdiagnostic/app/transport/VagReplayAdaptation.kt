package com.vagdiagnostic.app.transport

/** Explicit synthetic adapter setup around unchanged recorded ECU payloads.
 * This is software verification, never a recording of the new implementation. */
internal fun withExplicitPhysicalFlowControl(rows: List<Pair<String, String>>): List<Pair<String, String>> {
    var protocol = "6"
    var uds = false
    var tx = ""
    var custom = false
    return buildList {
        for ((command, raw) in rows) {
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
