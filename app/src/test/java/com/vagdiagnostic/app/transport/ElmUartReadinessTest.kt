package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scripted byte streams; not a physical BLE result. */
class ElmUartReadinessTest {
    private class Link(override val needsUartReadinessProbe: Boolean, val probeReply: String = "OK",
        val holdProbe: Boolean = false) : ElmLink {
        override val label = "Scripted UART readiness"
        val sent = mutableListOf<String>()
        val probeStarted = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<Unit>()
        private val queue = ArrayDeque<Int>()
        var closes = 0
        override suspend fun open() { opened.complete(Unit) }
        override fun close() { closes++; queue.clear() }
        override suspend fun readByte(): Int {
            if (holdProbe && sent == listOf("ATE0")) awaitCancellation()
            return queue.pollFirst() ?: -2
        }
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val raw = when {
                command == "ATE0" && sent.size == 1 -> { probeStarted.complete(Unit); probeReply }
                command == "ATZ" -> "ELM327 v1.5"
                command == "0100" -> "7E8064100BE3EA813"
                command == "ATDPN" -> "A6"
                else -> "OK"
            }
            queue.addAll((raw + "\r>").map { it.code })
        }
    }

    @Test fun bleUartMustAnswerBeforeResetAndVehicleConfirmation() = runBlocking<Unit> {
        val link = Link(true)
        val journal = mutableListOf<String>()
        val engine = ElmEngine(link, { command, _, _, _ -> journal += command })
        engine.connect()
        assertEquals(listOf("ATE0", "ATZ", "ATE0"), link.sent.take(3))
        assertEquals(link.sent, journal)
        assertTrue(engine.vehicleReady)
        engine.close()
    }

    @Test fun otherTransportsStillStartWithReset() = runBlocking<Unit> {
        val link = Link(false)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        engine.connect()
        assertEquals("ATZ", link.sent.first())
        assertTrue(engine.vehicleReady)
        engine.close()
    }

    @Test fun completedProbeRefusalOrMixedStatusCannotReachReset() = runBlocking<Unit> {
        for (reply in listOf("?", "NO DATA", "OK\rNO DATA")) {
            val link = Link(true, reply)
            val engine = ElmEngine(link, { _, _, _, _ -> })
            assertTrue(runCatching { engine.connect() }.exceptionOrNull() is IOException)
            assertEquals(listOf("ATE0"), link.sent)
            assertTrue(link.closes > 0)
            assertFalse(engine.adapterReady)
            assertFalse(engine.vehicleReady)
        }
    }

    @Test fun cancellationDuringProbeClosesLinkWithoutReset() = runBlocking<Unit> {
        val link = Link(true, holdProbe = true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        val opening = launch { engine.connect() }
        // On the old path ATZ was first; fail finitely instead of waiting for a probe it never sent.
        withTimeout(1_000) { while (link.sent.isEmpty()) yield() }
        assertEquals("ATE0", link.sent.first())
        link.probeStarted.await()
        assertFalse(engine.adapterReady)
        opening.cancelAndJoin()
        assertEquals(listOf("ATE0"), link.sent)
        assertTrue(link.closes > 0)
        assertFalse(engine.vehicleReady)
    }

    @Test fun cancellationDuringSettlingSendsNoAdapterCommand() = runBlocking<Unit> {
        val link = Link(true)
        val engine = ElmEngine(link, { _, _, _, _ -> })
        val opening = launch { engine.connect() }
        link.opened.await()
        opening.cancelAndJoin()
        assertTrue(link.sent.isEmpty())
        assertTrue(link.closes > 0)
        assertFalse(engine.adapterReady)
    }
}
