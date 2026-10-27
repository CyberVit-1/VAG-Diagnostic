package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Scripted transport contract, never a vehicle-confirmed result. */
class ElmVagScanTest {
    private class UdsLink(val wrongId: Boolean = false) : ElmLink {
        override val label = "Scripted UDS link"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() { queue.clear() }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val id = if (wrongId) "77B" else "77A"
            val reply = when {
                command == "ATZ" -> "ELM327 v1.5"
                command == "0100" -> "7E8064100BE3EA813"
                command == "ATDPN" -> "6"
                command.startsWith("AT") -> "OK"
                command == "1FC000100003011" -> "NO DATA"
                command == "1003" -> id + "025003"
                command == "1001" -> id + "025001"
                command == "2204A1" -> id + "076204A119000001"
                command == "19028D" -> id + "0359028D"
                else -> error("Unexpected UDS command $command")
            }
            for (b in (reply + ">").toByteArray(Charsets.US_ASCII)) queue.add(b.toInt() and 255)
        }
    }
    private class Link(val failRead: Boolean = false, val wrongSequence: Boolean = false) : ElmLink {
        override val label = "Scripted ELM"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        private val remaining = ArrayDeque<String>()
        private var destination = 0x1F
        private var receiveSeq = 0
        override suspend fun open() {}
        override suspend fun write(data: ByteArray) {
            val cmd = data.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val raw = when {
                cmd == "ATZ" -> "ELM327 v1.5"
                cmd == "0100" -> "7E8064100BE3EA813"
                cmd == "ATDPN" -> "6"
                cmd.startsWith("AT") -> "OK"
                cmd.endsWith("C000100003011") -> {
                    destination = cmd.take(2).toInt(16); receiveSeq = 0
                    "%03X00D00003000701".format(0x200 or destination)
                }
                cmd == "A00194FF82FF" -> "300A10F94FF82FF"
                cmd == "A80" || (cmd.length == 3 && cmd.startsWith("B")) -> "OK"
                cmd.length == 2 && cmd.startsWith("B") -> remaining.removeFirst()
                cmd.startsWith("1") -> {
                    if (failRead) throw IOException("Scripted disconnected stream")
                    val request = cmd.drop(6)
                    val reply = when (request) {
                        "1089" -> "5089"
                        "1A9F" -> "5A9F090100000119000001"
                        "1800FF00" -> "5801029908"
                        "1802FF00" -> "5800"
                        else -> error("Unexpected service $request")
                    }
                    remaining.clear()
                    var body = VagOemReadOnly.bytes(reply)
                    val total = body.size
                    var first = true
                    while (body.isNotEmpty()) {
                        val take = if (first) 5 else 7
                        val chunk = body.take(take); body = body.drop(take)
                        val seq = if (wrongSequence) (receiveSeq + 1) and 15 else receiveSeq
                        val frame = listOf((if (body.isEmpty()) 0x10 else 0x00) or seq) +
                            (if (first) listOf(total ushr 8, total and 255) else emptyList()) + chunk
                        remaining.add("300" + VagOemReadOnly.hex(frame))
                        first = false; receiveSeq = (receiveSeq + 1) and 15
                    }
                    "300B%X\r".format((cmd.take(2).toInt(16) + 1) and 15) + remaining.removeFirst()
                }
                else -> error("Unexpected wire command $cmd")
            }
            for (b in (raw + ">").toByteArray(Charsets.US_ASCII)) bytes.add(b.toInt() and 255)
        }
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override fun close() { bytes.clear() }
    }
    @Test fun completeGatewayListAndPerEcuDtcUseRecoveredAddressAndAck() = runBlocking {
        for (name in listOf("Wi-Fi", "SPP", "BLE")) {
            val link = Link()
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val units = linkedMapOf<String, ControlUnit>()
            val result = ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> })
            assertTrue(name + result.note, result.complete)
            assertEquals(setOf("VAG-19", "VAG-01"), units.keys)
            assertEquals("P0299", units.getValue("VAG-01").dtcs.single().code)
            assertTrue(link.sent.contains("ATCRA21F"))
            assertTrue(link.sent.contains("1FC000100003011"))
            assertFalse(link.sent.any { it == "ATCRA219" || it == "19C000100003011" || it == "ATSPB" })
            assertTrue(link.sent.count { it == "A80" } >= 3)
            assertTrue(link.sent.any { it.startsWith("B") && it.length == 2 })
            assertFalse(link.sent.any { it == "04" || it.startsWith("27") || it.startsWith("2E") })
        }
    }
    @Test fun ioFailureAbortsWithoutFallbackOrDisconnectCommandsOnBrokenStream() = runBlocking {
        val link = Link(failRead = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        try { ElmVagScan.scan(engine, {}, { _, _, _ -> }); fail("Expected IOException") }
        catch (_: IOException) { }
        assertEquals("1000021089", link.sent.last())
        assertFalse(link.sent.contains("ATSH710"))
    }
    @Test fun wrongSequenceIsNeverAcceptedAsZeroDtcs() = runBlocking {
        val link = Link(wrongSequence = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val channel = ElmTp20Channel(engine)
        channel.open(0x1F)
        assertTrue(runCatching { channel.request("1089") }.isFailure)
    }
    @Test fun unexpectedRawAddressAndIncompletePayloadFailClosed() {
        assertTrue(runCatching { ElmTp20Channel.frames("21900D00003000701", 0x21F, "") }.isFailure)
        assertTrue(runCatching { ElmTp20Channel.frames("NO DATA", 0x21F, "") }.isFailure)
        assertEquals(200L, ElmTp20Channel.timingMs(0x94))
        assertEquals(20L, ElmTp20Channel.timingMs(0x82))
        assertTrue(runCatching { ElmTp20Channel.timingMs(0xFF) }.isFailure)
    }
    @Test fun recoveredUdsEquivalentIsTriedAfterCompletedTpNoDataAndReturnsToDefaultSession() = runBlocking {
        val link = UdsLink()
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val units = mutableListOf<ControlUnit>()
        val result = ElmVagScan.scan(engine, { units += it }, { _, _, _ -> })
        assertEquals(ElmVagScan.Protocol.UDS, result.protocol)
        assertTrue(result.note, result.complete)
        assertTrue(units.last().dtcs.isEmpty())
        assertEquals(ReadState.COMPLETE, units.last().state)
        assertEquals(2, link.sent.count { it == "1001" })
        assertTrue(link.sent.indexOf("ATSH710") > link.sent.indexOf("1FC000100003011"))
    }
    @Test fun udsResponseFromAnotherAddressNeverConfirmsGateway() = runBlocking {
        val link = UdsLink(wrongId = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
        assertNull(result.protocol)
        assertFalse(result.complete)
        assertFalse(result.listConfirmed)
        assertFalse(link.sent.contains("19028D"))
    }
    @Test fun cancellationDuringProgressDoesNotStartNextEcu() = runBlocking {
        val link = Link()
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        try {
            ElmVagScan.scan(engine, { throw CancellationException("Scripted Stop") }, { _, _, _ -> })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, link.sent.count { it == "1FC000100003011" })
        assertFalse(link.sent.contains("01C000100003011"))
    }
}
