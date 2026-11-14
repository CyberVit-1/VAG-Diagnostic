package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Synthetic adapter capabilities; no phone or vehicle is involved. */
class ElmUdsRequestTimingTest {
    private class Link : ElmLink {
        override val label = "Synthetic timeout adapter"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        var extendedReply = "OK"
        var genericReply = "OK"
        var extendedFailure: Throwable? = null
        override suspend fun open() {}
        override fun close() { queue.clear() }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            if (command.startsWith("STPTO") || command.startsWith("VTTOST REP:")) extendedFailure?.let { throw it }
            val reply = when {
                command == "ATZ" -> "ELM327 v1.5"
                command == "0100" -> "7E8064100BE3EA813"
                command == "ATDPN" -> "6"
                command.startsWith("STPTO") || command.startsWith("VTTOST REP:") -> extendedReply
                command == "ATSTFA" -> genericReply
                else -> "OK"
            }
            (reply + "\r>").toByteArray().forEach { queue.add(it.toInt() and 255) }
        }
    }
    private fun attach(engine: ElmEngine): ElmNativeSegmentation =
        ElmNativeSegmentation(ElmNativeSegmentation.Info(1170, 5, 6, 5)) { engine.command(it) }
            .also { engine.nativeSegmentation = it }

    @Test fun longTimingWorksWithoutEnablingNativeAssemblyAndExternalSettingsInvalidateIt() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val adapter = attach(engine)
        ElmUdsRequestTiming.configure(engine, "3E00")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        assertEquals(1, link.sent.count { it == "STPTO1000" })
        assertEquals(1, link.sent.count { it == "STPTO2000" })
        engine.command("ATST28")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        engine.command("ATSP7")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        assertEquals(3, link.sent.count { it == "STPTO2000" })
        assertFalse(adapter.enabled)
        assertFalse(link.sent.any { it.startsWith("STCSEG") })
    }

    @Test fun exactCapabilityRefusalUsesConfirmedGenericTimingOnceUntilReset() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val adapter = attach(engine); link.extendedReply = "?"
        ElmUdsRequestTiming.configure(engine, "3E00")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        assertEquals(1, link.sent.count { it.startsWith("STPTO") })
        assertEquals(1, link.sent.count { it == "ATSTFA" })
        link.extendedReply = "OK"
        engine.command("ATZ")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        assertEquals(2, link.sent.count { it.startsWith("STPTO") })
        assertEquals("STPTO2000", link.sent.last())
        assertFalse(adapter.enabled)
    }

    @Test fun ambiguousTimeoutReplyCannotEnableFallbackOrAnotherTransaction() = runBlocking {
        for (raw in listOf("", "NO DATA", "OK\r?", "BUFFER FULL")) {
            val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            val adapter = attach(engine); link.extendedReply = raw
            assertTrue(runCatching { ElmUdsRequestTiming.configure(engine, "1902AF") }.exceptionOrNull() is VagProtocolFailure)
            assertFalse(link.sent.contains("ATSTFA"))
            val after = link.sent.toList()
            assertTrue(runCatching { adapter.enableRoute(VagOemReadOnly.routes.getValue(0x19)) }.isFailure)
            assertEquals(after, link.sent)
        }
    }

    @Test fun anActiveNativeFormatRequiresItsTimerAcknowledgement() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        val adapter = attach(engine)
        assertTrue(adapter.enableRoute(VagOemReadOnly.routes.getValue(0x19)))
        link.extendedReply = "?"
        assertTrue(runCatching { ElmUdsRequestTiming.configure(engine, "1902AF") }.exceptionOrNull() is VagProtocolFailure)
        assertFalse(link.sent.contains("ATSTFA"))
    }

    @Test fun genericOneSecondWindowRequiresARealAcknowledgement() = runBlocking {
        val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
        ElmUdsRequestTiming.configure(engine, "3E00")
        ElmUdsRequestTiming.configure(engine, "1902AF")
        assertEquals(1, link.sent.count { it == "ATSTFA" })
        assertFalse(link.sent.any { it.startsWith("STPTO") })
        engine.command("ATST28"); link.genericReply = "?"
        assertTrue(runCatching { ElmUdsRequestTiming.configure(engine, "1902AF") }.isFailure)
    }
    @Test fun cancellationAndEofDoNotBecomeAnUnsupportedCapability() = runBlocking {
        for (error in listOf(CancellationException("Synthetic stop"), java.io.EOFException("Synthetic EOF"))) {
            val link = Link(); val engine = ElmEngine(link, { _, _, _, _ -> }); engine.connect()
            val adapter = attach(engine); link.extendedFailure = error
            assertSame(error, runCatching { ElmUdsRequestTiming.configure(engine, "1902AF") }.exceptionOrNull())
            assertFalse(link.sent.contains("ATSTFA"))
            val after = link.sent.toList()
            assertTrue(runCatching { adapter.enableRoute(VagOemReadOnly.routes.getValue(0x19)) }.isFailure)
            assertEquals(after, link.sent)
        }
    }
}
