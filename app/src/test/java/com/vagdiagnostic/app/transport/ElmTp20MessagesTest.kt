package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly as Wire
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic multi-application TP packets, with continuous sequence. */
class ElmTp20MessagesTest {
    private class Link(val tail: String = "normal", val dtc: Boolean = false) : ElmLink {
        override val label = "Synthetic multi-message stream"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() {}
        override suspend fun readByte(): Int = bytes.removeFirstOrNull() ?: -2
        override suspend fun write(data: ByteArray) {
            val command = data.toString(Charsets.US_ASCII).trim()
            sent += command
            val raw = when {
                command == "ATDPN" -> "6"
                command.startsWith("AT") -> "OK"
                command == "1FC000100003011" -> "21F00D00003000701"
                command == "A00194FF82FF" -> "300A10F94FF82FF"
                command == "1000021089" -> "300B1\r3001000025089"
                dtc && command == "1100041802FF00" -> "300B2\r3001180055801051924"
                command == "1100032204A1" || command == "1100041802FF00" ->
                    "300B2\r300218007" + (when (tail) {
                        "wrong-sid" -> "5804A1"
                        "wrong-did" -> "6204A2"
                        else -> "6204A1"
                    }) + "191F\r300120100"
                tail == "many" && command.matches(Regex("B[0-9A-F]")) ->
                    "300%02X80056201010100".format(0x10 or command[1].digitToInt(16))
                dtc && command == "B2" -> when (tail) {
                    "badDtc" -> "300120003580205"
                    else -> "3001200055801051824"
                }
                command == "B3" -> when (tail) {
                    "missing" -> "300A10F94FF82FF"
                    "foreign" -> "3011300056201010100"
                    "sequence" -> "3001400056201010100"
                    "unadvertised" -> "3001300056201010100\r30014000162"
                    else -> "3001300056201010100"
                }
                command == "A3" -> "300A10F94FF82FF"
                command == "B40" && tail == "extra-on-quiet" -> "30014000162"
                command == "B40" && tail == "keepalive-on-quiet" -> "300A3"
                command == "B40" && tail == "closed-on-quiet" -> "300A8"
                command == "A10194FF82FF" -> ""
                command == "A80" || command.matches(Regex("B[0-9A-F]0")) -> ""
                else -> error("Unexpected $command")
            }
            bytes.addAll((raw + ">").toByteArray().map { it.toInt() and 255 })
        }
    }
    @Test fun advertisedMessagesAreAcknowledgedWithReceiveWindowAndMerged() = runBlocking {
        val link = Link()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(0x1F); channel.request("1089")
        val messages = channel.requestMessages("2204A1")
        assertEquals(listOf("6204A1191F0100", "6201010100"), messages.map(Wire::hex))
        assertEquals(listOf(0x19, 1), Wire.installed04A1Messages(messages).map { it.id })
        assertTrue(link.sent.contains("B3"))
        assertFalse(link.sent.contains("B30"))
        assertEquals("B40", link.sent.last())
        assertEquals(0, link.sent.count { it == "A3" })
    }
    @Test fun missingOrCorruptContinuationStopsWithoutClosingOrResending() = runBlocking {
        for (tail in listOf("missing", "foreign", "sequence", "unadvertised", "many", "wrong-sid", "wrong-did", "extra-on-quiet")) {
            val link = Link(tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(0x1F); channel.request("1089")
            val failure = runCatching { channel.requestMessages("2204A1") }.exceptionOrNull()
            assertTrue("$tail: $failure", failure is IOException)
            assertEquals(1, link.sent.count { it == "1100032204A1" })
            assertFalse(link.sent.contains("A80"))
        }
    }
    @Test fun finalAckKeepsControlPacketsAndPeerCloseDoesNotDiscardCompleteReply() = runBlocking {
        for (tail in listOf("keepalive-on-quiet", "closed-on-quiet")) {
            val link = Link(tail)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(0x1F); channel.request("1089")
            val messages = channel.requestMessages("2204A1")
            assertEquals(listOf(0x19, 1), Wire.installed04A1Messages(messages).map { it.id })
            if (tail == "keepalive-on-quiet") assertEquals("A10194FF82FF", link.sent.last())
            else {
                val count = link.sent.size
                channel.close()
                assertEquals(count, link.sent.size)
            }
        }
    }
    @Test fun singleDtcRequestNeverAcceptsAdvertisedMultiReply() = runBlocking {
        val link = Link()
        val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
        channel.open(0x1F); channel.request("1089")
        assertTrue(runCatching { channel.request("1802FF00") }.exceptionOrNull() is IOException)
        assertFalse(link.sent.contains("B3"))
    }
    @Test fun dtcReadCollectsAllAdvertisedApplicationMessages() = runBlocking {
        for (tail in listOf("normal", "badDtc")) {
            val link = Link(tail, dtc = true)
            val channel = ElmTp20Channel(ElmEngine(link, { _, _, _, _ -> }))
            channel.open(0x1F); channel.request("1089")
            val replies = channel.requestMessages("1802FF00")
            val parsed = runCatching { Wire.kwpFaultMessages(replies, false) }
            if (tail == "normal") assertEquals(listOf("01305", "01304"), parsed.getOrThrow().values.map { it.code })
            else assertTrue(parsed.isFailure)
            assertTrue(link.sent.contains("B2"))
            assertEquals("B30", link.sent.last())
            assertEquals(1, link.sent.count { it == "1100041802FF00" })
        }
    }
}

