package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TP2.0 channel setup vectors from public reverse-engineering references. */
class VagTp20GatewayTest {
    @Test fun readsDocumentedChannelSetupAndGatewayLogicalAddress() {
        assertEquals("1FC00010000301", VagTp20Gateway.channelRequest())
        assertEquals(0x200, VagTp20Gateway.SETUP_CAN_ID)
        assertEquals(0x21F, VagTp20Gateway.GATEWAY_REPLY_CAN_ID)
    }

    @Test fun validatesPositiveResponseOnlyWithExpected21FHeaderAndIds() {
        for (sample in listOf("21F00D00003A80701\r>",
            "21F 00 D0 00 03 A8 07 01\r>",
            "21F 7 00 D0 00 03 A8 07 01\r>",
            "21F 8 00 D0 00 03 A8 07 01 00\r>")) {
            val reply = VagTp20Gateway.decodeReply(sample)
            assertTrue("$sample: $reply", reply is VagTp20Gateway.Result.Accepted)
            val ok = reply as VagTp20Gateway.Result.Accepted
            assertEquals(0x300, ok.testerReceiveCanId)
            assertEquals(0x7A8, ok.testerTransmitCanId)
        }
    }

    @Test fun rejectsWrongCanIdAndMissingHeader() {
        for (sample in listOf("21300D00003A80701\r>",
            "00D00003A80701\r>", "NO DATA\r>",
            "21F\r>", "21F00D00003A80701\r21F00D00003A80701\r>")) {
            assertTrue(sample, VagTp20Gateway.decodeReply(sample)
                is VagTp20Gateway.Result.Rejected)
        }
    }

    @Test fun distinguishesNegativeGatewayResultAndInvalidNegotiation() {
        for (sample in listOf("21F00D60003A80701\r>",
            "21F00D70003A80701\r>", "21F00D80003A80701\r>",
            "21F00D00003A81701\r>", // invalid channel prefix
            "21F00D00103A80701\r>", // wrong RX 0x301
            "21F01D00003A80701\r>", // wrong tester address
            "21F00D00003A80700\r>")) { // wrong application
            assertTrue(sample, VagTp20Gateway.decodeReply(sample)
                is VagTp20Gateway.Result.Rejected)
        }
    }

    @Test fun detectsOnlyTheChassisMarkerAndNeverPersistsVin() {
        val markerVin = "WVWAAA1KAAAAAAAAA"
        val bytes = byteArrayOf(0x49, 0x02, 0x01) +
            markerVin.map { it.code.toByte() }.toByteArray()
        val frames = FrameParse(mapOf("7E8" to listOf(bytes)), emptyList())
        assertEquals("1K", VagTp20Gateway.chassisFromVerifiedMode09(frames, "7E8"))
        assertTrue(VagTp20Gateway.knownCandidate("1K") != null)
        assertEquals(null, VagTp20Gateway.knownCandidate("4G"))
        assertFalse(VagTp20Gateway.knownCandidate("1K")!!.source.contains(markerVin))
        assertEquals(null, VagTp20Gateway.chassisFromVerifiedMode09(frames, "7E9"))
    }

    @Test fun malformedVinReplyDoesNotEnableTp20() {
        val tooShort = FrameParse(mapOf("7E8" to listOf(byteArrayOf(0x49, 0x02, 0x01))), emptyList())
        assertEquals(null, VagTp20Gateway.chassisFromVerifiedMode09(tooShort, "7E8"))
    }
    private fun syntheticIdentity(wmi: String, chassis: String): ByteArray =
        byteArrayOf(0x49, 2, 1) + (wmi + "AAA" + chassis + "AAAAAAAAA").map { it.code.toByte() }.toByteArray()
    @Test fun verifiedManufacturerAllowsFixedCatalogFallbackForOtherVagChassis() {
        for ((wmi, chassis) in listOf("WVW" to "AU", "WAU" to "4G", "TMB" to "NE", "VSS" to "KL")) {
            // Synthetic identifiers only; no recorded vehicle VIN in fixtures.
            val frames = FrameParse(mapOf("7E8" to listOf(syntheticIdentity(wmi, chassis))), emptyList())
            val profile = requireNotNull(VagTp20Gateway.profileFromVerifiedMode09(frames, "7E8", setOf("7E8")))
            assertEquals(chassis, profile.chassis)
            assertTrue(profile.allowCandidateFallback)
            assertFalse(profile.source.contains(wmi + "AAA"))
        }
    }
    @Test fun unknownManufacturerOrConflictingVinCannotEnableGatewayProbe() {
        val unknown = FrameParse(mapOf("7E8" to listOf(syntheticIdentity("XXX", "1K"))), emptyList())
        assertEquals(null, VagTp20Gateway.profileFromVerifiedMode09(unknown, "7E8", setOf("7E8")))
        val conflict = FrameParse(mapOf("7E8" to listOf(syntheticIdentity("WVW", "AU")),
            "7E9" to listOf(syntheticIdentity("WAU", "4G"))), emptyList())
        assertEquals(null, VagTp20Gateway.profileFromVerifiedMode09(conflict, "7E8", setOf("7E8", "7E9")))
    }
}
