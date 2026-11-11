package com.vagdiagnostic.app.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class ElmSettingCacheTest {
    private class Link(val replies: MutableMap<String, ArrayDeque<String>> = mutableMapOf()) : ElmLink {
        override val label = "Synthetic setting acknowledgements"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() { }
        override fun close() { bytes.clear() }
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val response = replies[command]?.pollFirst() ?: if (command == "ATZ") "ELM327" else "OK"
            for (byte in (response + "\r>").toByteArray()) this.bytes.addLast(byte.toInt() and 255)
        }
    }
    @Test fun cacheHitSendsNoCommandAndInventsNoRawJournalOrActivityTime() = runBlocking {
        val link = Link()
        var now = 0L
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { command, _, _, _ -> journal += command }, clockNanos = { now })
        assertTrue(engine.configureSetting("ATCAF0"))
        now = 2_000_000_000L
        assertFalse(engine.configureSetting("ATCAF0"))
        assertEquals(listOf("ATCAF0"), link.sent)
        assertEquals(link.sent, journal)
        assertEquals(2000L, engine.idleMs())
    }
    @Test fun uncachedRealCommandsUpdateTheSameConfirmedState() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.command("ATCAF0")
        assertFalse(engine.configureSetting("ATCAF0"))
        engine.command("ATCAF1")
        assertTrue(engine.configureSetting("ATCAF0"))
        assertEquals(listOf("ATCAF0", "ATCAF1", "ATCAF0"), link.sent)
    }
    @Test fun resetAndActualProtocolSelectionInvalidateCanSettings() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.configureSetting("ATCAF0")
        engine.command("ATSP6")
        assertTrue(engine.configureSetting("ATCAF0"))
        assertFalse(engine.configureSetting("ATSP6"))
        assertFalse(engine.configureSetting("ATCAF0"))
        engine.command("ATZ")
        assertTrue(engine.configureSetting("ATCAF0"))
        engine.command("ATSP6")
        assertTrue(engine.configureSetting("ATCAF0"))
    }
    @Test fun changingPriorityInvalidatesThePreviouslyConfirmedLowHeader() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.configureSetting("ATCP17"); engine.configureSetting("ATSHFC008B")
        assertFalse(engine.configureSetting("ATSHFC008B"))
        engine.configureSetting("ATCP18")
        assertTrue(engine.configureSetting("ATSHFC008B"))
    }
    @Test fun changingFlowModeRequiresHeaderAndDataToBeConfirmedAgain() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.configureSetting("ATFCSM1")
        engine.configureSetting("ATFCSH710"); engine.configureSetting("ATFCSD300010")
        assertFalse(engine.configureSetting("ATFCSH710"))
        engine.configureSetting("ATFCSM0")
        assertTrue(engine.configureSetting("ATFCSH710"))
        assertTrue(engine.configureSetting("ATFCSD300010"))
    }
    @Test fun negativeOrMixedAcknowledgementNeverPopulatesTheCache() = runBlocking {
        for (raw in listOf("?", "OK\rCAN ERROR")) {
            val link = Link(mutableMapOf("ATCAF0" to ArrayDeque(listOf(raw, "OK"))))
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertTrue(runCatching { engine.configureSetting("ATCAF0") }.exceptionOrNull() is IOException)
            assertTrue(engine.configureSetting("ATCAF0"))
            assertFalse(engine.configureSetting("ATCAF0"))
            assertEquals(2, link.sent.size)
        }
    }
    @Test fun vehicleRequestsAreNeverCachedAndUnknownAdapterCommandsInvalidateState() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.configureSetting("ATCAF0")
        for (request in listOf("0100", "1902AF", "ATMA"))
            assertTrue(runCatching { engine.configureSetting(request) }.exceptionOrNull() is IllegalArgumentException)
        engine.command("0100"); engine.command("0100")
        assertFalse(engine.configureSetting("ATCAF0"))
        engine.command("ATUNKNOWN")
        assertTrue(engine.configureSetting("ATCAF0"))
        assertEquals(2, link.sent.count { it == "0100" })
    }
    @Test fun closedAndNewConnectionsDoNotShareConfirmedSettings() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.configureSetting("ATCAF0"); engine.close()
        assertTrue(engine.configureSetting("ATCAF0"))
        val other = ElmEngine(link, { _, _, _, _ -> })
        assertTrue(other.configureSetting("ATCAF0"))
        assertEquals(3, link.sent.size)
    }
    @Test fun nativeSettingSyntaxCannotInjectAnotherCommand() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> })
        for (command in listOf("VTFCTRA 710, 77A\r1902AF", "VTSWGP GR1, BZF1;ATZ",
            "VTTOST REP:2000 14FFFFFF", "STCFCPA710,77A,7E8"))
            assertTrue(runCatching { engine.command(command) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(link.sent.isEmpty())
    }
}
