package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Synthetic raw-CAN responses; no hardware capability is implied. */
class ElmUdsRawTransactionTest {
    private class Link(vararg replies: Pair<String, String>) : ElmLink {
        override val label = "Synthetic raw UDS"
        val script = ArrayDeque(replies.toList())
        val sent = mutableListOf<String>()
        val bytes = ArrayDeque<Int>()
        var closed = false
        var rowDelayMs = 0L
        override suspend fun open() { }
        override suspend fun readByte(): Int {
            if (bytes.isEmpty()) return -2
            val next = bytes.removeFirst()
            if (next == '\r'.code && bytes.isNotEmpty() && rowDelayMs > 0) delay(rowDelayMs)
            return next
        }
        override fun close() { closed = true }
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val response = when {
                script.peekFirst()?.first == command -> script.removeFirst().second
                command == "ATZ" -> "ELM327"
                command == "ATDPN" -> "6"
                command.startsWith("AT") -> "OK"
                command == "0100" -> "7E8064100BE3EA813"
                else -> {
                    val expected = script.removeFirst()
                    assertEquals(expected.first, command)
                    expected.second
                }
            }
            val end = if (command == "ATMA") "\r" else "\r>"
            for (b in (response + end).toByteArray()) this.bytes.addLast(b.toInt() and 255)
        }
    }
    private val gateway = VagOemReadOnly.routes.getValue(0x19)
    @Test fun connectionSessionProbeAndOrdinarySessionReadKeepDistinctWireContracts() = runBlocking {
        val link = Link("0210031" to "0210031\r77A025003", "021003AAAAAAAAAA" to "77A025003")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x50, 3), ElmUdsRawTransaction.read(engine, gateway, "1003", sessionProbe = true))
        assertEquals(listOf(0x50, 3), ElmUdsRawTransaction.read(engine, gateway, "1003"))
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun pendingSessionProbeReceivesItsFinalReplyWithoutResending() = runBlocking {
        val link = Link("0210031" to "77A037F1078", "ATMA" to "77A025003", "" to "STOPPED")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x50, 3), ElmUdsRawTransaction.read(engine, gateway, "1003",
            automaticFlowControl = true, sessionProbe = true))
        assertEquals(1, link.sent.count { it == "0210031" })
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun dtcReadsCannotUseTheConnectionProbeResponseHint() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val before = link.sent.toList()
        assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, "1902AF", sessionProbe = true) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ElmUdsNativeDriver.read(engine, gateway, "1902AF", 5250, sessionProbe = true) }
            .exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, link.sent)
    }
    @Test fun maximumDtcReplyWithSixteenMillisecondFrameSpacingCompletesWithinTheTransactionBudget() = runBlocking {
        val data = listOf(0x59, 2, 0x8D) + List(4092) { it and 255 }
        val rows = mutableListOf("77A1FFF" + data.take(6).joinToString("") { "%02X".format(it) })
        data.drop(6).chunked(7).forEachIndexed { index, chunk ->
            rows += "77A2%X".format((index + 1) and 15) + chunk.joinToString("") { "%02X".format(it) }.padEnd(14, 'A')
        }
        val link = Link("031902AFAAAAAAAA" to rows.joinToString("\r"))
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        // Synthetic STmin=0x10 pacing; no measured vehicle timing is implied.
        link.rowDelayMs = 16
        val reply = requireNotNull(ElmUdsRawTransaction.read(engine, gateway, "1902AF", automaticFlowControl = true))
        assertEquals(data, reply)
        assertEquals(1023, VagOemReadOnly.udsFaults(reply).values.size)
        assertEquals(1, link.sent.count { it == "031902AFAAAAAAAA" })
        assertFalse(link.closed)
    }
    @Test fun presenceUsesOnlyItsShortFrameAndOneResponseHint() = runBlocking {
        val link = Link("023E001" to "023E001\r77A027E00")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x7E, 0), ElmUdsRawTransaction.read(engine, gateway, "3E00"))
        assertEquals(1, link.sent.count { it == "023E001" })
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun shortPresencePendingContinuesReceivingWithoutResending() = runBlocking {
        val link = Link("023E001" to "77A037F3E78", "ATMA" to "77A027E00", "" to "STOPPED")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x7E, 0), ElmUdsRawTransaction.read(engine, gateway, "3E00", automaticFlowControl = true))
        assertEquals(listOf("023E001", "ATCFC0", "ATMA", "", "ATCFC1"),
            link.sent.dropWhile { it != "023E001" })
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun shortPresenceSilenceAndNegativeAreNotSuccessfulPresence() = runBlocking {
        for ((raw, expected) in listOf("NO DATA" to null, "77A037F3E31" to listOf(0x7F, 0x3E, 0x31))) {
            val link = Link("023E001" to raw)
            val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            assertEquals(expected, ElmUdsRawTransaction.read(engine, gateway, "3E00"))
            assertFalse(link.closed)
        }
    }
    @Test fun shortPresenceRejectsForeignOwnerAndContradictoryStatuses() = runBlocking {
        for (raw in listOf("7E8027E00", "NO DATA\r77A027E00", "OK\r77A027E00", "?\r77A027E00")) {
            val link = Link("023E001" to raw)
            val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, "3E00") }.exceptionOrNull() is VagProtocolFailure)
            assertTrue(link.closed)
            assertEquals(1, link.sent.count { it == "023E001" })
        }
    }
    @Test fun rawSingleFrameIncludesPciAndIsReadWithoutAutomaticFormatting() = runBlocking {
        val link = Link("031902AFAAAAAAAA" to "77A0359028D")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x59, 2, 0x8D), ElmUdsRawTransaction.read(engine, gateway, "1902AF"))
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun firstFrameRequiresOneExplicitFlowControlBeforeConsecutiveFrame() = runBlocking {
        val link = Link("031902AFAAAAAAAA" to "77A100859028D010203",
            "300010AAAAAAAAAA" to "77A210405AAAAAAAAAA")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x59, 2, 0x8D, 1, 2, 3, 4, 5), ElmUdsRawTransaction.read(engine, gateway, "1902AF"))
        assertEquals(1, link.sent.count { it == ElmUdsRawTransaction.FLOW_CONTROL })
        assertTrue(link.script.isEmpty())
    }
    @Test fun completePendingThenFinalNeverResendsTheRequest() = runBlocking {
        val link = Link("031902AFAAAAAAAA" to "77A037F1978\r77A0359028D")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x59, 2, 0x8D), ElmUdsRawTransaction.read(engine, gateway, "1902AF"))
        assertEquals(1, link.sent.count { it == "031902AFAAAAAAAA" })
    }
    @Test fun completedNoDataAndFinalNegativeRemainDistinct() = runBlocking {
        for ((raw, expected) in listOf("NO DATA" to null, "77A037F1931" to listOf(0x7F, 0x19, 0x31))) {
            val link = Link("031902AFAAAAAAAA" to raw)
            val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            assertEquals(expected, ElmUdsRawTransaction.read(engine, gateway, "1902AF"))
            assertFalse(link.closed)
        }
    }
    @Test fun foreignOwnerAndWrongSequenceCannotProduceAnEmptySuccessfulReply() = runBlocking {
        for (script in listOf(arrayOf("031902AFAAAAAAAA" to "7E80359028D"),
            arrayOf("031902AFAAAAAAAA" to "77A100859028D010203", "300010AAAAAAAAAA" to "77A220405AAAAAAAAAA"))) {
            val link = Link(*script)
            val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, "1902AF") }.exceptionOrNull() is VagProtocolFailure)
            assertTrue(link.closed)
        }
    }
    @Test fun writeServicesAreRejectedBeforeAnyFrameIsSent() = runBlocking {
        val link = Link()
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val before = link.sent.toList()
        for (request in listOf("14FFFFFF", "2701", "2EF19000", "3101FF00")) {
            assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, request) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { ElmUdsNativeDriver.read(engine, gateway, request, 5250) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(before, link.sent)
    }
    @Test fun lateFirstFrameAfterPendingUsesManualFcOnlyAfterMonitorStops() = runBlocking {
        val link = Link("031902AFAAAAAAAA" to "77A037F1978",
            "ATMA" to "77A100859028D010203", "" to "STOPPED",
            "300010AAAAAAAAAA" to "77A210405AAAAAAAAAA")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x59, 2, 0x8D, 1, 2, 3, 4, 5),
            ElmUdsRawTransaction.read(engine, gateway, "1902AF", automaticFlowControl = true))
        val start = link.sent.indexOf("031902AFAAAAAAAA")
        assertEquals(listOf("031902AFAAAAAAAA", "ATCFC0", "ATMA", "", "300010AAAAAAAAAA", "ATCFC1"), link.sent.drop(start))
        assertTrue(link.script.isEmpty())
        assertFalse(link.closed)
    }
    @Test fun rawTwentyNineBitReplyRequiresItsExactOwner() = runBlocking {
        val route = VagOemReadOnly.routes.values.first { it.is29Bit }
        val rx = "%08X".format(requireNotNull(route.udsRx))
        val link = Link("031902AFAAAAAAAA" to (rx + "0359028D"))
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertEquals(listOf(0x59, 2, 0x8D), ElmUdsRawTransaction.read(engine, route, "1902AF"))
    }
    @Test fun exactQuestionMarkLeavesKnownPromptBoundaryForCapabilityFallback() = runBlocking {
        val link = Link("023E001" to "?")
        val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, "3E00") }.exceptionOrNull()
            is ElmUdsRawTransaction.UnsupportedCommand)
        assertFalse(link.closed)
        assertTrue(engine.vehicleReady)
        assertEquals("OK", engine.command("ATCAF1").trim())
    }

    @Test fun mixedAdapterStatusAndPayloadCannotBecomeZeroDtcs() = runBlocking {
        for (status in listOf("NO DATA", "CAN ERROR", "OK", "STOPPED", "?")) {
            val link = Link("031902AFAAAAAAAA" to (status + "\r77A0359028D"))
            val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            assertTrue(runCatching { ElmUdsRawTransaction.read(engine, gateway, "1902AF") }.exceptionOrNull() is VagProtocolFailure)
            assertTrue(link.closed)
        }
    }
}
