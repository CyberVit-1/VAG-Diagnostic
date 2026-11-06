package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic adapter acknowledgements, not detected physical capabilities. */
class ElmNativeSegmentationTest {
    private val info = ElmNativeSegmentation.Info(1170, 5, 6, 5)
    private val gateway = VagOemReadOnly.routes.getValue(0x19)
    @Test fun modernStnConfirmsItsFcAndCfTimeoutsBeforeNativeUseAndAfterProtocolChange() = runBlocking {
        for (patch in listOf(4, 5)) {
            val sent = mutableListOf<String>()
            val mode = ElmNativeSegmentation(ElmNativeSegmentation.Info(1170, 5, 6, patch)) { sent += it; "OK" }
            assertTrue(mode.enableRoute(gateway))
            assertEquals(if (patch == 5) 1 else 0, sent.count { it == "STCTOR 1000, 1000" })
            val after = sent.toList()
            assertTrue(mode.enableRoute(gateway)); assertEquals(after, sent)
            if (patch == 5) {
                mode.onTimingSetting("STCTOR 1000, 1000")
                assertTrue(mode.enableRoute(gateway))
                assertEquals(2, sent.count { it == "STCTOR 1000, 1000" })
            }
            mode.onProtocolSelection()
            assertTrue(mode.enableRoute(gateway))
            assertEquals(if (patch == 5) 3 else 0, sent.count { it == "STCTOR 1000, 1000" })
        }
    }
    @Test fun rejectedFcTimeoutRollsBackNativeButKeepsIndependentLongTiming() = runBlocking {
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; if (it == "STCTOR 1000, 1000") "?" else "OK" }
        assertFalse(mode.enableRoute(gateway))
        assertFalse(mode.enabled)
        assertEquals(listOf("STCTOR 1000, 1000", "STCSEGR0", "STCSEGT0"), sent.takeLast(3))
        assertTrue(mode.setOptionalResponseTimeout(2000))
        assertEquals("STPTO2000", sent.last())
        assertFalse(mode.enabled)
    }
    @Test fun ambiguousFcTimeoutReplyRequiresANewConnection() = runBlocking {
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; if (it == "STCTOR 1000, 1000") "OK\r?" else "OK" }
        assertTrue(runCatching { mode.enableRoute(gateway) }.exceptionOrNull() is VagProtocolFailure)
        val after = sent.toList()
        assertTrue(runCatching { mode.enableRoute(gateway) }.isFailure)
        assertEquals(after, sent)
    }
    @Test fun bannerOnlyIdentifiesCandidateAndDoesNotEnableAnything() {
        assertEquals(info, ElmNativeSegmentation.identify("STN1170 v5.6.5\r>"))
        for (raw in listOf("OK", "?", "ELM327 v1.5", "STN1170", "STN1170 v5.6.5\rUNKNOWN"))
            assertNull(ElmNativeSegmentation.identify(raw))
    }
    @Test fun acceptedConfigurationIncludesExactPhysicalFlowControlPair() = runBlocking {
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; "OK" }
        assertTrue(mode.enableRoute(gateway))
        assertTrue(mode.enabled)
        assertEquals(listOf("STCSEGR1", "STCSEGT1", "STCFCPC", "STCFCPA710,77A", "STCTOR 1000, 1000"), sent)
        mode.disable()
        assertFalse(mode.enabled)
        assertEquals(listOf("STCSEGR0", "STCSEGT0"), sent.takeLast(2))
    }
    @Test fun twentyNineBitPairRetainsAllHeaderBits() = runBlocking {
        val route = VagOemReadOnly.routes.values.first { it.is29Bit }
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; "OK" }
        assertTrue(mode.enableRoute(route))
        assertEquals(listOf("STCFCPA%08X,%08X".format(route.udsTx, route.udsRx), "STCTOR 1000, 1000"), sent.takeLast(2))
    }
    @Test fun rejectedRxNeverPromotesTheFormatOrChangesOtherSettings() = runBlocking {
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; "?" }
        assertFalse(mode.enableRoute(gateway))
        assertFalse(mode.enabled)
        assertEquals(listOf("STCSEGR1"), sent)
    }
    @Test fun partialAcceptanceRequiresConfirmedRollbackBeforeFallback() = runBlocking {
        val sent = mutableListOf<String>()
        val mode = ElmNativeSegmentation(info) { sent += it; if (it == "STCSEGT1") "?" else "OK" }
        assertFalse(mode.enableRoute(gateway))
        assertFalse(mode.enabled)
        assertEquals(listOf("STCSEGR1", "STCSEGT1", "STCSEGR0", "STCSEGT0"), sent)
    }
    @Test fun failedRollbackAndCancellationNeverPermitAnotherTransaction() = runBlocking {
        for (cancel in listOf(false, true)) {
            val sent = mutableListOf<String>()
            val mode = ElmNativeSegmentation(info) {
                sent += it
                when (it) {
                    "STCSEGT1" -> if (cancel) throw CancellationException("Stopped") else "?"
                    "STCSEGR0" -> "?"
                    else -> "OK"
                }
            }
            val failure = runCatching { mode.enableRoute(gateway) }.exceptionOrNull()
            assertTrue(if (cancel) failure is CancellationException else failure is VagProtocolFailure)
            val after = sent.toList()
            assertTrue(runCatching { mode.enableRoute(gateway) }.exceptionOrNull() is VagProtocolFailure)
            assertEquals(after, sent)
        }
    }
    @Test fun vtFirmwareGateAndTimeoutSyntaxMatchTheConfirmedBoundaries() = runBlocking {
        assertNull(ElmNativeSegmentation.identifyVt("Synthetic Adapter v2.2.59"))
        assertEquals(20259, ElmNativeSegmentation.identifyVtAdapter("Synthetic Adapter v2.2.59")!!.version)
        val oldSent = mutableListOf<String>()
        val old = ElmNativeSegmentation(ElmNativeSegmentation.identifyVtAdapter("Synthetic Adapter v2.2.59")!!) {
            oldSent += it; "OK"
        }
        old.disable()
        assertFalse(old.enableRoute(gateway))
        assertTrue(oldSent.isEmpty())
        assertTrue(old.setOptionalResponseTimeout(2000))
        assertEquals(listOf("STPTO2000"), oldSent)
        assertEquals(20260, ElmNativeSegmentation.identifyVt("Synthetic Adapter v2.2.60")!!.version)
        assertEquals(20300, ElmNativeSegmentation.identifyVt("Synthetic Adapter BT3.0+ v2.3.0")!!.version)
        assertNull(ElmNativeSegmentation.identifyVt("Synthetic Adapter v2.3.0\rSynthetic Adapter v2.4.0"))
        for (patch in listOf(77, 78)) {
            val sent = mutableListOf<String>()
            val mode = ElmNativeSegmentation(ElmNativeSegmentation.Info(0, 2, 2, patch, ElmNativeSegmentation.Family.VT)) {
                sent += it; it + "\rOK"
            }
            assertTrue(mode.enableRoute(gateway))
            mode.setResponseTimeout(2000)
            val after = sent.toList()
            assertTrue(mode.enableRoute(gateway))
            mode.setResponseTimeout(2000)
            assertEquals(after, sent)
            assertEquals(listOf("VTSWGP GR1, BZF1", "VTSWGP GT1", "VTFCTRA 710, 77A",
                if (patch < 78) "STPTO2000" else "VTTOST REP:2000"), sent)
            mode.disable()
            assertEquals(listOf("VTSWGP GR0", "VTSWGP GT0"), sent.takeLast(2))
        }
    }
    @Test fun transportEofIsNotRelabelledAsRetryableProtocolData() = runBlocking {
        val sent = mutableListOf<String>()
        val eof = java.io.EOFException("Synthetic closed stream")
        val mode = ElmNativeSegmentation(info) { sent += it; throw eof }
        assertSame(eof, runCatching { mode.enableRoute(gateway) }.exceptionOrNull())
        assertTrue(runCatching { mode.enableRoute(gateway) }.exceptionOrNull() is VagProtocolFailure)
        assertEquals(listOf("STCSEGR1"), sent)
    }
}
