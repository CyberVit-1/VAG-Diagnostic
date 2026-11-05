package com.vagdiagnostic.app.core

/** Admission to a fixed, read-only Gateway attempt is not proof of an OEM
 * response. Installation scope and every ECU result still need wire evidence. */
object VagOemScanPlan {
    data class Plan(val profile: VagTp20Gateway.Profile?) {
        val attemptGateway: Boolean get() = profile != null
    }

    fun create(isCan: Boolean?, vinFrames: FrameParse?, supportedVinEcus: Set<String>): Plan {
        // Engine CAN proof may come from actual headers even when ATDPN is
        // unsupported. The Gateway driver verifies its own selected CAN mode.
        if (isCan != true) return Plan(null)
        val identitySource = vinFrames?.let { ObdMode09Readiness.assessVin(it, supportedVinEcus).verified.firstOrNull() }
            .orEmpty()
        return Plan(VagTp20Gateway.discoveryProfile(vinFrames, identitySource, supportedVinEcus))
    }
}
