package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.vagdiagnostic.app.core.VagTp20Gateway
import org.junit.Test

/** Uses the same byte contract for Wi-Fi TCP, RFCOMM and BLE NUS. */
class ElmAddressedObdProbeTest {
    private class Link(
        private val rejectAt: String? = null,
        private val reply0902: String =
            "7E81014490201313233\r7E82134353637383941\r7E82242434445464748",
        private val obd: String = "7E8064100BE3EA813",
        private val proto: String = "A6",
        private val addressedObd: String = obd
    ) : ElmLink {
        override val label = "scripted byte-stream"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() {}
        override suspend fun write(data: ByteArray) {
            val cmd = data.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val answer = when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATDPN" -> proto
                "0100" -> if (sent.count { it == "0100" } == 1) obd else addressedObd
                "ATCAF1", "ATCFC1", "ATSH7E0", "ATCRA7E8",
                "ATSH7E1", "ATCRA7E9" ->
                    if (cmd == rejectAt) "?" else "OK"
                "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1" -> "OK"
                "0902" -> reply0902
                else -> throw IOException("Unexpected or unsafe command: $cmd")
            }
            for (c in (answer + ">").toByteArray(Charsets.US_ASCII))
                bytes.add(c.toInt() and 255)
        }
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override fun close() = bytes.clear()
    }

    @Test fun fullAddressedIsoTpProofUsesOnlyLegislatedReadOnlyObd() = runBlocking {
        val link = Link()
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8")
        assertTrue(result.reason, result.verified)
        assertTrue(result.atAccepted)
        assertTrue(link.sent.contains("ATCRA7E8"))
        assertTrue(link.sent.contains("0902"))
        assertFalse(link.sent.any { it.startsWith("19") || it.startsWith("22") })
        assertFalse(result.journalSummary().contains("123456789ABCDEFGH"))
    }

    @Test fun unsupportedAtFilterDoesNotSendAnyAdditionalEcuRequest() = runBlocking {
        val link = Link(rejectAt = "ATCRA7E8")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8")
        assertFalse(result.verified)
        assertFalse(result.atAccepted)
        assertFalse(link.sent.contains("0902"))
    }

    @Test fun unverifiedEcuAddressMustNotBeProbed() = runBlocking {
        val link = Link(obd = "7E906410098188001")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8")
        assertFalse(result.verified)
        assertFalse(link.sent.contains("ATSH7E0"))
    }

    @Test fun noDataAndTruncatedIsoTpAreNotFalseHardwareProof() = runBlocking {
        for (raw in listOf("NO DATA", "7E81014490201313233")) {
            val link = Link(reply0902 = raw)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8")
            assertFalse(result.verified)
            assertTrue(result.atAccepted)
        }
    }

    @Test fun standard7E9ResponderUses7E1PhysicalRequest() = runBlocking {
        val link = Link(
            obd = "7E906410098188001",
            reply0902 = "7E91014490201313233\r7E92134353637383941\r7E92242434445464748"
        )
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E9")
        assertTrue(result.reason, result.verified)
        assertTrue(link.sent.contains("ATSH7E1"))
        assertTrue(link.sent.contains("ATCRA7E9"))
        assertFalse(link.sent.contains("ATSH7E0"))
    }

    @Test fun unsupportedCanProtocolSkipsAddressedCanCompletely() = runBlocking {
        val link = Link(proto = "A7")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8")
        assertFalse(result.verified)
        assertFalse(link.sent.contains("ATCAF1"))
    }
    @Test fun missingVinDoesNotBlockConfirmedPhysicalObdButCannotCreateLegacyCandidates() = runBlocking {
        val link = Link(reply0902 = "NO DATA")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8", readVin = false)
        assertTrue(result.physicalResponseConfirmed)
        assertFalse(result.verified) // No claim about multi-frame FC.
        assertFalse(link.sent.contains("0902"))
        assertEquals(2, link.sent.count { it == "0100" })
        val profile = VagTp20Gateway.discoveryProfile(null, "7E8", emptySet())
        assertFalse(profile.allowCandidateFallback)
        assertEquals("VAG", profile.brand)
    }
    @Test fun atAcceptanceWithoutActualAddressedResponseCannotStartOem() = runBlocking {
        for (bad in listOf("NO DATA", "7E9064100BE3EA813", "7E803410001", "7E8064101BE3EA813")) {
            val link = Link(addressedObd = bad)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val result = ElmAddressedObdProbe.runOnDisposableEngine(engine, "7E8", readVin = false)
            assertTrue(result.atAccepted)
            assertFalse(result.physicalResponseConfirmed)
            assertFalse(link.sent.contains("0902"))
        }
    }
}
