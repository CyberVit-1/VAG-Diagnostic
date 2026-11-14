package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic records, not vehicle evidence. */
class VagKwpDtcRecordsTest {
    private fun reply(data: List<Int>) = listOf(0x52, 0) + data + listOf(0, 0x05, 0x18)
    @Test fun fixedRecordsKeepTheirOwnBoundariesAndRawValues() {
        val parsed = VagKwpDtcRecords.decode(reply(listOf(1, 0x12, 0x34, 7, 0x56, 0x78)))
        assertTrue(parsed.complete)
        assertEquals(listOf(1, 7), parsed.records.map { it.id })
        assertEquals(listOf(0x1234L, 0x5678L), parsed.records.map { it.fields.single().value })
    }
    @Test fun longRecordMasksDoNotShiftTheFollowingRecord() {
        val long = listOf(0x6C, 0, 0, 2, 3, 4, 0, 1, 2, 0, 1, 0xFC, 0x3E, 0xF0, 0x3F)
        val parsed = VagKwpDtcRecords.decode(reply(long + listOf(1, 0, 10)))
        assertTrue(parsed.complete)
        assertEquals(listOf(0x6C, 1), parsed.records.map { it.id })
        val fields = parsed.records.first().fields
        assertEquals(listOf(2L, 3L, 4L, 258L), fields.take(4).map { it.value })
        assertEquals(127L, fields.first { it.offset == 10 }.value)
        assertEquals(63L, fields.last().value)
        assertEquals(10L, parsed.records.last().fields.single().value)
    }
    @Test fun variableTextLengthsAreCheckedWithoutGuessingEncoding() {
        for (id in listOf(0x5F, 0x76)) {
            val parsed = VagKwpDtcRecords.decode(reply(listOf(id, 3, 0xC4, 0xFF, 65, 1, 0, 9)))
            assertTrue(parsed.complete)
            assertEquals(listOf(0xC4, 0xFF, 65), parsed.records.first().textBytes)
            assertEquals(9L, parsed.records.last().fields.single().value)
        }
    }
    @Test fun unknownRecordPreservesOpaqueTailInsteadOfInventingLaterFields() {
        val data = listOf(1, 0, 10, 0xEE, 0, 5, 7, 0, 99)
        val parsed = VagKwpDtcRecords.decode(reply(data))
        assertFalse(parsed.complete)
        assertEquals(1, parsed.records.size)
        assertEquals(data.drop(3), parsed.opaqueTail)
    }
    @Test fun truncatedKnownRecordsAndUnboundedFieldCountsAreRejected() {
        for (data in listOf(listOf(1), listOf(1, 0), listOf(0x6C, 0, 0), listOf(0x5F, 5, 65),
                List(114) { listOf(1, 0, 1) }.flatten()))
            assertTrue(data.toString(), runCatching { VagKwpDtcRecords.decode(reply(data)) }.isFailure)
        assertTrue(VagKwpDtcRecords.decode(reply(emptyList())).complete)
    }
}
