package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class UdsFaultCoverageTest {
    @Test fun emptyReplyWithoutAnySupportedFailureFlagCannotProveNoFaults() {
        for (mask in listOf("00", "10", "40", "50"))
            assertTrue(runCatching { VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("5902$mask")) }.isFailure)
        for (mask in listOf("01", "02", "20", "AF", "FF"))
            assertTrue(VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("5902$mask")).values.isEmpty())
    }
    @Test fun queryIncludesEveryFailureFlagButNoReadinessOnlyFlag() {
        val mask = VagUdsReadOnly.DTC_REQUEST.takeLast(2).toInt(16)
        assertEquals(VagUdsReadOnly.FAULT_STATUS_MASK, mask)
        for (status in 0..255) {
            val failure = listOf(1, 2, 4, 8, 32, 128).any { status and it != 0 }
            assertEquals("status=$status", failure, status and mask != 0)
        }
        assertEquals(0x8D, mask and 0x8D) // retain all formerly requested bits
    }
    @Test fun cycleOnlyAndHistoricalOnlyRecordsAreRetainedWithAccurateDescriptions() {
        val faults = VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("5902AF1234560265432120"))
        assertEquals("1902AF", faults.service)
        assertEquals(listOf("123456", "654321"), faults.values.map { it.code })
        assertTrue(faults.values[0].statusText!!.contains("Сбой в текущем цикле"))
        assertTrue(faults.values[1].statusText!!.contains("Сбой после последнего стирания"))
        assertTrue(faults.values.all { it.statusText!!.contains("Не подтверждена ECU") })
    }
    @Test fun unsupportedHistoryFlagsAreNotInterpreted() {
        val text = VagUdsReadOnly.Fault("123456", 0x22, 0x8D).statusDescription()
        assertFalse(text.contains("Сбой в текущем цикле"))
        assertFalse(text.contains("Сбой после последнего стирания"))
    }
}
