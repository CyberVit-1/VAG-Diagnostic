package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TP2.0 channel setup vectors from public reverse-engineering references. */
class VagTp20GatewayTest {
    @Test fun readsDocumentedChannelSetupAndGatewayLogicalAddress() {
        assertEquals("19C00010000301", VagTp20Gateway.channelRequest())
        assertEquals(0x200, VagTp20Gateway.SETUP_CAN_ID)
        assertEquals(0x219, VagTp20Gateway.GATEWAY_REPLY_CAN_ID)
    }

    @Test fun validatesPositiveResponseOnlyWithExpected219HeaderAndIds() {
        for (sample in listOf("21900D00003A80701\r>",
            "219 00 D0 00 03 A8 07 01\r>",
            "219 7 00 D0 00 03 A8 07 01\r>")) {
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
            "219\r>", "21900D00003A80701\r21900D00003A80701\r>")) {
            assertTrue(sample, VagTp20Gateway.decodeReply(sample)
                is VagTp20Gateway.Result.Rejected)
        }
    }

    @Test fun distinguishesNegativeGatewayResultAndInvalidNegotiation() {
        for (sample in listOf("21900D60003A80701\r>",
            "21900D70003A80701\r>", "21900D80003A80701\r>",
            "21900D00003A81701\r>", // invalid channel prefix
            "21900D00103A80701\r>", // wrong RX 0x301
            "21901D00003A80701\r>", // wrong tester address
            "21900D00003A80700\r>")) { // wrong application
            assertTrue(sample, VagTp20Gateway.decodeReply(sample)
                is VagTp20Gateway.Result.Rejected)
        }
    }

    @Test fun detectsOnlyTheChassisMarkerAndNeverPersistsVin() {
        val markerVin = "AAAAAA1KAAAAAAAAA"
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
}
