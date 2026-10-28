package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Real observed prefixes plus synthetic delayed tails; not vehicle validation. */
class ElmTp20RecoveryTest {
    private class Link(val setup: String = "", val tail: String = "reply") : ElmLink {
        override val label = "Scripted TP recovery"
        val sent = mutableListOf<String>()
        val bytes = ArrayDeque<Int>()
        var polls = 0
        override suspend fun open() {}
        override fun close() {}
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val c = bytes.toString(Charsets.US_ASCII).trim()
            sent += c
            val raw = when {
                c == "ATDPN" -> "6"
                c.startsWith("AT") -> "OK"
                c == "09C000100003011" -> "20900D00003A80701"
                c == "A00194FF82FF" -> setup + "300A10F8AFF4AFF"
                c == "1000021089" -> "300B1\r3001000025089"
                c == "1100041802FF00" -> "300B2"
                c == "A3" -> {
                    polls++
                    if (tail == "io") throw IOException("lost stream")
                    if (tail == "cancel") throw CancellationException("stopped")
                    when {
                        tail == "foreign" -> "3011100025800"
                        polls == 1 -> "300A10F8AFF4AFF"
                        polls == 2 -> "NO DATA"
                        tail == "reply" -> "3001100025800"
                        else -> "300A10F8AFF4AFF"
                    }
                }
                c == "B10" || c == "B20" || c == "A80" -> ""
                else -> error("Unexpected command $c")
            }
            for (b in (raw + ">").toByteArray(Charsets.US_ASCII)) this.bytes.add(b.toInt() and 255)
        }
    }
    @Test fun exactSetupReplayDoesNotDiscardValidA1() = runBlocking {
        val link = Link("20900D00003A80701\r")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9)
        assertEquals(listOf(0x50, 0x89), channel.request("1089"))
        channel.close()
    }
    @Test fun changedOrMultipleSetupReplayStillFails() = runBlocking {
        for (row in listOf("20900D00003A90701\r", "20800D00003A80701\r",
            "20900D00003A80701\r20900D00003A80701\r")) {
            val channel = ElmTp20Channel(ElmEngine(Link(row), { _, _, _, _ -> }))
            assertTrue(row, runCatching { channel.open(9) }.isFailure)
        }
    }
    @Test fun delayedDtcAfterAckAndKeepalivesIsReceivedWithoutRepeatingRequest() = runBlocking {
        val link = Link()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9)
        channel.request("1089")
        assertTrue(VagOemReadOnly.kwpFaults(channel.request("1802FF00"), false).values.isEmpty())
        assertEquals(3, link.polls)
        assertEquals(1, link.sent.count { it == "1100041802FF00" })
        assertTrue(link.sent.contains("B20"))
    }
    @Test fun missingReplyHasFiniteBudgetAndNeverBecomesZeroFaults() = runBlocking {
        val link = Link(tail = "missing")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IllegalStateException)
        assertEquals(8, link.polls)
        assertEquals(1, link.sent.count { it == "1100041802FF00" })
    }
    @Test fun brokenStreamCancellationAndForeignIdStopImmediately() = runBlocking {
        for (tail in listOf("io", "cancel", "foreign")) {
            val link = Link(tail = tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            val error = runCatching { channel.request("1802FF00") }.exceptionOrNull()
            when (tail) {
                "io" -> assertTrue(error is IOException)
                "cancel" -> assertTrue(error is CancellationException)
                else -> assertTrue(error is IllegalStateException)
            }
            assertEquals(1, link.polls)
            assertEquals("A3", link.sent.last())
        }
    }
}
