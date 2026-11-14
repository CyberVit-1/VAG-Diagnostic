package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagGatewayDiscovery
import com.vagdiagnostic.app.core.VagInstallationBitmap

/** Used only after a completed unavailable primary list on an addressed UDS
 * Gateway. The caller owns framing, NRC classification, cancellation and IO. */
internal object ElmGatewayListFallback {
    suspend fun read(part: String?, request: suspend (String) -> List<Int>?,
        observed: suspend (String, VagGatewayDiscovery.Result?) -> Unit): VagGatewayDiscovery.Result {
        val logical = request("222A2A")?.let(VagGatewayDiscovery::logicalList)
        observed("222A2A", logical)
        if (logical != null && logical.entries.isNotEmpty()) return logical
        val results = mutableListOf<VagGatewayDiscovery.Result>()
        val indexed = request("22F1B7")?.let(VagGatewayDiscovery::addressIndexes)
        observed("22F1B7", indexed)
        if (indexed != null) results += indexed
        if (part != null && VagInstallationBitmap.formatFor(0x19, part) == "common") {
            for ((command, events) in listOf("2204A3" to false, "222A28" to true)) {
                val decoded = request(command)?.let { VagGatewayDiscovery.bitmap(part, it, events) }
                observed(command, decoded)
                if (decoded != null) results += decoded
            }
        }
        return VagGatewayDiscovery.merge(results)
    }
}
