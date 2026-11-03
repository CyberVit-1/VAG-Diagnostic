package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagDiagnosticAccess
import com.vagdiagnostic.app.core.VagUdsInfo

/** Shared read-only boundary for all CAN framing backends. */
internal object ElmUdsReadRequests {
    fun allowed(request: String): Boolean = request in setOf(
        "3E00", "1003", "1001", "2204A1", "222A26", "19028D", "22F187", VagDiagnosticAccess.REQUEST) ||
        request in VagUdsInfo.Field.entries.map { it.request } || Regex("1906[0-9A-F]{6}FF").matches(request)
}
