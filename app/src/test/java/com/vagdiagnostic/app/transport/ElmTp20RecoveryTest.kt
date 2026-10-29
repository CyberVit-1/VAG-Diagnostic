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
        var pendingSeq = 2
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
                c == "1100041802FF00" -> if (tail.startsWith("pending"))
                    "300B2\r3001100037F" + (if (tail == "pending-wrong") "1A" else "18") + "78" +
                    (if (tail == "pending-coalesced") "\r3001200025800" else "") else "300B2"
                c == "A3" -> {
                    polls++
                    if (tail == "io" || tail == "pending-io") throw IOException("lost stream")
                    if (tail == "cancel" || tail == "pending-cancel") throw CancellationException("stopped")
                    when {
                        tail == "pending-many" -> "3001%X00037F1878".format((pendingSeq++ and 15))
                        tail == "foreign" || tail == "pending-foreign" -> "3011100025800"
                        polls == 1 -> "300A10F8AFF4AFF"
                        polls == 2 -> "NO DATA"
                        tail == "pending" -> "3001200025800"
                        tail == "reply" -> "3001100025800"
                        else -> "300A10F8AFF4AFF"
                    }
                }
                (c.startsWith("B") && c.length == 3) || c == "A80" -> ""
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
        assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
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
                else -> assertTrue(error is IOException)
            }
            assertEquals(1, link.polls)
            assertEquals("A3", link.sent.last())
        }
    }
    @Test fun pendingKeepsSessionUntilFinalWithoutResendingDiagnosticRequest() = runBlocking {
        for (tail in listOf("pending", "pending-coalesced")) {
        val link = Link(tail = tail)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(9); channel.request("1089")
        assertEquals(listOf(0x58, 0), channel.request("1802FF00"))
        assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
        assertTrue(link.sent.contains("B20"))
        assertTrue(link.sent.contains("B30"))
        assertFalse(link.sent.contains("A80"))
        }
    }
    @Test fun mismatchedOrExcessPendingAbortsAsUnfinishedTransaction() = runBlocking {
        for (tail in listOf("pending-wrong", "pending-many")) {
            val link = Link(tail = tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
            assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
            assertFalse(link.sent.contains("A80"))
        }
    }

    @Test fun pendingIdleAndOverallDeadlinesStopWithoutAnotherApplicationCommand() = runBlocking {
        for ((tail, tick) in listOf("pending-never" to 1000L, "pending-many" to 3000L)) {
            val link = Link(tail = tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), { link.polls * tick * 1_000_000 })
            channel.open(9); channel.request("1089")
            assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
            assertTrue(link.polls <= 7)
            assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
            assertFalse(link.sent.contains("A80"))
        }
    }

    @Test fun pendingMalformedFramesIoAndCancellationCannotStartAnotherProtocol() = runBlocking {
        for (tail in listOf("pending-foreign", "pending-io", "pending-cancel")) {
            val link = Link(tail = tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(9); channel.request("1089")
            val error = runCatching { channel.request("1802FF00") }.exceptionOrNull()
            if (tail == "pending-cancel") assertTrue(error is CancellationException)
            else assertTrue(error is IOException)
            assertEquals("A3", link.sent.last())
            assertEquals(1, link.sent.count { it.endsWith("1802FF00") })
            assertFalse(link.sent.contains("A80"))
        }
    }

}
