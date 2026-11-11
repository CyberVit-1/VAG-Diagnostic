package com.vagdiagnostic.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Synthetic receive-boundary checks, not evidence of vehicle response time. */
class ElmTp20ReceiveWindowTest {
    private class Link(val receive: String, val stopped: String = "STOPPED\r>",
        val delayStopMs: Long = 0, val delayRestoreMs: Long = 0,
        val ackReply: String = "3001200025800>", val delayBarePromptMs: Long = 0,
        val splitReads: Boolean = false) : ElmLink {
        override val label = "Synthetic TP receive window"
        val sent = mutableListOf<String>()
        val queue = ArrayDeque<Int>()
        var closed = false
        private var nextReadDelay = 0L
        private var promptDelayed = false
        private var idleRead = false
        override suspend fun open() {}
        override fun close() { closed = true; queue.clear() }
        override suspend fun readByte(): Int {
            if (splitReads) { idleRead = !idleRead; if (idleRead) return -2 }
            if (nextReadDelay > 0) { val wait = nextReadDelay; nextReadDelay = 0; delay(wait) }
            if (queue.peekFirst() == '>'.code && "A32" in sent && !promptDelayed && delayBarePromptMs > 0) {
                promptDelayed = true
                delay(delayBarePromptMs)
            }
            return queue.pollFirst() ?: -2
        }
        private fun enqueue(raw: String) { raw.toByteArray().forEach { queue.add(it.toInt() and 255) } }
        override suspend fun write(bytes: ByteArray) {
            val rawCommand = bytes.toString(Charsets.US_ASCII)
            val c = rawCommand.trim()
            sent += if (rawCommand == " ") "<interrupt>" else c
            if (rawCommand == " ") {
                if (stopped == "IO") throw IOException("stream lost during interrupt")
                if (stopped == "CANCEL") throw CancellationException("cancelled interrupt")
                nextReadDelay = delayStopMs
                // A late space in command mode does not repeat the last request.
                if (!queue.contains('>'.code)) enqueue(stopped)
                return
            }
            if (c == "ATST0B" && "A32" in sent) nextReadDelay = delayRestoreMs
            enqueue(when {
                c == "A32" -> receive
                c == "ATDPN" -> "6>"
                c.startsWith("AT") -> "OK>"
                c == "09C000100003011" -> "20900D00003A80701>"
                c == "A00194FF82FF" || c == "A3" -> "300A10F94FF82FF>"
                c == "1000021089" -> "300B1\r3001000025089>"
                c.matches(Regex("1[0-9A-F]00041802FF00")) -> "300B%X>".format((c.take(2).toInt(16) + 1) and 15)
                c in setOf("B10", "B20", "B30", "A80") -> ">"
                c == "B2" -> ackReply
                else -> error("Unexpected $c")
            })
        }
    }
    @Test fun dataWithoutA1InterruptsReceiveAndPreservesOriginalJournal() = runBlocking {
        val link = Link("3001100025800\r")
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { _, raw, _, _ -> journal += raw })
        val reply = engine.receiveTp20More(0x300)
        assertEquals("3001100025800\r", reply.raw)
        assertNotNull(reply.ackDueNanos)
        assertEquals(listOf("A32", "<interrupt>"), link.sent)
        assertTrue(journal.isEmpty())
        engine.flushJournal()
        assertTrue(journal.single().contains("STOPPED"))
        assertFalse(link.closed)
    }
    @Test fun a1AloneDoesNotInterruptAndPromptAlreadyInFlightCannotRepeatRequest() = runBlocking {
        for (raw in listOf("300A10F94FF82FF\r>", "300A10F94FF82FF\r3001100025800\r>")) {
            val link = Link(raw)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertEquals(raw.dropLast(1), engine.receiveTp20More(0x300).raw)
            assertEquals(if (raw.contains("5800")) listOf("A32", "<interrupt>") else listOf("A32"), link.sent)
            assertTrue(link.queue.isEmpty())
        }
    }
    @Test fun longWindowIsRestoredBeforeAckForDataWithAndWithoutA1() = runBlocking {
        for (prefix in listOf("", "300A10F94FF82FF\r")) {
            val link = Link(prefix + "3001100025800\r")
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
            val at = link.sent.indexOf("ATST80")
            assertEquals(listOf("ATST80", "A32", "<interrupt>", "ATST0B", "B20"), link.sent.drop(at))
            assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
        }
    }
    @Test fun completePendingIsAcknowledgedBeforeFinalPacketWithoutAnotherDtcRequest() = runBlocking {
        val link = Link("3001100037F1878\r")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
        assertEquals("ATST0B", link.sent[link.sent.indexOf("A32") + 2])
        assertTrue(link.sent.indexOf("B2") > link.sent.indexOf("A32"))
        assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
    }
    @Test fun firstFrameAndContinuationKeepTheirOwnAckWindows() = runBlocking {
        val link = Link("300010005580201\r", ackReply = "300120199>")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 2, 1, 1, 0x99), channel.request("1802FF00"))
        assertEquals(listOf("ATST80", "A32", "<interrupt>", "ATST0B", "B2", "B30"),
            link.sent.drop(link.sent.indexOf("ATST80")))
    }
    @Test fun slowPromptOrSettingRestorationCannotSendALateAck() = runBlocking {
        for ((stopDelay, restoreDelay) in listOf(190L to 0L, 0L to 190L)) {
            val link = Link("3001100025800\r", delayStopMs = stopDelay, delayRestoreMs = restoreDelay)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
            assertTrue(link.closed)
            assertFalse(link.sent.contains("B20"))
            assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
        }
    }
    @Test fun omittedFinalCrStillUsesFrameArrivalForItsAckDeadline() = runBlocking {
        for (wait in listOf(0L, 190L)) {
            val link = Link("3001100025800>", delayBarePromptMs = wait)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            val result = runCatching { channel.request("1802FF00") }
            if (wait == 0L) assertEquals(listOf(0x58, 0), result.getOrThrow())
            else { assertTrue(result.exceptionOrNull() is IOException); assertTrue(link.closed) }
            assertEquals(wait == 0L, link.sent.contains("B20"))
            assertFalse(link.sent.contains("<interrupt>"))
        }
    }
    @Test fun fragmentedA1AndDataAreCollectedBeforeTheStopPrompt() = runBlocking {
        val link = Link("300A10F94FF82FF\r\n3001100025800\r\n", splitReads = true)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
        assertEquals(1, link.sent.count { it == "<interrupt>" })
        assertEquals("B20", link.sent.last())
    }
    @Test fun foreignMalformedAndUnsolicitedStoppedCannotBecomeARead() = runBlocking {
        for (raw in listOf("3011100025800\r>", "30011000258ZZ\r>", "STOPPED\r3001100025800\r>")) {
            val link = Link(raw)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            assertTrue(raw, runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
            assertTrue(link.closed)
            assertFalse(link.sent.contains("B20"))
        }
    }
    @Test fun interruptedReceiveFailureClosesWithoutRestorationOrAck() = runBlocking {
        for (stop in listOf("IO", "CANCEL", "STOPPED\r")) {
            val link = Link("3001100025800\r", stop)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            val failure = runCatching { withTimeout(100) { channel.request("1802FF00") } }.exceptionOrNull()
            assertTrue(failure is IOException || failure is CancellationException)
            assertTrue(link.closed)
            assertEquals("<interrupt>", link.sent.last())
            assertFalse(link.sent.contains("B20"))
        }
    }
}
