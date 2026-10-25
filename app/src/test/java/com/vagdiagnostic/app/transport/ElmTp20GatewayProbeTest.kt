package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagTp20Gateway
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same scripted byte contract as Wi-Fi, SPP and BLE; not a physical adapter test. */
class ElmTp20GatewayProbeTest {
    private class Fake(private val rejectAt: String? = null,
                       private val gatewayReply: String = "21900D00003A80701",
                       private val protocol: String = "A6") : ElmLink {
        override val label = "Scripted ELM link"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        override suspend fun open() {}
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val result = when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATDPN" -> protocol
                "0100" -> "7E8064100BE3EA813\r7E906410098188001"
                "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1",
                "ATCAF0", "ATCFC0", "ATV1", "ATAT0", "ATST96", "ATSH200", "ATCRA219" ->
                    if (cmd == rejectAt) "?" else "OK"
                "19C00010000301" -> gatewayReply
                else -> throw IOException("Unexpected command in TP20 test: " + cmd)
            }
            for (v in (result + ">").toByteArray(Charsets.US_ASCII)) queue.add(v.toInt() and 255)
        }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        override fun close() = queue.clear()
    }

    @Test fun verified1KProfileOpensOnlyOneGatewayChannelWithoutKwpCommands() = runBlocking {
        for (transport in listOf("Wi-Fi", "Bluetooth Classic", "BLE NUS")) {
            val link = Fake()
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val result = ElmTp20GatewayProbe.inspect(
                engine, VagTp20Gateway.knownCandidate("1K")
            )
            assertTrue(transport + ": " + result.status, result.accepted)
            assertEquals(0x7A8, result.negotiatedTxCanId)
            assertEquals(1, link.sent.count { it == "19C00010000301" })
            assertTrue(link.sent.containsAll(listOf("ATCAF0", "ATCFC0", "ATV1", "ATAT0", "ATST96", "ATSH200", "ATCRA219")))
            assertTrue(link.sent.indexOf("ATV1") < link.sent.indexOf("19C00010000301"))
            assertFalse(link.sent.any { it.startsWith("22") || it == "14" || it == "1902FF" })
        }
    }

    @Test fun unsupportedChassisNeverSendsCanSetup() = runBlocking {
        val link = Fake()
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        assertFalse(ElmTp20GatewayProbe.inspect(engine, null).accepted)
        assertFalse(link.sent.contains("ATSH200"))
        assertFalse(link.sent.contains("19C00010000301"))
    }

    @Test fun adapterRejectionStopsBeforeAnyGatewayTraffic() = runBlocking {
        val link = Fake(rejectAt = "ATCFC0")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmTp20GatewayProbe.inspect(
            engine, VagTp20Gateway.knownCandidate("1K")
        )
        assertFalse(result.accepted)
        assertFalse(link.sent.contains("ATSH200"))
        assertFalse(link.sent.contains("19C00010000301"))
    }

    @Test fun cloneRejectingVariableDlcCannotSendUnreliableChannelSetup() = runBlocking {
        val link = Fake(rejectAt = "ATV1")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmTp20GatewayProbe.inspect(engine, VagTp20Gateway.knownCandidate("1K"))
        assertFalse(result.accepted)
        assertFalse(link.sent.contains("ATSH200"))
        assertFalse(link.sent.contains("19C00010000301"))
    }

    @Test fun cloneRejectingReceiveTimeoutSettingCannotSendSetup() = runBlocking {
        val link = Fake(rejectAt = "ATST96")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmTp20GatewayProbe.inspect(engine, VagTp20Gateway.knownCandidate("1K"))
        assertFalse(result.accepted)
        assertFalse(link.sent.contains("19C00010000301"))
    }

    @Test fun gatewayNoDataDoesNotCountAsInstalledEcu() = runBlocking {
        val link = Fake(gatewayReply = "NO DATA")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmTp20GatewayProbe.inspect(
            engine, VagTp20Gateway.knownCandidate("1K")
        )
        assertFalse(result.accepted)
        assertEquals(null, result.negotiatedTxCanId)
    }

    @Test fun unknownCanProtocolBlocksSetupEvenWith1KMarker() = runBlocking {
        val link = Fake(protocol = "A7")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmTp20GatewayProbe.inspect(
            engine, VagTp20Gateway.knownCandidate("1K")
        )
        assertFalse(result.accepted)
        assertFalse(link.sent.contains("ATCAF0"))
    }
}
