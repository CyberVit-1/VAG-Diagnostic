package com.vagdiagnostic.app.transport

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class ElmPendingReceiveTest {
    private class Link(val reply: String) : ElmLink {
        override val label = "Receive-only fixture"
        val sent = mutableListOf<String>()
        val queue = ArrayDeque<Int>()
        var closed = false
        override suspend fun open() {}
        override fun close() { closed = true; queue.clear() }
        override suspend fun readByte() = queue.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val raw = if (cmd == "ATMA") reply else if (cmd.isEmpty()) "STOPPED\r>" else error(cmd)
            raw.toByteArray(Charsets.US_ASCII).forEach { queue.add(it.toInt() and 255) }
        }
    }
    @Test fun splitInputAndStopPromptPreserveRawJournalWithoutSendingDiagnosticRequest() = runBlocking {
        val link = Link("77F03590299\r")
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { _, raw, _, _ -> journal += raw })
        assertEquals("77F03590299", engine.receivePendingUdsFrame(0x77F, 100))
        assertEquals(listOf("ATMA", ""), link.sent)
        assertFalse(link.closed)
        assertTrue(journal.single().contains("STOPPED"))
    }
    @Test fun receiveOnlyUsesExact29BitId() = runBlocking {
        val link = Link("17FE008B03590219\r")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        assertEquals("17FE008B03590219", engine.receivePendingUdsFrame(0x17FE008B, 100))
        assertEquals(listOf("ATMA", ""), link.sent)
    }
    @Test fun rawFirstFrameAndOtherOwnersAreRetainedUntilMonitorStopsBeforeManualFc() = runBlocking {
        val link = Link("77A100A622A26010203\r7B404622A2680\r")
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { _, raw, _, _ -> journal += raw })
        val raw = engine.receivePendingCanFrames(setOf(0x77A, 0x7B4), 100, deferJournal = true)
        assertTrue(raw.contains("77A100A"))
        assertTrue(raw.contains("7B404622A2680"))
        assertEquals(listOf("ATMA", ""), link.sent)
        assertTrue(journal.isEmpty())
        engine.flushJournal()
        assertTrue(journal.single().contains("STOPPED"))
        assertFalse(link.closed)
    }
    @Test fun rawMonitorStillRejectsForeignIdsAndCannotResendTheRequest() = runBlocking {
        val link = Link("77B100A622A26010203\r")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        assertTrue(runCatching { engine.receivePendingCanFrames(setOf(0x77A), 100) }.exceptionOrNull() is IOException)
        assertTrue(link.closed)
        assertEquals(listOf("ATMA"), link.sent)
    }
    @Test fun wrongAddressOrFirstFrameNeverLeavesLiveStreamWithOutstandingRequest() = runBlocking {
        for (raw in listOf("77E03590299\r", "77F100B590299123456\r", "BUFFER FULL\r")) {
            val link = Link(raw)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            try { engine.receivePendingUdsFrame(0x77F, 100); fail(raw) } catch (_: IOException) {}
            assertTrue(link.closed)
            assertEquals(listOf("ATMA"), link.sent)
        }
    }
    @Test fun timeoutPreservesPartialBytesAndDoesNotSendSessionClose() = runBlocking {
        val link = Link("77F03")
        var raw = ""
        val engine = ElmEngine(link, { _, data, _, _ -> raw = data })
        try { engine.receivePendingUdsFrame(0x77F, 30); fail("timeout expected") } catch (_: IOException) {}
        assertEquals("77F03", raw)
        assertTrue(link.closed)
        assertEquals(listOf("ATMA"), link.sent)
    }
    @Test fun userCancellationClosesMonitorWithoutFurtherTraffic() = runBlocking {
        val link = Link("")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        try { withTimeout(30) { engine.receivePendingUdsFrame(0x77F, 5000) }; fail("cancel expected") }
        catch (_: TimeoutCancellationException) {}
        assertTrue(link.closed)
        assertEquals(listOf("ATMA"), link.sent)
    }
}
