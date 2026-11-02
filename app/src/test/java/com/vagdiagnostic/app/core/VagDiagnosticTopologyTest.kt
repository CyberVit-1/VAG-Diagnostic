package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagDiagnosticTopologyTest {
    private fun declared(id: String) = ControlUnit("VAG-$id", "Declared function", ReadState.NO_RESPONSE, source = "GATEWAY")
    private fun read(id: String) = ControlUnit("VAG-$id", "Read ECU", ReadState.COMPLETE,
        dtcs = listOf(Dtc("000001", "000001", DtcKind.OEM)), answeredModes = setOf(DtcKind.OEM), source = "RESPONSE")
    private fun identity(part: String, component: String) = VagEcuIdentification.Identity(part, null, "1A9B", component)
    @Test fun confirmedPq35BcmSuppliesAReferenceWithoutInventingChildDtc() {
        for (part in listOf("1K0937086H", "5K0 937 086 L")) {
            val child = declared("46")
            val result = VagDiagnosticTopology.host(child, mapOf("VAG-09" to read("09")), mapOf(9 to identity(part, "BCM PQ35 M")))
            assertEquals("VAG-09", result?.address)
            assertEquals(ReadState.NO_RESPONSE, child.state)
            assertTrue(child.dtcs.isEmpty())
            assertTrue(child.answeredModes.isEmpty())
        }
    }
    @Test fun differentBcmFamiliesNeverBecomeHostedComfortByAssumption() {
        for ((part, component) in listOf("3AA937087" to "BCM PQ35", "1K0937086H" to "BCM PQ25", "UNKNOWN" to "BCM PQ35"))
            assertNull(VagDiagnosticTopology.host(declared("46"), mapOf("VAG-09" to read("09")), mapOf(9 to identity(part, component))))
    }
    @Test fun noHostReferenceWithoutAFreshCompleteHostRead() {
        for (state in listOf(ReadState.NO_RESPONSE, ReadState.PARTIAL, ReadState.NOT_QUERIED))
            assertNull(VagDiagnosticTopology.host(declared("46"), mapOf("VAG-09" to read("09").copy(state = state)), mapOf(9 to identity("1K0937086H", "BCM PQ35 M"))))
    }
    @Test fun aRealIndependentResponseAlwaysWinsOverAHostRule() {
        for (state in listOf(ReadState.COMPLETE, ReadState.PARTIAL))
            assertNull(VagDiagnosticTopology.host(declared("46").copy(state = state, source = "RESPONSE"),
                mapOf("VAG-09" to read("09")), mapOf(9 to identity("1K0937086H", "BCM PQ35 M"))))
    }
    @Test fun candidatesAreNotPromotedToAFunctionOrReadEcu() {
        assertNull(VagDiagnosticTopology.host(declared("46").copy(source = "CANDIDATE"),
            mapOf("VAG-09" to read("09")), mapOf(9 to identity("1K0937086H", "BCM PQ35 M"))))
    }
    @Test fun mk70AngleReferenceNeedsBothIdentifiedReadControllers() {
        val ids = mapOf(3 to identity("1K0907379AK", "MK70 M MASR"), 0x44 to identity("1K0909144R", "EPS_ZFLS Kl. 235"))
        val units = mapOf("VAG-03" to read("03"), "VAG-44" to read("44"))
        val result = VagDiagnosticTopology.host(declared("04"), units, ids)
        assertEquals("VAG-44", result?.address)
        assertTrue(result?.reason.orEmpty().contains("не подтверждение"))
        assertNull(VagDiagnosticTopology.host(declared("04"), units - "VAG-03", ids))
        assertNull(VagDiagnosticTopology.host(declared("04"), units, ids - 0x44))
    }
    @Test fun mk60AndOtherSteeringControllersDoNotInheritTheMk70Rule() {
        val units = mapOf("VAG-03" to read("03"), "VAG-44" to read("44"))
        for (brake in listOf("MK60", "MK60EC1", "MK70EXTRA"))
            assertNull(VagDiagnosticTopology.host(declared("04"), units,
                mapOf(3 to identity("1K0907379AK", brake), 0x44 to identity("1K0909144R", "EPS_ZFLS Kl. 235"))))
    }
}
