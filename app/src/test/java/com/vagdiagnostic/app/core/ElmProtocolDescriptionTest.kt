package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmProtocolDescriptionTest {
    @Test fun actualAutomaticCanA6HasReadableDescription() {
        assertEquals(
            "ISO 15765-4 CAN (11 бит, 500 кбит/с) · Авто",
            ElmProtocolDescription.describe("A6")
        )
        assertEquals(
            "ISO 15765-4 CAN (11 бит, 500 кбит/с)",
            ElmProtocolDescription.describe("6")
        )
    }

    @Test fun everyStandardElmProtocolIsMappedWithoutFakingSpeedForCustomCan() {
        for (code in ('1'..'9').map { it.toString() } + listOf("A", "B", "C")) {
            assertTrue("Missing $code", !ElmProtocolDescription.describe(code).contains("Неизвестный"))
        }
        assertTrue(ElmProtocolDescription.describe("B").contains("Пользовательский"))
        assertTrue(ElmProtocolDescription.describe("C").contains("Пользовательский"))
        assertTrue(ElmProtocolDescription.describe("A7").contains("29 бит"))
        assertTrue(ElmProtocolDescription.describe("A8").contains("250 кбит/с"))
    }

    @Test fun zeroAndUnknownNeverClaimSelectedCanProtocol() {
        assertEquals("Автоматическое определение", ElmProtocolDescription.describe("0"))
        assertEquals("Автоматическое определение", ElmProtocolDescription.describe("A0"))
        assertEquals("Не определён", ElmProtocolDescription.describe("?"))
        assertEquals("Не определён", ElmProtocolDescription.describe("Не определён"))
        assertTrue(ElmProtocolDescription.describe("D").startsWith("Неизвестный"))
    }

    @Test fun decoderNormalizesWhitespaceAndCase() {
        assertEquals(ElmProtocolDescription.describe("A6"), ElmProtocolDescription.describe(" a6 "))
    }
}
