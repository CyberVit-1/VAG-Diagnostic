package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ElmGatewayListFallbackTest {
    @Test fun logicalListStopsDiscoveryBeforeAnySupplementalQuery() = runBlocking {
        val sent = mutableListOf<String>()
        val result = ElmGatewayListFallback.read(null, { sent += it; listOf(0x62, 0x2A, 0x2A, 0x19, 3) }, { _, _ -> })
        assertEquals(listOf("222A2A"), sent)
        assertEquals(listOf(0x19, 3), result.entries.map { it.id })
    }
    @Test fun indexedAndCodedSourcesRemainSeparateInTheCombinedQueue() = runBlocking {
        val sent = mutableListOf<String>()
        val result = ElmGatewayListFallback.read("5Q0907530", { command ->
            sent += command
            when (command) {
                "222A2A" -> null
                "22F1B7" -> listOf(0x62, 0xF1, 0xB7, 0, 0x13)
                "2204A3" -> listOf(0x62, 4, 0xA3) + List(32) { if (it == 1) 0x40 else 0 }
                "222A28" -> listOf(0x62, 0x2A, 0x28) + List(32) { if (it == 2) 0x04 else 0 }
                else -> error(command)
            }
        }, { _, _ -> })
        assertEquals(listOf("222A2A", "22F1B7", "2204A3", "222A28"), sent)
        assertTrue(result.entries.single { it.id == 3 }.installationConfirmed)
        assertFalse(result.entries.single { it.id == 9 }.installationConfirmed)
        assertFalse(result.entries.single { it.id == 0x44 }.installationConfirmed)
    }
    @Test fun unknownFamilyCannotEnableSupplementalBitmapInterpretation() = runBlocking {
        for (part in listOf(null, "UNKNOWN", "5WA907530")) {
            val sent = mutableListOf<String>()
            val result = ElmGatewayListFallback.read(part, { sent += it; null }, { _, _ -> })
            assertEquals(listOf("222A2A", "22F1B7"), sent); assertTrue(result.entries.isEmpty())
        }
    }
    @Test fun positiveEmptyListsContinueWithoutClaimingAnInstallationList() = runBlocking {
        val sent = mutableListOf<String>()
        val result = ElmGatewayListFallback.read("5Q0907530", {
            sent += it
            when (it) {
                "222A2A" -> listOf(0x62, 0x2A, 0x2A)
                "22F1B7" -> listOf(0x62, 0xF1, 0xB7)
                "2204A3" -> listOf(0x62, 4, 0xA3) + List(32) { if (it == 1) 0x40 else 0 }
                else -> null
            }
        }, { _, _ -> })
        assertEquals(listOf("222A2A", "22F1B7", "2204A3", "222A28"), sent)
        assertEquals(9, result.entries.single().id)
        assertFalse(result.entries.single().installationConfirmed)
    }
    @Test fun malformedDeniedBrokenAndCancelledRequestsStopBeforeAnotherDid() = runBlocking {
        for (mode in listOf("malformed", "denied", "io", "cancel")) {
            val sent = mutableListOf<String>()
            val error = runCatching { ElmGatewayListFallback.read("5Q0907530", {
                sent += it
                when (mode) {
                    "malformed" -> listOf(0x62, 0x2A, 0x26, 3)
                    "denied" -> listOf(0x7F, 0x22, 0x33)
                    "io" -> throw IOException("lost")
                    else -> throw CancellationException("cancel")
                }
            }, { _, _ -> }) }.exceptionOrNull()
            assertNotNull(error); assertEquals(listOf("222A2A"), sent)
            if (mode == "cancel") assertTrue(error is CancellationException)
        }
    }
    @Test fun newBoundaryAllowsOnlyFixedReadRequests() {
        for (command in listOf("222A2A", "22F1B7", "2204A3", "222A28")) {
            assertTrue(ElmUdsReadRequests.allowed(command)); assertFalse(ElmUdsReadRequests.allowsAdaptiveTiming(command))
        }
        for (command in listOf("14FFFFFF", "2E04A300", "2701", "222A2B")) assertFalse(ElmUdsReadRequests.allowed(command))
    }
}
