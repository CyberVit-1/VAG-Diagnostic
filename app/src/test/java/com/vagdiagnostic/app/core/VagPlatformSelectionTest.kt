package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagPlatformSelectionTest {
    private fun frames(wmi: String, chassis: String, suffix: String = "AAAAAAAAA"): FrameParse {
        val vin = wmi + "AAA" + chassis + suffix
        return FrameParse(mapOf("7E8" to listOf(byteArrayOf(0x49, 2, 1) + vin.toByteArray())), emptyList())
    }
    @Test fun documentedBrandAndChassisPairsSelectClosedProfilesWithoutKeepingVin() {
        for ((wmi, chassis) in listOf("WVW" to "1K", "3VW" to "1K", "WAU" to "8P", "TMB" to "1Z", "VSS" to "1P")) {
            val selected = VagTp20Gateway.chassisFromVerifiedMode09(frames(wmi, chassis), "7E8")
            assertEquals(chassis, selected)
            val profile = requireNotNull(VagTp20Gateway.knownCandidate(selected))
            assertEquals(chassis == "1K", profile.allowCandidateFallback)
            assertFalse(profile.toString().contains(wmi + "AAA"))
        }
    }
    @Test fun unrelatedWmiWrongBrandUnknownModernChassisAndInvalidVinStayBlocked() {
        for ((wmi, chassis) in listOf("XXX" to "1K", "WAU" to "1K", "WVW" to "8P", "TMB" to "1P",
            "VSS" to "1Z", "WAU" to "8V", "WVW" to "5G"))
            assertNull(VagTp20Gateway.chassisFromVerifiedMode09(frames(wmi, chassis), "7E8"))
        for (bad in listOf("AAAAIAAAA", "AAAAOAAAA", "AAAAQAAAA", "AAAAAAAA"))
            assertNull(VagTp20Gateway.chassisFromVerifiedMode09(frames("WVW", "1K", bad), "7E8"))
        val duplicate = frames("TMB", "1Z").messages.getValue("7E8").single()
        assertNull(VagTp20Gateway.chassisFromVerifiedMode09(FrameParse(mapOf("7E8" to listOf(duplicate, duplicate)), emptyList()), "7E8"))
    }
}
