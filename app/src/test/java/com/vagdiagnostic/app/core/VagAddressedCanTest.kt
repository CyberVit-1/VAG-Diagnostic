package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline only. These synthetic IDs/DIDs are NOT real-world VAG address profiles. */
class VagAddressedCanTest {
    private val testOnly = VagGatewayReadiness.VerifiedProfile(
        platform = "synthetic-test-not-vehicle",
        diagnosticRequestCanId = 0x700,
        diagnosticResponseCanId = 0x708,
        installationListDid = 0x1234,
        source = "synthetic fixture: not verified for any real car"
    )

    @Test fun missingEvidenceAlwaysBlocksAddressedElmCommands() {
        val empty = VagAddressedCan.prepareGatewayDtcRead(
            connected = true, elmProtocol = "A6", profile = testOnly,
            evidence = VagAddressedCan.AdapterEvidence()
        )
        assertTrue(empty is VagAddressedCan.PlanResult.Blocked)
        val onlyCan = VagAddressedCan.prepareGatewayDtcRead(
            true, "A6", testOnly,
            VagAddressedCan.AdapterEvidence(confirmed11Bit500k = true)
        )
        assertTrue(onlyCan is VagAddressedCan.PlanResult.Blocked)
        val onlyFlow = VagAddressedCan.prepareGatewayDtcRead(
            true, "A6", testOnly,
            VagAddressedCan.AdapterEvidence(confirmed11Bit500k = true,
                addressedFlowControlValidated = true)
        )
        assertTrue(onlyFlow is VagAddressedCan.PlanResult.Blocked)
        assertTrue(VagAddressedCan.prepareGatewayDtcRead(
            true, "A6", null,
            VagAddressedCan.AdapterEvidence(true, true, true)
        ) is VagAddressedCan.PlanResult.Blocked)
        assertTrue(VagAddressedCan.prepareGatewayDtcRead(
            false, "A6", testOnly,
            VagAddressedCan.AdapterEvidence(true, true, true)
        ) is VagAddressedCan.PlanResult.Blocked)
        assertTrue(VagAddressedCan.prepareGatewayDtcRead(
            true, "A7", testOnly,
            VagAddressedCan.AdapterEvidence(true, true, true)
        ) is VagAddressedCan.PlanResult.Blocked)
    }

    @Test fun fullyDocumentedSyntheticGateProducesOnlyWhitelistedReadOnlyPlan() {
        val result = VagAddressedCan.prepareGatewayDtcRead(
            true, "A6", testOnly, VagAddressedCan.AdapterEvidence(true, true, true)
        )
        assertTrue(result is VagAddressedCan.PlanResult.Prepared)
        val plan = (result as VagAddressedCan.PlanResult.Prepared).plan
        assertEquals(listOf("ATCAF1", "ATCFC1", "ATSH700", "ATCRA708"), plan.setup)
        assertEquals("1902FF", plan.request)
        assertEquals(0x708, plan.expectedResponseCanId)
        assertTrue(plan.mustReinitializeObdAfterwards)
    }

    @Test fun decodesRealisticCompactAndSpacedSingleFrame() {
        for (raw in listOf("7E8 03 59 02 FF\r>", "7E8035902FF\r>")) {
            val iso = VagAddressedCan.decodeSingleEcu(raw, 0x7E8)
            assertTrue(iso.problems.toString(), iso.valid)
            val parsed = VagUdsReadOnly.decodeDtcByStatusMask("7E8", iso.payload!!)
            assertTrue(parsed is VagUdsReadOnly.DtcReply.Positive)
            assertTrue((parsed as VagUdsReadOnly.DtcReply.Positive).faults.isEmpty())
        }
    }

    @Test fun assemblesFirstAndConsecutiveFramesInOrder() {
        val iso = VagAddressedCan.decodeSingleEcu(
            "7E8 10 0B 59 02 FF 12 34 56\r7E8 21 7F 89 AB CD 01 00 00\r>", 0x7E8
        )
        assertTrue(iso.problems.toString(), iso.valid)
        val parsed = VagUdsReadOnly.decodeDtcByStatusMask("7E8", iso.payload!!)
        assertTrue(parsed is VagUdsReadOnly.DtcReply.Positive)
        assertEquals(listOf("123456", "89ABCD"),
            (parsed as VagUdsReadOnly.DtcReply.Positive).faults.map { it.raw24 })
    }

