package com.vagdiagnostic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol-level tests; do not imply a successful real-car scan. */
class DtcModeAssessmentTest {
    private val expected = setOf("7E8", "7E9")

    /** Exact observed raw CAN lines from the physical ELM327 session on 2026-10-23. */
    @Test fun physicalTest03And07SucceededBut0ACannotBeCalledFaultFree() {
        val ecus = setOf("7E8", "7E9")
        val mode03 = ObdDecoder.assessMode(
            ElmFrames.parse("03", "7E8024300\r7E9024300\r>"),
            DtcKind.CONFIRMED, true, ecus
        )
        val mode07 = ObdDecoder.assessMode(
            ElmFrames.parse("07", "7E8024700\r7E9024700\r>"),
            DtcKind.PENDING, true, ecus
        )
        val mode0A = ObdDecoder.assessMode(
            ElmFrames.parse("0A", "NO DATA\r>"),
            DtcKind.PERMANENT, true, ecus
        )
        assertTrue(mode03.complete)
        assertTrue(mode07.complete)
        assertEquals(ecus, mode03.confirmed.keys)
        assertEquals(ecus, mode07.confirmed.keys)
        assertTrue(mode03.confirmed.values.all { it.isEmpty() })
        assertTrue(mode07.confirmed.values.all { it.isEmpty() })
        assertFalse(mode0A.complete)
        assertTrue(mode0A.confirmed.isEmpty())
        assertTrue(mode0A.problems.any { it.contains("NO DATA") })
    }

    @Test fun emptyResponseCannotCountAsFaultFreeOrCompletedScan() {
        val result = ObdDecoder.assessMode(ElmFrames.parse("03", ""), DtcKind.CONFIRMED, true, expected)
        assertTrue(result.confirmed.isEmpty())
        assertFalse(result.complete)
        assertTrue(result.problems.any { it.contains("Нет подтверждённого ответа") })
    }

    @Test fun noDataCannotCountAsFaultFree() {
        val result = ObdDecoder.assessMode(ElmFrames.parse("03", "NO DATA\r>"), DtcKind.CONFIRMED, true, expected)
        assertTrue(result.confirmed.isEmpty())
        assertFalse(result.complete)
        assertTrue(result.problems.any { it.contains("NO DATA") })
    }

    @Test fun positiveZeroCountIsAValidPerEcuReplyNotGlobalSuccess() {
        val frames = ElmFrames.parse("03", "7E8024300\r>")
        val result = ObdDecoder.assessMode(frames, DtcKind.CONFIRMED, true, expected)
        assertTrue(result.confirmed.containsKey("7E8"))
        assertTrue(result.confirmed["7E8"]!!.isEmpty())
        assertFalse(result.confirmed.containsKey("7E9"))
        assertFalse(result.complete)
    }

    @Test fun allExpectedEcusWithPositiveZeroRepliesCountAsSuccessfulMode() {
        val frames = ElmFrames.parse("03", "7E8024300\r7E9024300\r>")
        val result = ObdDecoder.assessMode(frames, DtcKind.CONFIRMED, true, expected)
        assertTrue(result.complete)
        assertEquals(expected, result.confirmed.keys)
        assertTrue(result.confirmed.values.all { it.isEmpty() })
    }

    @Test fun validDtcFromOneEcuSurvivesMalformedReplyFromAnother() {
        val frames = ElmFrames.parse("03", "7E80443010299\r7E903430102\r>")
        val result = ObdDecoder.assessMode(frames, DtcKind.CONFIRMED, true, expected)
        assertEquals(listOf("P0299"), result.confirmed["7E8"]?.map { it.code })
        assertFalse(result.confirmed.containsKey("7E9"))
        assertFalse(result.complete)
        assertTrue(result.problems.any { it.contains("7E9") })
    }

    @Test fun unknownDtcLayoutMustNotInventCodes() {
        val frames = ElmFrames.parse("03", "7E80443010299\r>")
        val result = ObdDecoder.assessMode(frames, DtcKind.CONFIRMED, null, setOf("7E8"))
        assertTrue(result.confirmed.isEmpty())
        assertFalse(result.complete)
        assertTrue(result.problems.any { it.contains("Неизвестен формат") })
    }

    @Test fun negativeReplyCannotCountAsPositiveMode() {
        val frames = ElmFrames.parse("03", "7E8037F0311\r>")
        val result = ObdDecoder.assessMode(frames, DtcKind.CONFIRMED, true, setOf("7E8"))
        assertTrue(result.confirmed.isEmpty())
        assertFalse(result.complete)
        assertTrue(result.problems.any { it.contains("0x11") && it.contains("7E8") })
    }
}
