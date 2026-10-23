package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All fixtures are scripted; only a future physical ELM test can confirm behavior. */
class ElmAdapterCapabilitiesTest {
    private class FakeElm(
        val protocol: String = "A6",
        val obdLine: String = "7E8064100BE3EA813",
        val reject: String? = null
    ) : ElmLink {
        override val label = "Scripted test only"
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque<Int>()
        override suspend fun open() {}
        override suspend fun write(bytes: ByteArray) {
            val cmd = bytes.toString(Charsets.US_ASCII).trim()
            sent += cmd
            val reply = when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATDPN" -> protocol
                "0100" -> obdLine
                "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATAT1",
                "ATCAF1", "ATCFC1", "ATSH7E0", "ATCRA7E8", "ATCRA7E9" ->
                    if (cmd == reject) "?" else "OK"
                else -> throw IOException("Unexpected non-whitelisted command: " + cmd)
            }
            for (v in (reply + ">").toByteArray(Charsets.US_ASCII)) queue.add(v.toInt() and 255)
        }
        override suspend fun readByte(): Int = queue.pollFirst() ?: -2
        override fun close() = queue.clear()
    }

    private fun client(link: FakeElm) =
        ElmEngine(link, { _, _, _, _ -> })

    @Test fun confirmedCanAndAcceptedCommandsYieldAcceptanceNotValidatedFlowControl() = runBlocking {
        val link = FakeElm()
        val engine = client(link)
        engine.connect()
        val report = ElmAdapterCapabilities.inspect(engine)
        assertTrue(report.completeAcceptance)
        assertEquals("7E8", report.responder)
        assertEquals(listOf("ATCAF1", "ATCFC1", "ATSH7E0", "ATCRA7E8"),
            report.checks.map { it.command })
        assertTrue(report.checks.all {
            it.verdict == ElmAdapterCapabilities.Verdict.ACCEPTED
        })
        assertTrue(report.logText().contains("does NOT confirm addressed ISO-TP"))
        assertFalse(link.sent.any { it.startsWith("19") || it.startsWith("22") })
    }

    @Test fun rejectionStopsFurtherElmConfiguration() = runBlocking {
        val link = FakeElm(reject = "ATCFC1")
        val engine = client(link)
        engine.connect()
        val report = ElmAdapterCapabilities.inspect(engine)
        assertFalse(report.completeAcceptance)
        assertEquals(listOf("ATCAF1", "ATCFC1"), report.checks.map { it.command })
        assertEquals(ElmAdapterCapabilities.Verdict.REJECTED, report.checks.last().verdict)
        assertFalse(link.sent.contains("ATSH7E0"))
        assertFalse(link.sent.contains("ATCRA7E8"))
    }

    @Test fun wrongCanProtocolDoesNotChangeAdapterSettings() = runBlocking {
        val link = FakeElm(protocol = "A7")
        val engine = client(link)
        engine.connect()
        val report = ElmAdapterCapabilities.inspect(engine)
        assertFalse(report.completeAcceptance)
        assertTrue(report.checks.isEmpty())
        assertFalse(link.sent.contains("ATCAF1"))
    }

    @Test fun noConfirmedCanResponderCannotGuessAnRxFilter() = runBlocking {
        val link = FakeElm(obdLine = "4100BE3EA813")
        val engine = client(link)
        engine.connect()
        val report = ElmAdapterCapabilities.inspect(engine)
        assertFalse(report.completeAcceptance)
        assertTrue(report.checks.isEmpty())
        assertEquals(null, report.responder)
        assertFalse(link.sent.any { it.startsWith("ATCRA") })
    }

    @Test fun alternateRealObservedResponderIsUsedInsteadOfHardcodedFilter() = runBlocking {
        val link = FakeElm(obdLine = "7E906410098188001")
        val engine = client(link)
        engine.connect()
        val report = ElmAdapterCapabilities.inspect(engine)
        assertTrue(report.completeAcceptance)
        assertEquals("7E9", report.responder)
        assertEquals("ATCRA7E9", report.checks.last().command)
        assertFalse(link.sent.contains("ATCRA7E8"))
    }
}