    @Test fun validatesNumberedCaf1AgainstAdvertisedLength() {
        val valid = VagAddressedCan.decodeSingleEcu(
            "00B\r7E8 0: 59 02 FF 12 34 56 7F\r7E8 1: 89 AB CD 01\r>", 0x7E8
        )
        assertTrue(valid.problems.toString(), valid.valid)
        assertEquals(11, valid.payload!!.size)
        val short = VagAddressedCan.decodeSingleEcu(
            "00C\r7E8 0: 59 02 FF 12 34 56 7F\r7E8 1: 89 AB CD 01\r>", 0x7E8
        )
        assertFalse(short.valid)
        assertTrue(short.problems.any { it.contains("Обрезанный") })
        val noLength = VagAddressedCan.decodeSingleEcu(
            "7E8 0: 59 02 FF\r>", 0x7E8
        )
        assertFalse(noLength.valid)
    }

    @Test fun stopsOnTruncatedOrDisorderedIsoTp() {
        val transcripts = listOf(
            "7E8 10 0B 59 02 FF 12 34 56\r>",
            "7E8 10 0B 59 02 FF 12 34 56\r7E8 22 7F 89 AB CD 01\r>",
            "7E8 03 59 02\r>",
            "7E8 03 59 02 FF\r7E8 03 59 02 FF\r>",
            "7E8 30 00 00\r>"
        )
        transcripts.forEach { sample ->
            val r = VagAddressedCan.decodeSingleEcu(sample, 0x7E8)
            assertFalse(sample, r.valid)
            assertTrue(sample, r.problems.isNotEmpty())
        }
    }

    @Test fun wrongOrMissingEcuHeaderNeverAccepted() {
        val cases = listOf(
            "7E9 03 59 02 FF\r>",
            "03 59 02 FF\r>",
            "NO DATA\r>",
            "7E8 03 59 02 FF\r7E9 03 59 02 FF\r>",
            "00B\r0: 59 02 FF 12 34 56 7F\r1: 89 AB CD 01\r>"
        )
        cases.forEach { sample ->
            val r = VagAddressedCan.decodeSingleEcu(sample, 0x7E8)
            assertFalse(sample, r.valid)
        }
    }

    @Test fun explicitDlcFormatFromCanReadersIsValidatedRatherThanGuessed() {
        val example = VagAddressedCan.decodeSingleEcu(
            "7E8 8 10 0B 59 02 FF 12 34 56\r7E8 8 21 7F 89 AB CD 01 00 00\r>",
            0x7E8
        )
        assertTrue(example.problems.toString(), example.valid)
        assertEquals(11, example.payload!!.size)
        val broken = VagAddressedCan.decodeSingleEcu(
            "7E8 8 03 59 02 FF\r>", 0x7E8
        )
        assertFalse(broken.valid)
    }

    @Test fun strictDecoderNeverForwardsWrongEcuToUdsDtcAssessment() {
        val bad = VagAddressedCan.interpretGatewayDtcReply("7E9 03 59 02 FF\r>", 0x7E8)
        assertTrue(bad is VagUdsReadOnly.DtcReply.Invalid)
        val valid = VagAddressedCan.interpretGatewayDtcReply("7E8 03 59 02 FF\r>", 0x7E8)
        assertTrue(valid is VagUdsReadOnly.DtcReply.Positive)
        assertTrue((valid as VagUdsReadOnly.DtcReply.Positive).faults.isEmpty())
        val pending = VagAddressedCan.interpretGatewayDtcReply("7E8 03 7F 19 78\r>", 0x7E8)
        assertTrue(pending is VagUdsReadOnly.DtcReply.Pending)
        val refused = VagAddressedCan.interpretGatewayDtcReply("7E8 03 7F 19 31\r>", 0x7E8)
        assertTrue(refused is VagUdsReadOnly.DtcReply.Negative)
    }

    @Test fun supportsExplicit29BitResponseHeaderWithoutGuessing11BitAddress() {
        val ok = VagAddressedCan.decodeSingleEcu("18DAF110035902FF\r>", 0x18DAF110)
        assertTrue(ok.problems.toString(), ok.valid)
        assertEquals(3, ok.payload!!.size)
        assertFalse(VagAddressedCan.decodeSingleEcu("18DAF110035902FF\r>", 0x7E8).valid)
    }
}
