package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogRedactorTest {
    @Test fun mode09HexEncodedVinIsHiddenFromTxtExport() {
        val raw = "7E81014490201313233\\r7E82134353637383941\\r7E82242434445464748\\r>"
        val exported = DiagnosticLogRedactor.redact(raw, "0902")
        assertTrue(exported.contains("VIN-bearing diagnostic payload hidden"))
        assertFalse(exported.contains("7E81014"))
        assertFalse(exported.contains("7E821"))
        assertFalse(exported.contains("7E822"))
        assertEquals(raw, raw) // Redaction does not modify the stored original.
    }

    @Test fun spacedMode09AndUdsVinRequestsAreProtected() {
        val raw = "18DAF1101014490201AABBCC\\r18DAF11021DDEEFF\\r>"
        assertFalse(DiagnosticLogRedactor.redact(raw, "09 02").contains("AABBCC"))
        assertFalse(DiagnosticLogRedactor.redact(raw, "22 F1 90").contains("AABBCC"))
    }

    @Test fun ordinaryMode03AndCanFrameEvidenceRemainIntact() {
        val raw = "7E8024300\\r7E9024300\\r>"
        assertEquals(raw, DiagnosticLogRedactor.redact(raw, "03"))
        val packed = "7E8064100BE3EA813"
        assertEquals(packed, DiagnosticLogRedactor.redact(packed, "0100"))
    }

    @Test fun literalTextVinIsStillRedacted() {
        val vin = "WVWZZZ1JZXW000001"
        assertEquals("[VIN REDACTED]", DiagnosticLogRedactor.redact(vin))
    }

    @Test fun benignMode09SummaryContainsNoVinAndIsPreserved() {
        val summary = "Mode 09: verified ECUs=1; no VIN displayed"
        assertEquals(summary, DiagnosticLogRedactor.redact(summary, "OBD09_ISOTP_READINESS"))
    }
}
