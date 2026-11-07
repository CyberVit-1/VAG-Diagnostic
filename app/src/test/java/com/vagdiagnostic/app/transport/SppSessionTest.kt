package com.vagdiagnostic.app.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scripted socket boundary; no physical SPP evidence. */
class SppSessionTest {
    private class Socket(private val events: MutableList<String>, val id: String) : SppSession.Connection {
        var action: suspend () -> Unit = {}
        var streamFailure = false
        var closes = 0
        val bytes = ByteArrayOutputStream()
        override suspend fun connect() { events += "connect:$id"; action() }
        override fun input(): ByteArrayInputStream {
            events += "input:$id"
            if (streamFailure) throw IOException("broken stream")
            return ByteArrayInputStream(byteArrayOf(65))
        }
        override fun output() = bytes
        override fun close() { closes++; events += "close:$id" }
    }
    private class Driver : SppSession.Driver {
        val events = mutableListOf<String>()
        val first = Socket(events, "insecure")
        val second = Socket(events, "secure")
        val modes = mutableListOf<SppSession.Mode>()
        var onCreate: () -> Unit = {}
        var denied = false
        override fun cancelDiscovery() { events += "cancelDiscovery" }
        override fun create(mode: SppSession.Mode): SppSession.Connection {
            modes += mode
            if (denied) throw SecurityException("permission")
            onCreate()
            return if (mode == SppSession.Mode.INSECURE) first else second
        }
    }
    private fun session(driver: Driver, timeout: Long = 1_000, settle: suspend (Long) -> Unit = {}) =
        SppSession(driver, timeout, settle)

    @Test fun insecureCompatibleAdapterOpensWithoutSecureAttemptAndWaitsBeforePublishingStreams() = runBlocking<Unit> {
        val driver = Driver()
        lateinit var session: SppSession
        var settling = 0L
        session = session(driver) { ms ->
            settling = ms
            assertThrows(IOException::class.java) { session.input() }
        }
        session.open()
        assertEquals(listOf(SppSession.Mode.INSECURE), driver.modes)
        assertEquals(2_000L, settling)
        assertEquals(65, session.input().read())
        assertEquals(listOf("cancelDiscovery", "connect:insecure", "input:insecure"), driver.events)
        session.close()
        assertEquals(1, driver.first.closes)
    }

    @Test fun failedInsecureSocketClosesBeforeOneSecureFallback() = runBlocking<Unit> {
        val driver = Driver().apply { first.action = { throw IOException("pairing required") } }
        val session = session(driver)
        session.open()
        assertEquals(SppSession.Mode.entries, driver.modes)
        assertTrue(driver.events.indexOf("close:insecure") < driver.events.indexOf("connect:secure"))
        assertEquals(2, driver.events.count { it == "cancelDiscovery" })
        session.output().write(66)
        assertArrayEquals(byteArrayOf(66), driver.second.bytes.toByteArray())
        session.close()
    }

    @Test fun bothFailedAttemptsAreFiniteAndLeaveNoUsableStreams() = runBlocking<Unit> {
        val driver = Driver().apply {
            first.action = { throw IOException("first") }
            second.action = { throw IOException("second") }
        }
        val session = session(driver)
        val problem = runCatching { session.open() }.exceptionOrNull()
        assertTrue(problem is IOException)
        assertEquals("second", problem?.message)
        assertEquals(2, driver.modes.size)
        assertEquals(1, driver.first.closes)
        assertEquals(1, driver.second.closes)
        assertThrows(IOException::class.java) { session.input() }
    }

    @Test fun brokenStreamAfterConnectionDoesNotStartAnotherPairingAttempt() = runBlocking<Unit> {
        val driver = Driver().apply { first.streamFailure = true }
        val session = session(driver)
        assertTrue(runCatching { session.open() }.exceptionOrNull() is IOException)
        assertEquals(1, driver.modes.size)
        assertEquals(1, driver.first.closes)
    }

    @Test fun permissionFailureIsNotRetriedAsAnotherConnectionMode() = runBlocking<Unit> {
        val driver = Driver().apply { denied = true }
        assertTrue(runCatching { session(driver).open() }.exceptionOrNull() is SecurityException)
        assertEquals(1, driver.modes.size)
    }

    @Test fun cancellationClosesBlockedConnectWithoutFallback() = runBlocking<Unit> {
        val driver = Driver().apply { first.action = { awaitCancellation() } }
        val session = session(driver)
        val job = launch { session.open() }
        yield()
        job.cancelAndJoin()
        assertEquals(1, driver.modes.size)
        assertEquals(1, driver.first.closes)
        assertThrows(IOException::class.java) { session.output() }
    }

    @Test fun externalCloseCancelsOpeningButDoesNotCancelItsCaller() = runBlocking<Unit> {
        val driver = Driver().apply { first.action = { awaitCancellation() } }
        val session = session(driver)
        val job = launch { session.open() }
        yield()
        session.close()
        withTimeout(500) { job.join() }
        assertTrue(isActive)
        assertEquals(1, driver.modes.size)
        assertEquals(1, driver.first.closes)
    }

    @Test fun ownDeadlineClosesTimedOutSocketAndAllowsSecureFallback() = runBlocking<Unit> {
        val driver = Driver().apply { first.action = { awaitCancellation() } }
        val session = session(driver, timeout = 20)
        session.open()
        assertEquals(2, driver.modes.size)
        assertEquals(1, driver.first.closes)
        assertEquals(65, session.input().read())
        session.close()
    }

    @Test fun closeDuringSocketCreationClosesUnpublishedSocketWithoutConnecting() = runBlocking<Unit> {
        val driver = Driver()
        val session = session(driver)
        driver.onCreate = { session.close() }
        val job = launch { session.open() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, driver.first.closes)
        assertFalse(driver.events.any { it.startsWith("connect:") })
        assertEquals(1, driver.modes.size)
    }

    @Test fun closeDuringSettlingCannotExposeOldStreamsOrAffectFreshSession() = runBlocking<Unit> {
        val driver = Driver()
        val entered = CompletableDeferred<Unit>()
        val old = session(driver) { entered.complete(Unit); awaitCancellation() }
        val job = launch { old.open() }
        entered.await()
        old.close()
        job.join()
        val freshDriver = Driver()
        val fresh = session(freshDriver)
        fresh.open()
        old.close()
        assertThrows(IOException::class.java) { old.input() }
        assertEquals(65, fresh.input().read())
        assertEquals(0, freshDriver.first.closes)
        fresh.close()
    }
}
