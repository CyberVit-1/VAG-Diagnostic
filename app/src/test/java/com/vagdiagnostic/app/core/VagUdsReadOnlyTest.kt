package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic UDS response fixtures. No vehicle-specific Gateway route or DID
 * is claimed, and no request is transmitted to real equipment.
 */
class VagUdsReadOnlyTest {
    @Test fun reassemblesIsoTpAndKeepsRaw24BitDtcWithStatusAvailabilityMask() {
        val frames = ElmFrames.parse(
            "1902FF",
            "7E8 10 0B 59 02 FF 12 34 56\r7E8 21 7F 89 AB CD 01 00 00\r>"
        )
        assertTrue(frames.problems.toString(), frames.problems.isEmpty())
        val result = VagUdsReadOnly.assessDtcByStatusMask(frames, setOf("7E8"))
        assertTrue(result.problems.toString(), result.complete)
        val reply = result.confirmed["7E8"]!!
        assertEquals(0xFF, reply.availableStatusMask)
        assertEquals(listOf("123456", "89ABCD"), reply.faults.map { it.raw24 })
        assertEquals(listOf(0x7F, 0x01), reply.faults.map { it.rawStatus })
    }

    @Test fun validEmptyPositiveResponseDoesNotCreateFaults() {
        val frames = ElmFrames.parse("1902FF", "7E8 03 59 02 FF\r>")
        val result = VagUdsReadOnly.assessDtcByStatusMask(frames, setOf("7E8"))
        assertTrue(result.complete)
        assertTrue(result.confirmed["7E8"]!!.faults.isEmpty())
    }

    @Test fun supportMaskIsRetainedWithoutInventingFlags() {
        val data = byteArrayOf(0x59, 0x02, 0x09, 0x12, 0x34, 0x56, 0xFF.toByte())
        val result = VagUdsReadOnly.decodeDtcByStatusMask("7E8", data)
        assertTrue(result is VagUdsReadOnly.DtcReply.Positive)
        val fault = (result as VagUdsReadOnly.DtcReply.Positive).faults.single()
        assertEquals("123456", fault.raw24)
        assertEquals(0xFF, fault.rawStatus)
        assertEquals(0x09, fault.supportedStatus)
    }

    @Test fun negativeAndResponsePendingCannotClaimNoDtc() {
        val refused = ElmFrames.parse("1902FF", "7E8 03 7F 19 31\r>")
        val pending = ElmFrames.parse("1902FF", "7E8 03 7F 19 78\r>")
        val result1 = VagUdsReadOnly.assessDtcByStatusMask(refused, setOf("7E8"))
        val result2 = VagUdsReadOnly.assessDtcByStatusMask(pending, setOf("7E8"))
        assertFalse(result1.complete)
        assertFalse(result2.complete)
        assertTrue(result1.confirmed.isEmpty())
        assertTrue(result2.confirmed.isEmpty())
        assertTrue(result1.problems.any { "0x31" in it })
        assertTrue(result2.problems.any { "0x78" in it })
    }

    @Test fun truncatedIsoTpAndInvalidSequenceRemainUnconfirmed() {
        val missingEnd = ElmFrames.parse("1902FF", "7E8 10 0B 59 02 FF 12 34 56\r>")
        val outOfOrder = ElmFrames.parse(
            "1902FF",
            "7E8 10 0B 59 02 FF 12 34 56\r7E8 22 7F 89 AB CD 01 00 00\r>"
        )
        for (frames in listOf(missingEnd, outOfOrder)) {
            val check = VagUdsReadOnly.assessDtcByStatusMask(frames, setOf("7E8"))
            assertFalse(check.complete)
            assertTrue(check.confirmed.isEmpty())
            assertTrue(check.problems.isNotEmpty())
        }
    }

    @Test fun malformedPositiveAndWrongServiceAreRejected() {
        val malformed = VagUdsReadOnly.decodeDtcByStatusMask(
            "7E8", byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x12, 0x34)
        )
        val wrong = VagUdsReadOnly.decodeDtcByStatusMask(
            "7E8", byteArrayOf(0x59, 0x01, 0xFF.toByte())
        )
        assertTrue(malformed is VagUdsReadOnly.DtcReply.Invalid)
        assertTrue(wrong is VagUdsReadOnly.DtcReply.Invalid)
    }

    @Test fun anotherEcuAnswerCannotBeAttributedToRequestedEcu() {
        val frames = ElmFrames.parse("1902FF", "7E9 03 59 02 FF\r>")
        val result = VagUdsReadOnly.assessDtcByStatusMask(frames, setOf("7E8"))
        assertFalse(result.complete)
        assertTrue(result.confirmed.isEmpty())
        assertTrue(result.problems.any { "незапрошенного" in it })
    }

    @Test fun gatewayRequiresPlatformAddressAndValidatedIsoTp() {
        val none = VagGatewayReadiness.evaluate(true, true, null, false)
        assertTrue(none is VagGatewayReadiness.Decision.Blocked)

        // A non-vehicle test fixture is only useful for checking the gate.
        val fakeProfile = VagGatewayReadiness.VerifiedProfile(
            platform = "unit-test-only",
            diagnosticRequestCanId = 0x700,
            diagnosticResponseCanId = 0x708,
            installationListDid = 0x1234,
            source = "synthetic test, not an actual VAG profile"
        )
        assertTrue(VagGatewayReadiness.evaluate(true, true, fakeProfile, false)
            is VagGatewayReadiness.Decision.Blocked)
        assertTrue(VagGatewayReadiness.evaluate(false, true, fakeProfile, true)
            is VagGatewayReadiness.Decision.Blocked)
        assertTrue(VagGatewayReadiness.evaluate(true, null, fakeProfile, true)
            is VagGatewayReadiness.Decision.Blocked)
        assertTrue(VagGatewayReadiness.evaluate(true, true, fakeProfile, true)
            is VagGatewayReadiness.Decision.EligibleForImplementation)
        assertFalse(fakeProfile.copy(diagnosticRequestCanId = 0x7DF).valid())
        assertFalse(fakeProfile.copy(installationListDid = 0).valid())
        assertFalse(fakeProfile.copy(source = "").valid())
    }
}
