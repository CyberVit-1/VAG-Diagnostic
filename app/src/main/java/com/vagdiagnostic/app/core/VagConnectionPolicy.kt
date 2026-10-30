package com.vagdiagnostic.app.core

/** Transport choice is per ECU; a Gateway's transport is not a vehicle-wide
 * protocol. Only a completed absence can suppress a route in this scan. */
object VagConnectionPolicy {
    enum class Explicit { AUTO, TP20, UDS }
    data class State(val hasTp: Boolean, val hasUds: Boolean, val udsOnlyChassis: Boolean = false,
        val explicit: Explicit = Explicit.AUTO, val connectedTp: Boolean = false,
        val connectedUds: Boolean = false, val tpNoResponse: Boolean = false,
        val udsNoResponse: Boolean = false)

    fun mustConnectToTp(s: State): Boolean = s.hasTp && !s.udsOnlyChassis &&
        s.explicit != Explicit.UDS && !s.tpNoResponse &&
        (s.explicit == Explicit.TP20 || s.connectedTp || !s.connectedUds)

    fun mustConnectToUds(s: State, precedingTpNoResponse: Boolean): Boolean =
        s.hasUds && s.explicit != Explicit.TP20 && !s.udsNoResponse &&
            (s.explicit == Explicit.UDS || !mustConnectToTp(s) || precedingTpNoResponse)

    private val udsOnlyPrefixes = setOf("1EA", "2Q0", "2Q1", "2Q5", "2QW", "4N0", "5Q0", "5Q1", "5WA", "6C0", "8W0", "8W1", "971")
    /** Only the confirmed central-electronics part number, never VIN or Gateway. */
    fun isUdsOnlyChassis(centralPart: String?): Boolean = centralPart?.take(3) in udsOnlyPrefixes
}
