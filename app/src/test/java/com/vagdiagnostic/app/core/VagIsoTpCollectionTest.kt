package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagIsoTpCollectionTest {
    @Test fun interleavedOwnersDoNotSharePayloadOrSequence() {
        val c = VagIsoTpCollection(setOf(0x77A, 0x7B4), "222A26")
        c.accept("77A100A622A26010203\r7B404622A2680")
        assertEquals(setOf(0x77A), c.flowControlNeeded)
        assertEquals(listOf(0x62, 0x2A, 0x26, 0x80), c.payloads.getValue(0x7B4))
        c.flowControlSent(0x77A)
        c.accept("77A2104050607000000")
        assertEquals(listOf(0x62, 0x2A, 0x26, 1, 2, 3, 4, 5, 6, 7), c.payloads.getValue(0x77A))
        assertTrue(c.unfinished.isEmpty())
    }
    @Test fun pendingFirstFrameAndFinalStayInTheSameRead() {
        val c = VagIsoTpCollection(setOf(0x77A), "222A26")
        c.accept("77A037F2278")
        assertEquals(setOf(0x77A), c.pending)
        c.accept("77A1008622A26010203")
        c.flowControlSent(0x77A)
        c.accept("77A2104050000000000")
        assertTrue(c.pending.isEmpty())
        assertEquals(8, c.payloads.getValue(0x77A).size)
    }
    @Test fun missingFlowControlWrongSequenceAndWrongDidCannotProduceACompleteReply() {
        for (mode in listOf("no-fc", "sequence", "did")) {
            val c = VagIsoTpCollection(setOf(0x77A), "222A26")
            c.accept("77A1008622A${if (mode == "did") "28" else "26"}010203")
            if (mode != "no-fc") c.flowControlSent(0x77A)
            assertTrue(mode, runCatching { c.accept("77A2${if (mode == "sequence") "2" else "1"}04050000000000") }.isFailure)
            assertTrue(c.payloads.isEmpty())
        }
    }
    @Test fun unknownRespondersCannotBecomeKnownEcusOrReceiveFlowControl() {
        val c = VagIsoTpCollection(setOf(0x77A), "222A26")
        c.accept("7B4100A622A26010203\r77A037F2231")
        assertEquals(setOf(0x7B4), c.unknownIds)
        assertTrue(c.flowControlNeeded.isEmpty())
        assertEquals(listOf(0x7F, 0x22, 0x31), c.payloads.getValue(0x77A))
    }
    @Test fun conflictingFinalResponsesAreRejected() {
        val c = VagIsoTpCollection(setOf(0x7B4), "222A26")
        c.accept("7B404622A2680")
        assertTrue(runCatching { c.accept("7B404622A2640") }.isFailure)
        assertTrue(c.payloads.isEmpty())
        assertEquals(setOf(0x7B4), c.invalidIds)
    }
    @Test fun shortFinalOrIntermediateCfCannotBeCompletedByInventingAnotherFrame() {
        for (length in listOf(8, 14)) {
            val c = VagIsoTpCollection(setOf(0x77A, 0x7B4), "222A26")
            c.accept("7B404622A2680\r77A10%02X622A26010203".format(length))
            c.flowControlSent(0x77A)
            assertTrue(runCatching { c.accept("77A2104") }.isFailure)
            assertFalse(c.payloads.containsKey(0x77A))
            assertEquals(listOf(0x62, 0x2A, 0x26, 0x80), c.payloads[0x7B4])
        }
    }
    @Test fun extendedHeaderCannotMasqueradeAsKnownStandardId() {
        val c = VagIsoTpCollection(setOf(0x77A), "222A26")
        assertTrue(runCatching { c.accept("0000077A04622A2680") }.isFailure)
        assertTrue(c.payloads.isEmpty())
        assertEquals(setOf(0x77A), c.invalidIds)
    }
}
