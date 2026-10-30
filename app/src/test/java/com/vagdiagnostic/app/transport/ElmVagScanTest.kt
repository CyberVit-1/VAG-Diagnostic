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
    private class UdsLink(val wrongId: Boolean = false, val pending: Int = 0, val identityReply: String = "7F2231",
        val presenceReply: String = "7E00", val sessionReply: String = "5003", val bitmapReply: String? = null,
        val dtcReply: String? = null, val closeFailure: String? = null,
        val includeCentral: Boolean = false) : ElmLink {
        override val label = "Scripted UDS link"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        private var hasDtcReply = false
        private var receiveId = "77A"
        override suspend fun open() {}
        override fun close() { queue.clear() }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        private fun payload(id: String, hex: String): String {
            if (hex == "NO DATA") return hex
            val length = hex.length / 2
            if (length <= 7) return id + "%02X".format(length) + hex
            return id + "10%02X".format(length) + hex.take(12) + "\r" +
                hex.drop(12).chunked(14).mapIndexed { index, chunk ->
                    id + "2%X".format(index + 1) + chunk.padEnd(14, '0')
                }.joinToString("\r")
        }
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            if (command.startsWith("ATCRA")) receiveId = command.removePrefix("ATCRA")
            val id = if (wrongId) "77B" else receiveId
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
                command == "3E00" -> if (presenceReply == "NO DATA") "NO DATA" else
                    id + "%02X".format(presenceReply.length / 2) + presenceReply
                command == "1003" -> if (sessionReply == "NO DATA") "NO DATA" else
                    id + "%02X".format(sessionReply.length / 2) + sessionReply
                command == "1001" -> {
                    if (hasDtcReply && closeFailure == "io") throw IOException("Synthetic close failure")
                    if (hasDtcReply && closeFailure == "cancel") throw CancellationException("Synthetic close cancellation")
                    id + "025001"
                }
                command == "2204A1" -> payload(id, "6204A1191F0100" + if (includeCentral) "09200100" else "")
                command == "22F187" -> payload(id, identityReply)
                command == "222A26" -> payload(id, requireNotNull(bitmapReply))
                command == "19028D" -> { hasDtcReply = true; when (pending) {
                    1 -> id + "037F1978\r" + id + "03590299"
                    2, 3 -> id + "037F1978"
                    else -> payload(id, dtcReply ?: "59028D")
                } }
                command == "" -> "STOPPED"
                else -> error("Unexpected UDS command $command")
            }
            for (b in (reply + ">").toByteArray(Charsets.US_ASCII)) queue.add(b.toInt() and 255)
        }
    }
    private class Link(val failRead: Boolean = false, val wrongSequence: Boolean = false, val keepalive: Boolean = false,
        val longList: Boolean = false, val rejectService1A: Boolean = false,
        val clock: () -> Long = { 0L }, val identityReply: String = "7F1A11", val failIdentity: Boolean = false,
        val dtcReply: String? = null, val closeFailure: String? = null,
        val installedReply: String? = null, val missingSupplemental: Boolean = false,
        val failSupplemental: Boolean = false, val missingGateway: Boolean = false,
        val supplementalSetup: String? = null, val closedDestination: Int? = null,
        val closeAttempts: Int = 1, val failReset: Boolean = false,
        val recordedPrefix: List<Pair<String, String>> = emptyList()) : ElmLink {
        override val label = "Scripted ELM"
        val sent = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        private val remaining = ArrayDeque<String>()
        private var destination = 0x1F
        private var receiveSeq = 0
        private var ackDue: Long? = null
        private var hasDtcReply = false
        private var prefixIndex = 0
        private var closedAttempts = 0
        private var waitingForClose = false
        private var pollsBeforeClose = 0
        private var resets = 0
        private fun nextFrame(): String {
            val frame = remaining.removeFirst()
            return frame + if (keepalive && longList && frame[4] == '7') "\r300A3" else ""
        }
        override suspend fun open() {}
        override suspend fun write(data: ByteArray) {
            val cmd = data.toString(Charsets.US_ASCII).trim()
            if (prefixIndex < recordedPrefix.size) {
                val row = recordedPrefix[prefixIndex++]
                check(cmd == row.first) { "Recorded row $prefixIndex: expected ${row.first}, got $cmd" }
                sent += cmd
                (row.second + ">").toByteArray(Charsets.US_ASCII).forEach { bytes.add(it.toInt() and 255) }
                return
            }
            if (cmd.startsWith("B")) {
                check(clock() <= requireNotNull(ackDue)) { "ACK exceeded advertised T1" }
                ackDue = null
            }
            sent += cmd
            val raw = when {
                cmd == "ATZ" -> {
                    resets++
                    if (failReset && resets > 1) throw IOException("Synthetic reset failed")
                    "ELM327 v1.5"
                }
                cmd == "0100" -> "7E8064100BE3EA813"
                cmd == "ATDPN" -> "6"
                cmd.startsWith("AT") -> "OK"
                cmd.endsWith("C000100003011") -> {
                    destination = cmd.take(2).toInt(16); receiveSeq = 0
                    hasDtcReply = false
                    if (destination == 0x33 && supplementalSetup != null) supplementalSetup
                    else if ((destination == 0x33 && missingSupplemental) || (destination == 0x1F && missingGateway)) "NO DATA"
                    else "%03X00D00003000701".format(0x200 or destination)
                }
                cmd == "A00194FF82FF" -> "300A10F94FF82FF"
                cmd == "A10194FF82FF" -> "NO DATA"
                cmd == "A3" && waitingForClose -> {
                    pollsBeforeClose++
                    if (pollsBeforeClose < 4) "300A10F94FF82FF" else {
                        waitingForClose = false
                        "300A10F94FF82FF\r300A8"
                    }
                }
                cmd == "A80" || (cmd.length == 3 && cmd.startsWith("B")) -> {
                    if (cmd == "A80" && hasDtcReply && closeFailure == "io") throw IOException("Synthetic close failure")
                    if (cmd == "A80" && hasDtcReply && closeFailure == "cancel") throw CancellationException("Synthetic close cancellation")
                    "OK"
                }
                cmd.length == 2 && cmd.startsWith("B") -> nextFrame()
                cmd.startsWith("1") -> {
                    if (failRead) throw IOException("Scripted disconnected stream")
                    val request = cmd.drop(6)
                    if (request in setOf("1800FF00", "1802FF00") && destination == closedDestination && closedAttempts < closeAttempts) {
                        closedAttempts++
                        pollsBeforeClose = 0
                        waitingForClose = true
                        val ack = "300B%X>".format((cmd.take(2).toInt(16) + 1) and 15)
                        ack.toByteArray(Charsets.US_ASCII).forEach { bytes.add(it.toInt() and 255) }
                        return
                    }
                    if (request in setOf("1800FF00", "1802FF00")) hasDtcReply = true
                    val reply = when (request) {
                        "1089" -> "5089"
                        "22F187" -> "7F2231"
                        "1A9B" -> if (failIdentity || (destination == 0x33 && failSupplemental)) throw IOException("identity stream lost") else identityReply
                        "1A9F" -> installedReply ?: if (longList || rejectService1A) "7F1A11" else "5A9F090100000119000001"
                        "2204A1" -> if (rejectService1A) "6204A1191F010001010100" else "6204A1" + (1..65).joinToString("") { "%02X%02X0100".format(it, it) }
                        "1800FF00" -> dtcReply ?: "5801029908"
                        "1802FF00" -> dtcReply ?: "5800"
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
    @Test fun udsPresenceIsIndependentOfExtendedSessionAcceptance() = runBlocking {
        for (presence in listOf("7E00", "7F3E11")) for (session in listOf("NO DATA", "7F1012", "5003")) {
            val link = UdsLink(presenceReply = presence, sessionReply = session)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            val result = ElmVagScan.scan(engine, {}, { _, _, _ -> })
            assertTrue("$presence/$session: ${result.note}", result.complete)
            assertTrue(link.sent.indexOf("3E00") < link.sent.indexOf("1003"))
            assertTrue(link.sent.contains("19028D"))
            assertEquals(session == "5003", link.sent.contains("1001"))
        }
    }
    @Test fun ambiguousPresenceNeverAdvancesAndAllSilentProbesDoNotReadDtc() = runBlocking {
        for (presence in listOf("NO DATA", "7F1011", "7E01", "7F3E78")) {
            val link = UdsLink(presenceReply = presence, sessionReply = "NO DATA", identityReply = "NO DATA")
            val engine = ElmEngine(link, { _, _, _, _ -> })
            val result = runCatching { ElmVagScan.scan(engine, {}, { _, _, _ -> }) }
            if (presence == "NO DATA") assertFalse(result.getOrThrow().complete)
            else assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(presence == "NO DATA", link.sent.contains("1003"))
            assertFalse(link.sent.contains("19028D"))
            assertFalse(link.sent.contains("1001"))
        }
    }
    @Test fun ignoredTesterPresentCanEstablishPresenceBySessionOrPartRead() = runBlocking {
        for (session in listOf("5003", "7F1012", "NO DATA")) {
            val link = UdsLink(presenceReply = "NO DATA", sessionReply = session,
                identityReply = partReply("1K0907530"))
            val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), {}, { _, _, _ -> })
            assertTrue(result.note, result.complete)
            assertTrue(link.sent.contains("19028D"))
            assertEquals(if (session == "NO DATA") 2 else 1, link.sent.count { it == "22F187" })
            assertEquals(session == "5003", link.sent.contains("1001"))
            assertTrue(link.sent.containsAll(listOf("ATFCSH710", "ATFCSD300010", "ATFCSM1")))
        }
    }
    @Test fun peerClosedEcuIsRetriedAfterOtherUnitsOnFreshConnection() = runBlocking {
        for (closes in listOf(1, 2)) {
            val link = Link(installedReply = "5A9F0D190000016200000172000001", closedDestination = 0x24, closeAttempts = closes)
            val units = linkedMapOf<String, ControlUnit>()
            val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
            assertEquals(closes == 1, result.complete)
            assertEquals(ReadState.COMPLETE, units.getValue("VAG-72").state)
            assertEquals(if (closes == 1) ReadState.COMPLETE else ReadState.PARTIAL, units.getValue("VAG-62").state)
            assertEquals(2, link.sent.count { it == "24C000100003011" })
            val retry = link.sent.lastIndexOf("24C000100003011")
            assertTrue(link.sent.indexOf("25C000100003011") < retry)
            assertEquals(closes, link.sent.count { it == "ATZ" })
            assertTrue(link.sent.contains("0100"))
        }
    }
    @Test fun failureDuringRecoveryStopsBeforeTheNextEcu() = runBlocking {
        val link = Link(installedReply = "5A9F0D190000016200000172000001", closedDestination = 0x24,
            closeAttempts = 2, failReset = true)
        val units = linkedMapOf<String, ControlUnit>()
        // Start once to make the subsequent recovery reset the failing one.
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        val result = runCatching { ElmVagScan.scan(engine, { units[it.address] = it }, { _, _, _ -> }) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("ATZ", link.sent.last())
        assertFalse(link.sent.contains("25C000100003011"))
        assertEquals(ReadState.COMPLETE, units.getValue("VAG-19").state)
    }
    @Test fun recorded036CloseAndResetContinueQueueWithExplicitSyntheticTail() = runBlocking {
        val recorded = javaClass.getResourceAsStream("/replay/vehicle-036-oem.tsv")!!.bufferedReader().useLines { lines ->
            lines.map { line -> val fields = line.split('\t', limit = 2)
                fields[0] to String(java.util.Base64.getDecoder().decode(fields[1]), Charsets.UTF_8)
            }.toList()
        }
        val probes = recorded.flatMap { row ->
            if (row.first == "3E00" && row.second.trim() == "NO DATA")
                listOf(row, "1003" to "NO DATA", "22F187" to "NO DATA") else listOf(row)
        }
        // Original traffic through the close and OBD restoration; all later
        // replies come from Link and are explicitly synthetic, including retry.
        val prefix = withExplicitPhysicalFlowControl(probes)
        val link = Link(recordedPrefix = prefix)
        val units = linkedMapOf<String, ControlUnit>()
        val outcome = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }),
            { units[it.address] = it }, { _, _, _ -> }, discoverAdditionalUnits = false)
        assertTrue(outcome.listConfirmed)
        assertFalse(outcome.complete) // Real04/46 remain unconfirmed.
        assertEquals(18, units.size)
        assertEquals(listOf("01305", "01304"), units.getValue("VAG-19").dtcs.map { it.code })
        for (id in listOf("62", "72", "16", "08")) assertEquals(id, ReadState.COMPLETE, units.getValue("VAG-$id").state)
        assertEquals(2, link.sent.count { it == "24C000100003011" })
        assertEquals(1, link.sent.count { it == "ATZ" }) // Actual recorded recovery.
    }
    private fun partReply(part: String) = "62F187" + VagOemReadOnly.hex(part.map { it.code })
    @Test fun validatedPartNumberSelectsTheActualInstallationFormat() = runBlocking {
        for ((part, bitmap) in listOf(
            "5Q0907530" to ("01" + "00".repeat(31)),
            "4G0907468" to ("000008" + "00".repeat(6)))) {
            val link = UdsLink(identityReply = partReply(part), bitmapReply = "622A26$bitmap")
            val units = linkedMapOf<String, ControlUnit>()
            val outcome = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> },
                allowCandidateFallback = false, readGatewayIdentity = true)
            assertTrue(outcome.note, outcome.complete)
            assertEquals(setOf("VAG-19"), units.keys)
            assertTrue(link.sent.indexOf("22F187") < link.sent.indexOf("222A26"))
            assertFalse(link.sent.contains("2204A1"))
            assertEquals(1, link.sent.count { it == "22F187" })
        }
    }
    @Test fun unknownPositionsAndMissingAdditionalListsKeepCoverageIncomplete() = runBlocking {
        for ((part, bitmap) in listOf(
            "5Q0907530" to ("01" + "00".repeat(30) + "01"),
            "5WA907530" to ("01" + "00".repeat(31)))) {
            val link = UdsLink(identityReply = partReply(part), bitmapReply = "622A26$bitmap")
            val units = linkedMapOf<String, ControlUnit>()
            val outcome = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> },
                allowCandidateFallback = false)
            assertTrue(outcome.listConfirmed)
            assertFalse(outcome.complete)
            assertEquals(setOf("VAG-19"), units.keys)
            assertEquals(ReadState.COMPLETE, units.getValue("VAG-19").state)
        }
    }
    @Test fun malformedBitmapDoesNotTurnIntoEmptyInstalledList() = runBlocking {
        for (bitmap in listOf("622A26" + "00".repeat(31), "622A28" + "00".repeat(32), "7F2231")) {
            val link = UdsLink(identityReply = partReply("5Q0907530"), bitmapReply = bitmap)
            val units = mutableListOf<ControlUnit>()
            val result = runCatching { ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units += it }, { _, _, _ -> },
                allowCandidateFallback = false) }
            if (bitmap.startsWith("622A28")) {
                assertTrue(result.exceptionOrNull() is IOException)
                assertEquals("222A26", link.sent.last())
            } else {
                assertFalse(result.getOrThrow().listConfirmed)
                assertFalse(result.getOrThrow().complete)
            }
            assertTrue(units.isEmpty())
            assertFalse(link.sent.contains("19028D"))
        }
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
        for (identity in listOf(reply, "7F1A10", "7F1A11", "7F1A12", "7F1A31", "7F1A33", "5A9B010203")) {
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
            assertEquals(identity in setOf("7F1A10", "7F1A11", "7F1A12"), link.sent.any { it.endsWith("22F187") })
        }
    }
    @Test fun completedDtcRejectionsRemainPartialWithoutSwitchingProtocol() = runBlocking {
        for (reply in listOf("7F1811", "7F1822", "7F1831", "7F1833")) {
            val link = Link(dtcReply = reply)
            val units = linkedMapOf<String, ControlUnit>()
            val outcome = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
            assertFalse(outcome.complete)
            assertTrue(units.values.all { it.state == ReadState.PARTIAL && it.dtcs.isEmpty() })
            assertFalse(link.sent.contains("ATSH710"))
            assertFalse(link.sent.contains("19028D"))
        }
    }
    @Test fun completeDtcSurvivesFailureOrCancellationDuringSessionTeardown() = runBlocking {
        for (uds in listOf(false, true)) for (mode in listOf("io", "cancel")) {
            val tp = Link(dtcReply = "5801051924", closeFailure = mode)
            val iso = UdsLink(dtcReply = "59028D12345608", closeFailure = mode)
            val units = linkedMapOf<String, ControlUnit>()
            val failure = runCatching {
                ElmVagScan.scan(ElmEngine(if (uds) iso else tp, { _, _, _, _ -> }),
                    { units[it.address] = it }, { _, _, _ -> })
            }.exceptionOrNull()
            if (mode == "io") assertTrue(failure.toString(), failure is IOException)
            else assertTrue(failure.toString(), failure is CancellationException)
            val saved = units.getValue("VAG-19")
            assertEquals(ReadState.COMPLETE, saved.state)
            assertEquals(if (uds) "123456" else "01305", saved.dtcs.single().code)
            assertTrue(saved.answeredModes.contains(DtcKind.OEM))
            assertEquals(setOf("VAG-19"), units.keys)
            assertEquals(if (uds) "1001" else "A80", (if (uds) iso.sent else tp.sent).last())
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
    private fun centralIdentity(part: String) = "5A9B" + VagOemReadOnly.hex(
        (part.padEnd(12) + "0001").map { it.code } + List(10) { 0 })
    @Test fun additionalEcuRequiresResponseAndThenReadsItsActualFaults() = runBlocking {
        for (identity in listOf(centralIdentity("1K0937087"), "7F1A31")) {
            val link = Link(installedReply = "5A9F091900000109000001", identityReply = identity)
            val units = linkedMapOf<String, ControlUnit>()
            val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }),
                { units[it.address] = it }, { _, _, _ -> })
            assertTrue(result.note, result.complete)
            assertEquals(setOf("VAG-19", "VAG-09", "VAG-61"), units.keys)
            assertEquals(ReadState.COMPLETE, units.getValue("VAG-61").state)
            assertEquals("RESPONSE", units.getValue("VAG-61").source)
            assertEquals(2, link.sent.count { it == "33C000100003011" })
            assertFalse(link.sent.contains("ATSH17FC009C"))
            val probe = link.sent.indexOf("33C000100003011")
            assertEquals(2, link.sent.take(probe).count { it.endsWith("1802FF00") })
            assertTrue(link.sent.drop(probe).any { it.endsWith("1802FF00") })
        }
    }
    @Test fun additionalEcuNoResponseDoesNotInventAUnit() = runBlocking {
        val link = Link(installedReply = "5A9F091900000109000001",
            identityReply = centralIdentity("1K0937087"), missingSupplemental = true)
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
        assertTrue(result.note, result.complete)
        assertFalse(units.containsKey("VAG-61"))
        assertEquals(1, link.sent.count { it == "33C000100003011" })
        assertFalse(link.sent.contains("ATSH17FC009C"))
    }
    @Test fun supplementalTpEcuDoesNotInheritGatewayUdsProtocol() = runBlocking {
        val tp = Link(missingGateway = true, identityReply = "7F1A31", dtcReply = "5801051924")
        val uds = UdsLink(includeCentral = true, identityReply = partReply("1K0937087"))
        val sent = mutableListOf<String>()
        val mixed = object : ElmLink {
            override val label = "Synthetic mixed-protocol vehicle"
            var rawCan = false
            var last: ElmLink = uds
            override suspend fun open() {}
            override fun close() { tp.close(); uds.close() }
            override suspend fun readByte() = last.readByte()
            override suspend fun write(bytes: ByteArray) {
                val command = bytes.toString(Charsets.US_ASCII).trim()
                sent += command
                if (command == "ATCAF0") rawCan = true
                if (command == "ATCAF1") rawCan = false
                last = if (rawCan) tp else uds
                last.write(bytes)
            }
        }
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(mixed, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
        assertEquals(ElmVagScan.Protocol.UDS, result.protocol)
        assertTrue(result.note, result.complete)
        val extra = units.getValue("VAG-61")
        assertEquals(ReadState.COMPLETE, extra.state)
        assertEquals("01305", extra.dtcs.single().code)
        assertEquals("RESPONSE", extra.source)
        assertEquals(2, sent.count { it == "33C000100003011" })
        assertFalse(sent.contains("ATSHFC009C"))
        assertEquals(2, sent.count { it == "19028D" })
    }
    @Test fun centralPartExcludesLegacyProcedureOnUdsOnlyChassis() = runBlocking {
        for (prefix in listOf("1EA", "2Q0", "2Q1", "2Q5", "2QW", "4N0", "5Q0", "5Q1", "5WA", "6C0", "8W0", "8W1", "971")) {
            val link = Link(installedReply = "5A9F091900000109000001", identityReply = centralIdentity(prefix + "937087"))
            val units = linkedMapOf<String, ControlUnit>()
            val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
            assertTrue(result.note, result.complete)
            assertFalse(units.containsKey("VAG-61"))
            assertFalse(link.sent.contains("33C000100003011"))
        }
    }
    @Test fun additionalProcedureSkipsAnAlreadyListedEcu() = runBlocking {
        val link = Link(installedReply = "5A9F0D190000010900000161000001")
        val units = linkedMapOf<String, ControlUnit>()
        val result = ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }), { units[it.address] = it }, { _, _, _ -> })
        assertTrue(result.note, result.complete)
        assertEquals(1, link.sent.count { it == "33C000100003011" })
        assertFalse(link.sent.any { it.endsWith("1A9B") })
    }
    @Test fun additionalDiscoveryIoPreservesEarlierDtcAndStopsTraffic() = runBlocking {
        val link = Link(installedReply = "5A9F091900000109000001",
            identityReply = centralIdentity("1K0937087"), failSupplemental = true)
        val units = linkedMapOf<String, ControlUnit>()
        val error = runCatching { ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }),
            { units[it.address] = it }, { _, _, _ -> }) }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals(setOf("VAG-19", "VAG-09"), units.keys)
        assertTrue(units.values.all { it.state == ReadState.COMPLETE })
        assertTrue(link.sent.last().endsWith("1A9B"))
        assertEquals(1, link.sent.count { it == "33C000100003011" })
    }
    @Test fun malformedSupplementalSetupCannotBecomeAbsenceOrProtocolFallback() = runBlocking {
        for (setup in listOf("23400D00003000701", "23300D00003", "233A10F94FF82FF")) {
            val link = Link(installedReply = "5A9F091900000109000001",
                identityReply = centralIdentity("1K0937087"), supplementalSetup = setup)
            val units = linkedMapOf<String, ControlUnit>()
            val error = runCatching { ElmVagScan.scan(ElmEngine(link, { _, _, _, _ -> }),
                { units[it.address] = it }, { _, _, _ -> }) }.exceptionOrNull()
            assertTrue("$setup: $error", error is IOException)
            assertEquals("33C000100003011", link.sent.last())
            assertFalse(units.containsKey("VAG-61"))
            assertTrue(units.values.all { it.state == ReadState.COMPLETE })
        }
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
