package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.DtcKind
import com.vagdiagnostic.app.core.ElmFrames
import com.vagdiagnostic.app.core.ObdDecoder
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Offline tests of a full scripted ELM command channel, NOT physical car tests. */
class ElmScanSessionTest {
    private class ScriptedLink(
        val failCommands: MutableSet<String> = mutableSetOf(),
        val closeChannelCommands: MutableSet<String> = mutableSetOf(),
        val dropPromptCommands: MutableSet<String> = mutableSetOf(),
        val customReplies: Map<String, String> = emptyMap()
    ) : ElmLink {
        override val label = "Scripted ELM"
        var opened = false
        var closed = false
        val commands = mutableListOf<String>()
        private val bytes = ArrayDeque<Int>()
        override suspend fun open() { opened = true; closed = false }
        override suspend fun write(bytes: ByteArray) {
            if (closed || !opened) throw IOException("Already closed")
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            commands.add(cmd)
            if (failCommands.remove(cmd)) throw IOException("Broken pipe")
            if (closeChannelCommands.remove(cmd))
                throw kotlinx.coroutines.channels.ClosedReceiveChannelException("BLE GATT closed")
            val result = customReplies[cmd] ?: when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1" -> "OK"
                "ATDPN" -> "A6"
                "0900" -> "7E806490040000000"
                "0902" -> "7E81014490201313233\r7E82134353637383941\r7E82242434445464748"
                "0100" -> "SEARCHING...\r7E8064100BE3EA813\r7E906410098188001"
                "03" -> "7E80443010299000000\r7E9024300"
                "07" -> "7E802470000000000\r7E9024700"
                "0A" -> "7E8024A0000000000\r7E9024A00"
                else -> error("Unexpected test command: $cmd")
            }
            val payload = result + if (dropPromptCommands.remove(cmd)) "" else ">"
            payload.toByteArray(Charsets.US_ASCII).forEach { this.bytes.addLast(it.toInt() and 255) }
        }
        override suspend fun readByte(): Int = bytes.pollFirst() ?: -2
        override fun close() { closed = true; bytes.clear() }
    }

    private fun engine(link: ScriptedLink, time: () -> Long = System::nanoTime) =
        ElmEngine(link, { _, _, _, _ -> }, time)

    @Test fun idleConnectionIsReopenedBeforeScanAndDtcModesProceed() = runBlocking {
        var now = 1_000_000_000L
        val stale = ScriptedLink()
        val current = engine(stale) { now }
        current.connect()
        assertTrue(current.vehicleReady)
        now += 22_000_000_000L

        val fresh = ScriptedLink()
        val events = mutableListOf<String>()
        val scan = ElmScanSession(current, true, { engine(fresh) { now } },
            { e -> events.add(if (e == null) "closed" else "ready") })
        scan.prepare()
        assertTrue(stale.closed)
        assertEquals(listOf("closed", "ready"), events)
        val discovery = ElmFrames.parse("0100", scan.read("0100", 9000))
        assertTrue(ObdDecoder.hasValidVehicleResponse(discovery))
        assertEquals(setOf("7E8", "7E9"), discovery.messagesFor(0x41).keys)
        for (mode in DtcKind.entries) {
            val frames = ElmFrames.parse(mode.command, scan.read(mode.command, 8500))
            assertTrue("Invalid ${mode.command}: ${frames.problems}", frames.problems.isEmpty())
            val assessment = ObdDecoder.assessMode(frames, mode, scan.current.isCanVehicle,
                setOf("7E8", "7E9"))
            assertTrue("Mode ${mode.command} not fully confirmed: ${assessment.problems}",
                assessment.complete)
            assertEquals(setOf("7E8", "7E9"), assessment.confirmed.keys)
            if (mode == DtcKind.CONFIRMED) {
                assertEquals(listOf("P0299"), assessment.confirmed["7E8"]?.map { it.code })
            }
        }
        assertTrue(fresh.commands.containsAll(listOf("0100", "03", "07", "0A")))
        assertFalse(scan.hasReactiveRecovery)
    }

    @Test fun identicalReadOnlyDtcSequenceWorksAcrossTransportKinds() = runBlocking {
        for (kind in listOf("WIFI", "BT_CLASSIC", "BLE_NUS")) {
            val link = ScriptedLink()
            val engine = engine(link)
            engine.connect()
            assertTrue("$kind did not confirm OBD-II", engine.vehicleReady)
            val session = ElmScanSession(
                engine, wifi = kind == "WIFI",
                newEngine = { engine(ScriptedLink()) },
                onEngineChanged = { },
                transportTag = kind
            )
            session.prepare()
            val discovery = ElmFrames.parse("0100", session.read("0100", 9000))
            assertTrue("$kind: invalid 0100", ObdDecoder.hasValidVehicleResponse(discovery))
            val ecus = discovery.messagesFor(0x41).keys
            assertEquals(setOf("7E8", "7E9"), ecus)
            for (mode in DtcKind.entries) {
                val raw = session.read(mode.command, 8500)
                val result = ObdDecoder.assessMode(
                    ElmFrames.parse(mode.command, raw),
                    mode, session.current.isCanVehicle, ecus
                )
                assertTrue("$kind: mode ${mode.command}: ${result.problems}",
                    result.complete)
            }
            assertEquals("No transport-specific diagnostic commands allowed: $kind",
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1",
                    "ATSP0", "ATAT1", "0100", "ATDPN", "0100", "03", "07", "0A"),
                link.commands)
        }
    }

    @Test fun reactiveRecoveryAndNoFurtherInfiniteReconnects() = runBlocking {
        val first = ScriptedLink()
        val live = engine(first)
        live.connect()
        first.failCommands.add("0100")
        val retry = ScriptedLink()
        var creates = 0
        val session = ElmScanSession(live, true, {
            creates++
            engine(retry)
        }, { })
        val frames = ElmFrames.parse("0100", session.read("0100", 9000))
        assertTrue(ObdDecoder.hasValidVehicleResponse(frames))
        assertEquals(1, creates)
        assertTrue(first.closed)
        retry.failCommands.add("03")
        try {
            session.read("03", 8500)
            fail("A second failure must propagate")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Broken pipe"))
        }
        assertEquals(1, creates)
        assertTrue(session.hasReactiveRecovery)
    }

    @Test fun readOnlyMode09CapabilityIsAvailableOnAllThreeELMTransports() = runBlocking {
        for (kind in listOf("WIFI", "BT_CLASSIC", "BLE_NUS")) {
            val stream = ScriptedLink()
            val original = engine(stream)
            original.connect()
            val session = ElmScanSession(
                original, kind == "WIFI", { engine(ScriptedLink()) }, { },
                transportTag = kind
            )
            val known = setOf("7E8", "7E9")
            val supported = com.vagdiagnostic.app.core.ObdMode09Readiness.supportedVinEcus(
                ElmFrames.parse("0900", session.read("0900", 9500)), known
            )
            assertEquals(setOf("7E8"), supported.responders)
            val check = com.vagdiagnostic.app.core.ObdMode09Readiness.assessVin(
                ElmFrames.parse("0902", session.read("0902", 13500)),
                supported.responders
            )
            assertTrue("$kind: ${check.issues}", check.confirmed)
            assertTrue(stream.commands.containsAll(listOf("0900", "0902")))
            assertFalse(stream.commands.any { it == "14" || it.startsWith("19") })
        }
    }

    @Test fun bluetoothClassicUsesFreshSPPLinkAfterIoErrorOnly() = runBlocking {
        var now = 1_000_000_000L
        val first = ScriptedLink()
        val live = engine(first) { now }
        live.connect()
        now += 60_000_000_000L
        val fallback = ScriptedLink()
        var opens = 0
        val events = mutableListOf<String>()
        val session = ElmScanSession(
            live, false, { opens++; engine(fallback) { now } }, { },
            onEvent = { event, _ -> events.add(event) }, transportTag = "BT_CLASSIC"
        )
        session.prepare() // Non-Wi-Fi never reconnects merely due to idle time.
        assertEquals(0, opens)
        assertFalse(first.closed)
        first.failCommands.add("0100")
        val response = ElmFrames.parse("0100", session.read("0100", 9000))
        assertTrue(ObdDecoder.hasValidVehicleResponse(response))
        assertEquals(1, opens)
        assertTrue(first.closed)
        assertTrue(session.hasReactiveRecovery)
        assertTrue(events.contains("BT_CLASSIC_IO_RECOVERY"))
        assertTrue(events.contains("BT_CLASSIC_SESSION_RESTORED"))
    }

    @Test fun bleNusRecoveryIsBoundedToOneAttempt() = runBlocking {
        val link = ScriptedLink()
        val first = engine(link)
        first.connect()
        val replacement = ScriptedLink()
        var reconnects = 0
        val session = ElmScanSession(
            first, false, { reconnects++; engine(replacement) }, { },
            transportTag = "BLE_NUS"
        )
        link.failCommands.add("03")
        assertTrue(session.read("03", 8500).isNotBlank())
        replacement.failCommands.add("07")
        try {
            session.read("07", 8500)
            fail("Second BLE I/O failure must propagate")
        } catch (_: IOException) {}
        assertEquals(1, reconnects)
        assertTrue(session.hasReactiveRecovery)
    }

    @Test fun bleClosedGATTNotificationStreamTriggersReadOnlyRecovery() = runBlocking {
        val link = ScriptedLink(closeChannelCommands = mutableSetOf())
        val first = engine(link)
        first.connect()
        link.closeChannelCommands.add("07")
        var reconnects = 0
        val next = ScriptedLink()
        val session = ElmScanSession(first, false, {
            reconnects++
            engine(next)
        }, { }, transportTag = "BLE_NUS")
        assertTrue(session.read("07", 8500).isNotBlank())
        assertEquals(1, reconnects)
        assertTrue(link.closed)
    }

    @Test fun bluetoothReadOnlyRecoveryNeverRetriesWriteOrUnknownCommands() = runBlocking {
        val first = ScriptedLink()
        val engine = engine(first)
        engine.connect()
        var creates = 0
        val session = ElmScanSession(engine, false,
            { creates++; engine(ScriptedLink()) }, { }, transportTag = "BT_CLASSIC")
        try {
            session.read("14", 8500) // ClearDTC is not read-only!
            fail("Unknown/mutating OBD command must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, creates)
        assertFalse(session.hasReactiveRecovery)
    }

    @Test fun failedIdleReopenLeavesUiDisconnectedAndJournalsCause() = runBlocking {
        var now = 1_000_000_000L
        val old = ScriptedLink()
        val first = engine(old) { now }
        first.connect()
        now += 21_000_000_000L

        val failing = ScriptedLink(mutableSetOf("ATZ"))
        val states = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        val session = ElmScanSession(first, true, { engine(failing) { now } },
            { e -> states.add(e?.vehicleReady == true) },
            onEvent = { event, _ -> events.add(event) })
        try {
            session.prepare()
            fail("Adapter failing at ATZ must not be treated as vehicle ready")
        } catch (_: IOException) { }
        assertTrue(old.closed)
        assertTrue(failing.closed)
        assertEquals(listOf(false), states)
        assertTrue(events.contains("WIFI_IDLE_REFRESH"))
        assertTrue(events.contains("WIFI_SESSION_RESTORE_FAILED"))
    }

    @Test fun unsupportedAdaptiveTimingAndProtocolQueryStillConfirmVehicle() = runBlocking {
        val link = ScriptedLink(customReplies = mapOf("ATAT1" to "?", "ATDPN" to "?"))
        val e = engine(link)
        e.connect()
        assertTrue(e.vehicleReady)
        assertTrue(e.adapterReady)
        assertEquals("Не определён", e.protocol)
    }

    @Test fun unknownAtdpnStillIdentifiesCanFromRealVehicleEcuHeaders() = runBlocking {
        val link = ScriptedLink(customReplies = mapOf("ATDPN" to "?"))
        val e = engine(link)
        e.connect()
        assertTrue(e.vehicleReady)
        assertEquals(true, e.isCanVehicle)
    }

    @Test fun observedCanEcuHeadersOverrideInconsistentCloneProtocolReport() = runBlocking {
        val elm = engine(ScriptedLink(customReplies = mapOf("ATDPN" to "A1")))
        elm.connect()
        assertTrue(elm.vehicleReady)
        assertEquals("A1", elm.protocol)
        assertEquals(true, elm.isCanVehicle)
    }

    @Test fun automaticProtocolZeroNeverForcesLegacyDtcLayout() = runBlocking {
        val elm = engine(ScriptedLink(customReplies = mapOf("ATDPN" to "A0")))
        elm.connect()
        assertTrue(elm.vehicleReady)
        assertEquals(true, elm.isCanVehicle)
    }

    @Test fun noProtocolAndNoCanIdCannotAssignDtcFormat() = runBlocking {
        val link = ScriptedLink(customReplies = mapOf(
            "ATDPN" to "?",
            "0100" to "4100BE3EA813"
        ))
        val e = engine(link)
        e.connect()
        assertTrue(e.vehicleReady)
        assertEquals(null, e.isCanVehicle)
    }

    @Test fun missingAdaptiveTimingPromptFailsClosedRatherThanCorruptingNextObdQuery() = runBlocking {
        val link = ScriptedLink(dropPromptCommands = mutableSetOf("ATAT1"))
        val statuses = mutableListOf<String>()
        val e = ElmEngine(link, { _, _, _, result -> statuses.add(result) })
        try {
            e.connect()
            fail("ATAT1 timeout must not be ignored")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("Тайм-аут"))
        }
        assertTrue(link.closed)
        assertFalse(e.vehicleReady)
        assertFalse(link.commands.contains("0100"))
        assertTrue(statuses.any { it.startsWith("TIMEOUT") })
    }

    @Test fun timeoutBecomesLoggedIOExceptionNotCancelledScan() = runBlocking {
        val io = ScriptedLink()
        val statuses = mutableListOf<String>()
        val e = ElmEngine(io, { _, _, _, status -> statuses.add(status) })
        e.connect()
        io.dropPromptCommands.add("0100")
        try {
            e.command("0100", 60)
            fail("No prompt must fail with timeout")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("Тайм-аут"))
        }
        assertTrue(statuses.last().startsWith("TIMEOUT"))
    }
}
