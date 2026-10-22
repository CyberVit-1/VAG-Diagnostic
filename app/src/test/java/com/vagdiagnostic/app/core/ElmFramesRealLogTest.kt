package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases from an actual Wi-Fi ELM327 vehicle session, 2026-10-22.
 * These tests check parser behavior, not physical hardware compatibility.
 */
class ElmFramesRealLogTest {
    private val first = "7E8064100BE3EA813"
    private val second = "7E906410098188001"

    @Test
    fun compactCanHeadersFromRealCarAreRecognized() {
        val frames = ElmFrames.parse("0100", "SEARCHING...\r$first\r$second\r>")
        assertTrue("Unexpected parse errors: ${frames.problems}", frames.problems.isEmpty())
        assertEquals(setOf("7E8", "7E9"), frames.messagesFor(0x41).keys)
        assertTrue(ObdDecoder.hasValidVehicleResponse(frames))
        val firstPayload = frames.messagesFor(0x41)["7E8"]!!.single()
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3E, 0xA8, 0x13),
            firstPayload.map { it.toInt() and 0xFF })
    }

    @Test
    fun compactPayloadAfterWhitespaceHeaderIsRecognized() {
        val frames = ElmFrames.parse("0100", "7E8 064100BE3EA813\r>")
        assertTrue(frames.problems.isEmpty())
        assertEquals(setOf("7E8"), frames.messagesFor(0x41).keys)
    }

    @Test
    fun vinMaskingRetainsBothActualCanFrames() {
        val source = "$first\r$second\rVIN: WVWZZZ1KZAW123456\r"
        val exported = DiagnosticLogRedactor.redact(source)
        assertTrue(exported.contains(first))
        assertTrue(exported.contains(second))
        assertTrue(exported.contains("VIN: [VIN REDACTED]"))
        assertFalse(exported.contains("WVWZZZ1KZAW123456"))
    }

    @Test
    fun unknownOrNoDataStillCannotCountAsVehicleReply() {
        val failed = ElmFrames.parse("0100", "SEARCHING...\rNO DATA\r>")
        assertFalse(ObdDecoder.hasValidVehicleResponse(failed))
        assertTrue(failed.problems.isNotEmpty())
    }
}
