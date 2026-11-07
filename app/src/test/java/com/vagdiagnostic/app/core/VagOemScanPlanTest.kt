package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagOemScanPlanTest {
    @Test fun confirmedCanWithoutVinOnlyAdmitsFixedGatewayDiscovery() {
        val plan = VagOemScanPlan.create(true, null, emptySet())
        assertTrue(plan.attemptGateway)
        assertFalse(plan.profile!!.allowCandidateFallback)
        assertEquals("VAG", plan.profile.brand)
    }

    @Test fun missingCanProofCannotBeReplacedByEvenAValidVehicleIdentity() {
        val vin = "WVWAAA1KAAAAAAAAA" // Synthetic marker, never recorded vehicle data.
        val frames = FrameParse(mapOf("7E8" to listOf(byteArrayOf(0x49, 2, 1) + vin.toByteArray())), emptyList())
        for (can in listOf(false, null)) {
            val plan = VagOemScanPlan.create(can, frames, setOf("7E8"))
            assertFalse(plan.attemptGateway)
            assertNull(plan.profile)
        }
    }

    @Test fun validatedIdentityCanSelectFallbackWithoutPersistingItsVin() {
        val vin = "WVWAAA1KAAAAAAAAA" // Synthetic marker only.
        for (ecu in listOf("7E8", "18DAF110")) {
            val frames = FrameParse(mapOf(ecu to listOf(byteArrayOf(0x49, 2, 1) + vin.toByteArray())), emptyList())
            val plan = VagOemScanPlan.create(true, frames, setOf(ecu))
            assertTrue(plan.attemptGateway)
            assertTrue(plan.profile!!.allowCandidateFallback)
            assertEquals("1K", plan.profile.chassis)
            assertFalse(plan.toString().contains(vin))
        }
    }

    @Test fun unconfirmedOrForeignIdentityCannotEnableTheCandidateCatalog() {
        val short = FrameParse(mapOf("7E8" to listOf(byteArrayOf(0x49, 2, 1))), emptyList())
        val foreign = FrameParse(mapOf("7E9" to listOf(byteArrayOf(0x49, 2, 1) + "WVWAAA1KAAAAAAAAA".toByteArray())), emptyList())
        for (frames in listOf(short, foreign)) {
            val plan = VagOemScanPlan.create(true, frames, setOf("7E8"))
            assertTrue(plan.attemptGateway)
            assertFalse(plan.profile!!.allowCandidateFallback)
            assertEquals("VAG", plan.profile.brand)
        }
    }
}
