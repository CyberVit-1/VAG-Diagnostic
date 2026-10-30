package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagDiagnosticAccessTest {
    @Test fun recognizedFunctionAndFilterValuesPreserveReportedState() {
        assertEquals("22539B", VagDiagnosticAccess.REQUEST)
        for (function in listOf(0, 1, 4)) for (filter in 0..1) {
            val status = requireNotNull(VagDiagnosticAccess.decode(listOf(0x62, 0x53, 0x9B, function, filter)))
            assertEquals(function, status.functionCode)
            assertEquals(filter == 1, status.active)
        }
        assertTrue(requireNotNull(VagDiagnosticAccess.decode(listOf(0x62, 0x53, 0x9B, 1, 1, 0))).active)
    }
    @Test fun missingNegativeAndUnrecognizedDataCannotBecomeFilterOff() {
        assertNull(VagDiagnosticAccess.decode(null))
        assertNull(VagDiagnosticAccess.decode(listOf(0x7F, 0x22, 0x31)))
        for (body in listOf(emptyList(), listOf(1), listOf(2, 0), listOf(3, 0), listOf(1, 2)))
            assertNull(VagDiagnosticAccess.decode(listOf(0x62, 0x53, 0x9B) + body))
    }
    @Test fun wrongDidAndPendingRemainOutstandingOrCorrupt() {
        for (reply in listOf(listOf(0x62, 0x53, 0x9A, 1, 0), listOf(0x7F, 0x22, 0x78), listOf(0x7F, 0x19, 0x31)))
            assertTrue(runCatching { VagDiagnosticAccess.decode(reply) }.isFailure)
    }
}
