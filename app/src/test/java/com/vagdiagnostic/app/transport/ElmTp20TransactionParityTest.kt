package com.vagdiagnostic.app.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Synthetic control-flow checks derived independently from the source path. */
class ElmTp20TransactionParityTest {
    private class Link(val idleReply: String = "300A10F94FF82FF", val silentSends: Int = 0,
        val dtcReply: String = "300B2\r3001100025800", val nackReply: String = "NO DATA") : ElmLink {
        override val label = "Synthetic transaction control"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        private var sends = 0
        override suspend fun open() {}
        override fun close() {}
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val raw = when {
                cmd == "ATDPN" -> "6"
                cmd.startsWith("AT") -> "OK"
                cmd == "09C000100003011" -> "20900D00003A80701"
                cmd == "A00194FF82FF" -> "300A10F94FF82FF"
                cmd == "1000021089" -> if (++sends <= silentSends) "NO DATA" else "300B1\r3001000025089"
                cmd == "1100041802FF00" -> dtcReply
                cmd == "A3" -> idleReply
                cmd == "91" -> nackReply
                cmd == "B6" -> "3001600025800"
                cmd in setOf("B10", "B20", "B70", "A80", "A10194FF82FF") -> "NO DATA"
                else -> error("Unexpected $cmd")
            }
            (raw + ">").toByteArray().forEach { this.bytes.add(it.toInt() and 255) }
        }
    }
    @Test fun nextApplicationRequestChecksIdleChannelBeforeSending() = runBlocking {
        val link = Link()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089"); channel.request("1802FF00")
        assertEquals(1, link.sent.count { it == "A3" })
        assertEquals(listOf("A3", "1100041802FF00", "B20"), link.sent.takeLast(3))
        assertFalse(link.sent.take(link.sent.indexOf("1000021089")).contains("A3"))
    }
    @Test fun idleChannelForeignApplicationDataCannotBeDiscarded() = runBlocking {
        val link = Link(idleReply = "3001100025800")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
        assertFalse(link.sent.contains("1100041802FF00"))
    }
    @Test fun cleanIdlePeerClosePreventsNewDiagnosticRequest() = runBlocking {
        val link = Link(idleReply = "300A8")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is ElmTp20Channel.PeerClosedChannel)
        assertFalse(link.sent.contains("1100041802FF00"))
    }
    @Test fun whollySilentRequestGetsOneIdenticalTransportSendRetry() = runBlocking {
        val link = Link(silentSends = 1)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9)
        assertEquals(listOf(0x50, 0x89), channel.request("1089"))
        assertEquals(2, link.sent.count { it == "1000021089" })
        assertFalse(link.sent.any { it == "1100021089" })
    }
    @Test fun whollySilentBothSendsAreBoundedWithoutNextDiagnosticRequest() = runBlocking {
        val link = Link(silentSends = 2)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9)
        assertTrue(runCatching { channel.request("1089") }.exceptionOrNull() is IOException)
        assertEquals(2, link.sent.count { it == "1000021089" })
        assertEquals("1000021089", link.sent.last())
    }
    @Test fun missingSequenceIsRecoveredByNackWithoutResendingDtcRequest() = runBlocking {
        val link = Link(dtcReply = "300B2\r3001200025800", nackReply = "3001100025800")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
        assertEquals(1, link.sent.count { it == "91" })
        assertEquals(1, link.sent.count { it == "1100041802FF00" })
    }
    @Test fun matchingCompletePendingCanResynchroniseSequenceBeforeFinalReply() = runBlocking {
        val link = Link(dtcReply = "300B2\r3001500037F1878")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
        assertTrue(link.sent.contains("B6"))
        assertTrue(link.sent.contains("B70"))
        assertEquals(1, link.sent.count { it == "1100041802FF00" })
    }
    @Test fun unrecoveredSequenceGapCannotBecomeHealthyNoApplicationResponse() = runBlocking {
        val link = Link(dtcReply = "300B2\r3001200025800")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        val failure = runCatching { channel.request("1802FF00") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, link.sent.count { it == "1100041802FF00" })
        assertTrue(link.sent.contains("91"))
        assertFalse(link.sent.contains("A80"))
    }
}
