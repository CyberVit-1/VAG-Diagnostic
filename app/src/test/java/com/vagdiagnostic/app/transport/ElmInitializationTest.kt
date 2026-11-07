package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic initialization responses, not evidence from an adapter model. */
class ElmInitializationTest {
    private class Link(val replies: Map<String, String> = emptyMap()) : ElmLink {
        override val label = "Scripted ELM initialization"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        var closes = 0
        override suspend fun open() {}
        override fun close() { closes++; queue.clear() }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val raw = replies[command] ?: when (command) {
                "ATZ" -> "ELM327 v1.5"
                "0100" -> "7E8064100BE3EA813"
                "ATDPN" -> "A6"
                else -> "OK"
            }
            queue.addAll((raw + "\r>").map { it.code })
        }
    }

    @Test fun resetBannerBrandDoesNotGateVerifiedAdapterAndVehicleReplies() = runBlocking<Unit> {
        val link = Link(mapOf("ATZ" to "Diagnostic adapter ready\rFirmware 1.0"))
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        assertTrue(engine.adapterReady)
        assertTrue(engine.vehicleReady)
        assertEquals("ATZ", link.sent.first())
        assertTrue(link.sent.contains("0100"))
        engine.close()
    }

    @Test fun mandatorySettingsRejectMixedAcknowledgmentBeforeVehicleTraffic() = runBlocking<Unit> {
        for (command in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")) {
            val link = Link(mapOf(command to "OK\rCAN ERROR"))
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertTrue(command, runCatching { engine.connect() }.exceptionOrNull() is IOException)
            assertEquals(command, link.sent.last())
            assertFalse(link.sent.contains("0100"))
            assertFalse(engine.adapterReady)
            assertTrue(link.closes > 0)
        }
    }

    @Test fun optionalAdaptiveTimingAcceptsOnlyAnUnambiguousAcknowledgmentOrRefusal() = runBlocking<Unit> {
        for (reply in listOf("OK\rNO DATA", "?\rCAN ERROR", "?garbage")) {
            val link = Link(mapOf("ATAT1" to reply))
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertTrue(reply, runCatching { engine.connect() }.exceptionOrNull() is IOException)
            assertEquals("ATAT1", link.sent.last())
            assertFalse(link.sent.contains("0100"))
            assertFalse(engine.adapterReady)
            assertTrue(link.closes > 0)
        }
    }

    @Test fun completedUnsupportedAdaptiveTimingStillPermitsGenericObd() = runBlocking<Unit> {
        val link = Link(mapOf("ATAT1" to "?"))
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        assertTrue(engine.vehicleReady)
        assertFalse(link.sent.contains("ATAT0"))
        engine.close()
    }

    @Test fun adapterReadinessWaitsForAllInitializationAcknowledgments() = runBlocking<Unit> {
        val link = Link()
        lateinit var engine: ElmEngine
        engine = ElmEngine(link, { command, _, _, _ ->
            if (command in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1"))
                assertFalse(command, engine.adapterReady)
            if (command == "0100") assertTrue(engine.adapterReady)
        })
        engine.connect()
        assertTrue(engine.vehicleReady)
        engine.close()
    }

    @Test fun explicitResetErrorsOrMixedErrorBannerCannotInitializeTheAdapter() = runBlocking<Unit> {
        for (reply in listOf("NO DATA", "?", "CAN ERROR", "ELM327 v1.5\rNO DATA", "")) {
            val link = Link(mapOf("ATZ" to reply))
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertNotNull(reply, runCatching { engine.connect() }.exceptionOrNull())
            assertEquals(listOf("ATZ"), link.sent)
            assertFalse(engine.adapterReady)
            assertFalse(engine.vehicleReady)
            assertTrue(link.closes > 0)
        }
    }
}
