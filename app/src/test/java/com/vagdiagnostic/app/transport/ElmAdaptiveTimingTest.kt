package com.vagdiagnostic.app.transport

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmAdaptiveTimingTest {
    private class Link(val adaptive: String = "OK", val fixed: String = "OK") : ElmLink {
        override val label = "Scripted adaptive timing"
        val sent = mutableListOf<String>()
        private val incoming = ArrayDeque<Int>()
        override suspend fun open() {}
        override fun close() { incoming.clear() }
        override suspend fun readByte(): Int = incoming.removeFirstOrNull() ?: -2
        override suspend fun write(bytes: ByteArray) {
            val command = bytes.toString(Charsets.US_ASCII).trim()
            sent += command
            val reply = when (command) { "ATAT1" -> adaptive; "ATAT0" -> fixed; else -> "OK" }
            incoming.addAll((reply + ">").toByteArray().map { it.toInt() and 255 })
        }
    }
    @Test fun supportedAdaptiveTimingIsAcknowledgedAndCached() = runBlocking {
        val link = Link()
        val engine = ElmEngine(link, { _, _, _, _ -> })
        assertTrue(engine.configureAdaptiveTiming(true))
        assertTrue(engine.configureAdaptiveTiming(true))
        assertEquals(listOf("ATAT1"), link.sent)
        assertFalse(engine.configureAdaptiveTiming(false))
        assertFalse(engine.configureAdaptiveTiming(false))
        assertEquals(listOf("ATAT1", "ATAT0"), link.sent)
    }
    @Test fun completedUnsupportedReplySelectsConfirmedFixedTimingOncePerConnection() = runBlocking {
        val link = Link(adaptive = "?")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        repeat(3) { assertFalse(engine.configureAdaptiveTiming(true)) }
        assertEquals(listOf("ATAT1", "ATAT0"), link.sent)
        engine.command("ATZ")
        assertFalse(engine.configureAdaptiveTiming(true))
        assertEquals(listOf("ATAT1", "ATAT0", "ATZ", "ATAT1", "ATAT0"), link.sent)
    }
    @Test fun ambiguousAdaptiveRepliesCannotAuthorizeAQuietFallback() = runBlocking {
        for (reply in listOf("", "NO DATA", "OK\r?", "CAN ERROR")) {
            val link = Link(adaptive = reply)
            val error = runCatching { ElmEngine(link, { _, _, _, _ -> }).configureAdaptiveTiming(true) }.exceptionOrNull()
            assertTrue("$reply: $error", error is IOException)
            assertEquals(listOf("ATAT1"), link.sent)
        }
    }
    @Test fun rejectedFixedTimingDoesNotBecomeAConfirmedConfiguration() = runBlocking {
        val link = Link(adaptive = "?", fixed = "?")
        val engine = ElmEngine(link, { _, _, _, _ -> })
        assertTrue(runCatching { engine.configureAdaptiveTiming(true) }.exceptionOrNull() is IOException)
        assertEquals(listOf("ATAT1", "ATAT0"), link.sent)
    }
}
