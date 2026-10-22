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
            val result = customReplies[cmd] ?: when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1" -> "OK"
                "ATDPN" -> "A6"
                "0100" -> "SEARCHING...\r7E8064100BE3EA813\r7E906410098188001"
                "03" -> "7E80443010299000000"
                "07" -> "7E802470000000000"
                "0A" -> "7E8024A0000000000"
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
            ObdDecoder.decodedMode(frames, mode, true)
        }
        assertTrue(fresh.commands.containsAll(listOf("0100", "03", "07", "0A")))
        assertFalse(scan.hasReactiveRecovery)
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

    @Test fun bluetoothDoesNotAcquireWifiSocketOnIoFailure() = runBlocking {
        val first = ScriptedLink()
        val live = engine(first)
        live.connect()
        first.failCommands.add("0100")
        var creates = 0
        val session = ElmScanSession(live, false, { creates++; engine(ScriptedLink()) }, { })
        try {
            session.read("0100", 9000)
            fail("Non-Wi-Fi link must propagate I/O error")
        } catch (_: IOException) { }
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
