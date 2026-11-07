package com.vagdiagnostic.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class ElmTp20SetupRejectionTest {
    private class Link(vararg replies: String, val parameterReplies: List<String> = listOf("300A10F94FF82FF")) : ElmLink {
        override val label = "Synthetic setup rejection"
        val sent = mutableListOf<String>()
        val setups = ArrayDeque(replies.toList())
        private var parameterIndex = 0
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() {}
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val raw = when {
                cmd == "ATDPN" -> "6"
                cmd.startsWith("AT") -> "OK"
                cmd == "20C000100003011" -> setups.removeFirst()
                cmd == "A00194FF82FF" -> parameterReplies[(parameterIndex++).coerceAtMost(parameterReplies.lastIndex)]
                cmd == "A80" -> "NO DATA"
                else -> error("Unexpected $cmd")
            }
            (raw + ">").toByteArray().forEach { this.bytes.add(it.toInt() and 255) }
        }
    }
    private val rejected = "22000D70003380301"
    private val accepted = "22000D00003380301"

    @Test fun temporaryRejectionRetriesOnlySetupWithBackoffThenNegotiates() = runBlocking {
        val link = Link(rejected, accepted)
        val waits = mutableListOf<Long>()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), setupBackoff = { waits += it })
        channel.open(0x20)
        assertEquals(listOf(2000L), waits)
        assertEquals(2, link.sent.count { it == "20C000100003011" })
        assertEquals(1, link.sent.count { it == "A00194FF82FF" })
        assertTrue(link.sent.contains("ATSH338"))
    }
    @Test fun lastNegativeWithValidPairStillRequiresPositiveA1() = runBlocking {
        val link = Link(rejected, rejected, rejected, rejected)
        val waits = mutableListOf<Long>()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), setupBackoff = { waits += it })
        channel.open(0x20)
        assertEquals(listOf(2000L, 2000L, 2000L, 2000L), waits)
        assertEquals(4, link.sent.count { it == "20C000100003011" })
        assertEquals(1, link.sent.count { it == "A00194FF82FF" })
        assertFalse(link.sent.any { it.endsWith("1089") })
    }
    @Test fun invalidPairInCompleteNegativeRemainsLocalAfterFourSetups() = runBlocking {
        for (opcode in listOf(0xD6, 0xD8)) {
            val link = Link(*Array(4) { "22000%02X0010001001".format(opcode) })
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), setupBackoff = { error("Unexpected retry") })
            val error = runCatching { channel.open(0x20) }.exceptionOrNull()
            assertTrue(error is ElmTp20Channel.ChannelRejected)
            assertEquals(opcode, (error as ElmTp20Channel.ChannelRejected).opcode)
            channel.close()
            assertEquals("20C000100003011", link.sent.last())
            assertEquals(4, link.sent.count { it == "20C000100003011" })
        }
    }
    @Test fun silenceRetriesSetupFourTimesWithoutInventingChannel() = runBlocking {
        val link = Link("NO DATA", "NO DATA", "NO DATA", "NO DATA")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        assertTrue(runCatching { channel.open(0x20) }.exceptionOrNull() is ElmTp20Channel.NoChannelResponse)
        assertEquals(4, link.sent.count { it == "20C000100003011" })
        channel.close()
        assertEquals("20C000100003011", link.sent.last())
    }
    @Test fun missingParametersRetryFourTimesThenDisconnectBeforeNewSetup() = runBlocking {
        val link = Link(accepted, accepted, parameterReplies = List(4) { "NO DATA" } + "300A10F94FF82FF")
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(0x20)
        assertEquals(5, link.sent.count { it == "A00194FF82FF" })
        assertEquals(2, link.sent.count { it == "20C000100003011" })
        val close = link.sent.indexOf("A80")
        assertTrue(close > link.sent.indexOf("A00194FF82FF"))
        assertTrue(close < link.sent.lastIndexOf("20C000100003011"))
        assertFalse(link.sent.any { it.endsWith("1089") })
    }
    @Test fun bothParameterRoundsExhaustWithoutSendingDiagnosticRequests() = runBlocking {
        val link = Link(accepted, accepted, parameterReplies = listOf("NO DATA"))
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        assertTrue(runCatching { channel.open(0x20) }.exceptionOrNull() is ElmTp20Channel.ParametersUnavailable)
        assertEquals(8, link.sent.count { it == "A00194FF82FF" })
        assertEquals(2, link.sent.count { it == "A80" })
        assertEquals("A80", link.sent.last())
    }
    @Test fun malformedForeignAndAmbiguousNegativeRepliesAreStillFatal() = runBlocking {
        for (raw in listOf("22100D70003380301", "22000D700033803", "22001D70003380301",
            "22000D70003380300", "$rejected\r$rejected", "22000D90003380301")) {
            val link = Link(raw)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), setupBackoff = { error("Unexpected retry") })
            assertTrue(raw, runCatching { channel.open(0x20) }.exceptionOrNull() is IOException)
            assertEquals("20C000100003011", link.sent.last())
        }
    }
    @Test fun cancellationDuringBackoffStopsBeforeSecondSetup() = runBlocking {
        val link = Link(rejected, accepted)
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }), setupBackoff = { throw CancellationException("Stopped") })
        assertTrue(runCatching { channel.open(0x20) }.exceptionOrNull() is CancellationException)
        assertEquals(1, link.sent.count { it == "20C000100003011" })
        channel.close()
        assertEquals("20C000100003011", link.sent.last())
    }
}
