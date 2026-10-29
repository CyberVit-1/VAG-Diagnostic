package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ObdMode09ReadinessTest {
    private val ecus = setOf("7E8", "7E9")

    @Test fun pid02SupportIsConfirmedOnlyFromPositive0900Bitmap() {
        val frames = ElmFrames.parse("0900", "7E806490040000000\r7E906490000000000\r>")
        val result = ObdMode09Readiness.supportedVinEcus(frames, ecus)
        assertEquals(setOf("7E8"), result.responders)
    }

    @Test fun noDataAndUnknownEcuAreNotMistakenForSupport() {
        assertTrue(ObdMode09Readiness.supportedVinEcus(
            ElmFrames.parse("0900", "NO DATA\r>"), ecus).responders.isEmpty())
        val unknown = ObdMode09Readiness.supportedVinEcus(
            ElmFrames.parse("0900", "7EA06490040000000\r>"), ecus)
        assertTrue(unknown.responders.isEmpty())
        assertTrue(unknown.issues.any { "7EA" in it })
    }

    @Test fun realCanMultiframePayloadCanBeVerifiedWithoutExposingVin() {
        // Synthetic 17-byte VIN-like payload: 123456789ABCDEFGH
        val raw = "7E81014490201313233\r7E82134353637383941\r7E82242434445464748\r>"
        val frames = ElmFrames.parse("0902", raw)
        assertTrue(frames.problems.toString(), frames.problems.isEmpty())
        val result = ObdMode09Readiness.assessVin(frames, setOf("7E8"))
        assertTrue(result.issues.toString(), result.confirmed)
        assertEquals(setOf("7E8"), result.verified)
        assertFalse(result.journalSummary().contains("123456789ABCDEFGH"))
    }

    @Test fun truncatedOutOfOrderOrHeaderlessMultiframeMustFail() {
        val responses = listOf(
            "7E81014490201313233\r>",
            "7E81014490201313233\r7E82234353637383941\r>",
            "1014490201313233\r2134353637383941\r2242434445464748\r>",
            "NO DATA\r>"
        )
        responses.forEach { raw ->
            val result = ObdMode09Readiness.assessVin(
                ElmFrames.parse("0902", raw), setOf("7E8")
            )
            assertFalse(raw, result.confirmed)
        }
    }

    @Test fun shortVinIsNotEvidenceOfMultiframeSupport() {
        val incomplete = ElmFrames.parse("0902", "7E80749020131323334\r>")
        val result = ObdMode09Readiness.assessVin(incomplete, setOf("7E8"))
        assertFalse(result.confirmed)
        assertTrue(result.issues.isNotEmpty())
    }

    @Test fun doesNotRequireAllObdEcusToSupportVin() {
        val frames = ElmFrames.parse("0900", "7E806490040000000\r7E906490000000000\r>")
        assertEquals(setOf("7E8"), ObdMode09Readiness.supportedVinEcus(frames, ecus).responders)
    }
    private fun syntheticReply(value: String, ecu: String = "7E8"): String {
        val payload = listOf(0x49, 2, 1) + value.map { it.code }
        val hex = payload.joinToString("") { "%02X".format(it) }
        return ecu + "10%02X".format(payload.size) + hex.take(12) + "\r" +
            hex.drop(12).chunked(14).mapIndexed { index, chunk -> ecu + "2%X".format(index + 1) + chunk }.joinToString("\r")
    }
    @Test fun localDisplayReturnsOnlyAValidatedConsistentVin() {
        // Deliberately synthetic identifier; never substitute recorded vehicle data.
        val value = "123456789ABCDEFGH"
        val frames = ElmFrames.parse("0902", syntheticReply(value) + "\r" + syntheticReply(value, "7E9"))
        assertEquals(value, ObdMode09Readiness.vinForDisplay(frames, ecus))
        assertFalse(ObdMode09Readiness.assessVin(frames, ecus).journalSummary().contains(value))
        val conflicting = ElmFrames.parse("0902", syntheticReply(value) + "\r" + syntheticReply("223456789ABCDEFGH", "7E9"))
        assertNull(ObdMode09Readiness.vinForDisplay(conflicting, ecus))
        assertNull(ObdMode09Readiness.vinForDisplay(frames, emptySet()))
    }
    @Test fun invalidAndPartialVinValuesNeverReachDisplay() {
        for (value in listOf("123456789ABCDEFGI", "123456789ABCDEFGO", "123456789ABCDEFGQ", "123456789ABCDEFG")) {
            val frames = ElmFrames.parse("0902", syntheticReply(value))
            assertNull(ObdMode09Readiness.vinForDisplay(frames, setOf("7E8")))
        }
        assertNull(ObdMode09Readiness.vinForDisplay(ElmFrames.parse("0902", "NO DATA"), setOf("7E8")))
    }
}
