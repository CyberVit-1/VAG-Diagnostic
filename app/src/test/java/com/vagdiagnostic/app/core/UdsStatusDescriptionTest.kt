package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class UdsStatusDescriptionTest {
    @Test fun actualSteeringReplyRetainsCodeAndExplainsUnconfirmedStatus() {
        val fault = VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("590219096D0801")).values.single()
        assertEquals("096D08", fault.code)
        assertTrue(fault.statusText!!.contains("Тест неисправности: сбой"))
        assertTrue(fault.statusText!!.contains("Не подтверждена ECU"))
        assertTrue(fault.statusText!!.contains("0x01"))
        assertTrue(fault.statusText!!.contains("0x19"))
    }
    @Test fun unsupportedFlagsAreNeverInterpretedAsSupportedOrAbsent() {
        val fault = VagUdsReadOnly.Fault("123456", 0xFF, 0x01)
        val text = fault.statusDescription()
        assertTrue(text.contains("не поддерживается"))
        assertFalse(text.contains("Ожидающая"))
        assertFalse(text.contains("Запрошен индикатор"))
        assertFalse(text.contains("Подтверждена ECU"))
        assertTrue(text.contains("0xFF"))
        assertTrue(VagUdsReadOnly.Fault("123456", 8, 0x19).statusDescription().contains("Подтверждена ECU"))
    }
}
