package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagEcuIdentificationTest {
    private fun ascii(s: String) = s.map { it.code }
    @Test fun fixedMainModuleFieldsOnlyAndNoCodingOrSerialInSummary() {
        val reply = listOf(0x5A, 0x9B) + ascii("1K0907530K  " + "0178") + List(10) { 0 } + ascii("OTHER DATA")
        val identity = requireNotNull(VagEcuIdentification.kwp(reply))
        assertEquals("1K0907530K", identity.partNumber)
        assertEquals("0178", identity.softwareVersion)
        assertFalse(identity.summary().contains("OTHER DATA"))
        assertNull(VagEcuIdentification.kwp(listOf(0x5A, 0x9B) + ascii("SHORT")))
        assertNull(VagEcuIdentification.kwp(listOf(0x7F, 0x1A, 0x11)))
    }
    @Test fun udsRequiresMatchingDidPrintablePartAndBoundedLength() {
        assertEquals("7N0907530", VagEcuIdentification.udsPartNumber(listOf(0x62, 0xF1, 0x87) + ascii("7N0907530"))!!.partNumber)
        assertNull(VagEcuIdentification.udsPartNumber(listOf(0x7F, 0x22, 0x31)))
        assertNull(VagEcuIdentification.udsPartNumber(listOf(0x62, 0xF1, 0x87, 0, 1, 2, 3, 4)))
        assertNull(VagEcuIdentification.udsPartNumber(listOf(0x62, 0xF1, 0x87) + ascii("WVWAAA1KAAAAAAAAA")))
        assertTrue(runCatching { VagEcuIdentification.udsPartNumber(listOf(0x62, 0xF1, 0x90) + ascii("123456789")) }.isFailure)
        assertTrue(runCatching { VagEcuIdentification.kwp(listOf(0x5A, 0x9F) + List(26) { 0x41 }) }.isFailure)
    }
}
