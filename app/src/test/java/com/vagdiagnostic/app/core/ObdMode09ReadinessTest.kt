package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
