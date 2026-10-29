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
    private class UdsLink(val wrongId: Boolean = false, val pending: Int = 0, val identityReply: String = "7F2231") : ElmLink {
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
            if (command == "ATMA") {
                val payload = if (pending == 3) "037F1978" else "03590299"
                for (b in (id + payload + "\r").toByteArray(Charsets.US_ASCII)) queue.add(b.toInt() and 255)
                return
            }
            val reply = when {
                command == "ATZ" -> "ELM327 v1.5"
                command == "0100" -> "7E8064100BE3EA813"
                command == "ATDPN" -> "6"
                command.startsWith("AT") -> "OK"
                command == "1FC000100003011" -> "NO DATA"
                command == "1003" -> id + "025003"
                command == "1001" -> id + "025001"
                command == "2204A1" -> id + "076204A1191F0100"
                command == "22F187" -> if (identityReply.length / 2 <= 7)
                    id + "%02X".format(identityReply.length / 2) + identityReply
                else id + "10%02X".format(identityReply.length / 2) + identityReply.take(12) + "\r" +
                    id + "21" + identityReply.drop(12).padEnd(14, '0')
                command == "19028D" -> when (pending) {
                    1 -> id + "037F1978\r" + id + "03590299"
                    2, 3 -> id + "037F1978"
                    else -> id + "0359028D"
                }
                command == "" -> "STOPPED"
                else -> error("Unexpected UDS command $command")
            }
            for (b in (reply + ">").toByteArray(Charsets.US_ASCII)) queue.add(b.toInt() and 255)
        }
    }
    private class Link(val failRead: Boolean = false, val wrongSequence: Boolean = false, val keepalive: Boolean = false,
        val longList: Boolean = false, val rejectService1A: Boolean = false,
        val clock: () -> Long = { 0L }, val identityReply: String = "7F1A11", val failIdentity: Boolean = false) : ElmLink {
        override val label = "Scripted ELM"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        private val remaining = ArrayDeque<String>()
        private var destination = 0x1F
        private var receiveSeq = 0
        private var ackDue: Long? = null
        private fun nextFrame(): String {
            val frame = remaining.removeFirst()
            return frame + if (keepalive && longList && frame[4] == '7') "\r300A3" else ""
        }
        override suspend fun open() {}
        override suspend fun write(data: ByteArray) {
            val cmd = data.toString(Charsets.US_ASCII).trim()
            if (cmd.startsWith("B")) {
                check(clock() <= requireNotNull(ackDue)) { "ACK exceeded advertised T1" }
                ackDue = null
            }
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
                cmd == "A10194FF82FF" -> "NO DATA"
                cmd == "A80" || (cmd.length == 3 && cmd.startsWith("B")) -> "OK"
                cmd.length == 2 && cmd.startsWith("B") -> nextFrame()
                cmd.startsWith("1") -> {
                    if (failRead) throw IOException("Scripted disconnected stream")
                    val request = cmd.drop(6)
                    val reply = when (request) {
                        "1089" -> "5089"
                        "22F187" -> "7F2231"
                        "1A9B" -> if (failIdentity) throw IOException("identity stream lost") else identityReply
                        "1A9F" -> if (longList || rejectService1A) "7F1A11" else "5A9F090100000119000001"
                        "2204A1" -> if (rejectService1A) "6204A1191F010001010100" else "6204A1" + (1..65).joinToString("") { "%02X%02X0100".format(it, it) }
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
                    "300B%X\r".format((cmd.take(2).toInt(16) + 1) and 15) + nextFrame() +
                        if (keepalive && request == "1A9F") "\r300A3" else ""
                }
                else -> error("Unexpected wire command $cmd")
            }
            if (raw.split('\r').any { it.startsWith("3000") || it.startsWith("3001") }) ackDue = clock() + 200
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
    @Test fun explicitCancellationFromCheckpointStopsBothProtocolsImmediately() = runBlocking {
        for (uds in listOf(false, true)) {
            val tpLink = Link()
            val udsLink = UdsLink()
            val engine = ElmEngine(if (uds) udsLink else tpLink, { _, _, _, _ -> })
            engine.connect()
            var commandsAtCancel: List<String>? = null
            val failure = runCatching {
                ElmVagScan.scan(engine, { unit ->
                    if (unit.source == "RESPONSE") {
                        commandsAtCancel = (if (uds) udsLink.sent else tpLink.sent).toList()
                        throw CancellationException("Checkpoint cancelled scan")
                    }
                }, { _, _, _ -> })
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is CancellationException)
            assertNotNull(commandsAtCancel)
            assertEquals(commandsAtCancel, if (uds) udsLink.sent else tpLink.sent)
        }
    }
    @Test fun slowPersistenceCannotDelayTpAcknowledgmentsAndJournalIsFlushed() = runBlocking {
        var now = 0L
        val link = Link(clock = { now })
        val logged = mutableListOf<String>()
        val engine = ElmEngine(link, { command, _, _, _ -> logged += command; now += 250 })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
        assertTrue(result.note, result.complete)
        assertEquals(link.sent, logged)
        assertTrue(link.sent.contains("ATST0B"))
        assertFalse(link.sent.contains("ATST64"))
    }
    @Test fun keepaliveEmptyReplyDoesNotDiscardAlreadyQueuedListData() = runBlocking {
        val link = Link(keepalive = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
        assertTrue(result.note, result.listConfirmed)
        assertTrue(result.complete)
        assertTrue(link.sent.contains("A10194FF82FF"))
    }
    @Test fun udsPendingAndFinalInOneElmBlockAreOneTransaction() = runBlocking {
        val link = UdsLink(pending = 1)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
        assertTrue(result.note, result.complete)
        assertEquals(1, link.sent.count { it == "19028D" })
    }
    @Test fun synthetic263ByteListSurvivesRepeatedKeepaliveAndSequenceWrap() = runBlocking {
        val link = Link(keepalive = true, longList = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> })
        assertTrue(result.note, result.listConfirmed)
        assertEquals(65, units.size)
        assertEquals("GATEWAY", units.getValue("VAG-04").source)
        assertEquals(ReadState.NOT_QUERIED, units.getValue("VAG-04").state)
        assertTrue(link.sent.count { it == "A10194FF82FF" } >= 2)
        assertFalse(result.complete) // Unknown reported IDs have no invented route.
    }
    @Test fun pendingAfterPromptUsesReceiveOnlyAndClosesSessionAfterFinal() = runBlocking {
        val link = UdsLink(pending = 2)
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { cmd, _, _, _ -> journal += cmd })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
        assertTrue(result.note, result.complete)
        assertEquals(1, link.sent.count { it == "19028D" })
        val readIndex = link.sent.indexOf("19028D")
        assertEquals(listOf("ATMA", "", "1001"), link.sent.drop(readIndex + 1))
        assertTrue(journal.contains("ATMA"))
    }
    @Test fun repeatedPendingIsBoundedWithoutResendOrPrematureSessionClose() = runBlocking {
        val link = UdsLink(pending = 3)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        try { ElmVagScan.scan(engine, {}, { _, _, _ -> }); fail("pending limit expected") }
        catch (failure: IOException) { assertTrue(failure.message!!.contains("VAG-19")) }
        val afterRead = link.sent.drop(link.sent.indexOf("19028D") + 1)
        assertEquals(1, link.sent.count { it == "19028D" })
        assertEquals(8, afterRead.count { it == "ATMA" })
        assertFalse(afterRead.contains("1001"))
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
        try { ElmVagScan.scan(engine, {}, { _, _, _ -> }); fail("Expected strict address failure") }
        catch (_: IOException) { }
        assertFalse(link.sent.contains("19028D"))
        assertFalse(link.sent.contains("1001"))
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
    @Test fun gatewayIdentificationIsIndependentFromDtcAndList() = runBlocking {
        val reply = "5A9B" + ("1K0907530K  " + "0178").toByteArray().joinToString("") { "%02X".format(it) } + "00".repeat(10)
        for (identity in listOf(reply, "7F1A11", "7F1A33", "5A9B010203")) {
            val link = Link(identityReply = identity)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val events = mutableListOf<Pair<String, String>>()
            val units = mutableListOf<ControlUnit>()
            val outcome = ElmVagScan.scan(engine, { units += it }, { cmd, text, _ -> events += cmd to text }, readGatewayIdentity = true)
            assertTrue(outcome.note, outcome.complete)
            assertTrue(units.any { it.state == ReadState.COMPLETE })
            assertEquals(1, link.sent.count { it.endsWith("1A9B") })
            val metadata = events.single { it.first == "VAG_GATEWAY_IDENTITY" }.second
            assertEquals(identity == reply, metadata.contains("1K0907530K"))
            if (identity == reply) assertTrue(outcome.note.contains("0178"))
            assertEquals(identity == "7F1A11", link.sent.any { it.endsWith("22F187") })
        }
    }
    @Test fun gatewayServiceCapabilityIsIndependentOfItsTransport() = runBlocking {
        val link = Link(rejectService1A = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = ElmVagScan.scan(engine, {}, { _, _, _ -> }, readGatewayIdentity = true)
        assertTrue(result.note, result.listConfirmed)
        assertEquals(ElmVagScan.Protocol.TP20, result.protocol)
        assertTrue(link.sent.any { it.endsWith("22F187") })
        assertFalse(link.sent.any { it.endsWith("1A9B") })
        assertEquals(1, link.sent.count { it.endsWith("1A9F") })
    }
    @Test fun identityIoStopsWithoutSendingMoreCommandsOnBrokenStream() = runBlocking {
        val link = Link(failIdentity = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val saved = linkedMapOf<String, ControlUnit>()
        assertTrue(runCatching { ElmVagScan.scan(engine, { saved[it.address] = it }, { _, _, _ -> }, readGatewayIdentity = true) }.exceptionOrNull() is IOException)
        assertTrue(link.sent.last().endsWith("1A9B"))
        assertTrue(saved.isNotEmpty())
        assertTrue(saved.values.all { it.state == ReadState.COMPLETE })
    }
    @Test fun udsIdentificationUsesOnlyItsMatchingDidAndPreservesZeroDtc() = runBlocking {
        for (reply in listOf("62F187314B30", "7F2231", "62F187314B30393037353330")) {
            // Short printable data is an unknown identity, never a fabricated part.
            val link = UdsLink(identityReply = reply)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            engine.connect()
            val result = ElmVagScan.scan(engine, {}, { _, _, _ -> }, readGatewayIdentity = true)
            assertTrue(result.note, result.complete)
            assertTrue(link.sent.contains("22F187"))
            assertEquals(reply.endsWith("314B30393037353330"), result.note.contains("1K0907530"))
            assertFalse(link.sent.contains("22F190"))
        }
    }

}
