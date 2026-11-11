package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagDiagnosticAccess
import com.vagdiagnostic.app.core.VagUdsInfo

/** Shared read-only boundary for all CAN framing backends. */
internal object ElmUdsReadRequests {
    /** Only requests whose researched command options permit adaptive timing.
     * DTC/list/extended-record reads retain their fixed receive window. */
    fun allowsAdaptiveTiming(request: String): Boolean = request in setOf(
        "1003", "1001", "22F187", "22F197", "22F189", "22F19E", "22F1A2", "220174")
    fun allowed(request: String): Boolean = request in setOf(
        "3E00", "1003", "1001", "2204A1", "222A26", "222A2A", "2204A3", "222A28", "22F1B7",
        "19028D", "22F187", VagDiagnosticAccess.REQUEST) ||
        request in VagUdsInfo.Field.entries.map { it.request } || Regex("1906[0-9A-F]{6}FF").matches(request)
}
