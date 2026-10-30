package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic payloads exercise framing, not actual vehicle operation. */
class VagUdsDtcRecordsTest {
    private fun reply(body: List<Int>, status: Int = 8) = listOf(0x59, 0x06, 0x12, 0x34, 0x56, status) + body
    @Test fun asamIdentityUsesTheConfirmedRevisionPrefix() {
        assertEquals("EV_BCM37w_005", VagUdsDtcRecords.asamKey("EV_BCM37w", "005999"))
        for ((name, rev) in listOf(null to "001", "EV_TEST" to null, "EV TEST" to "001", "EV_TEST" to ""))
            assertNull(VagUdsDtcRecords.asamKey(name, rev))
    }
    @Test fun commonRecordLayoutUsesItsOwnRawFieldsAndPreservesStatus() {
        val parsed = VagUdsDtcRecords.decode(reply(listOf(1, 2, 3)), "EV_UNKNOWN_001")
        assertTrue(parsed.complete)
        assertEquals(8, parsed.status)
        assertEquals(1, parsed.records.single().id)
        assertEquals(listOf(2L, 3L), parsed.records.single().fields.map { it.value })
        assertEquals(listOf(1, 2, 3), parsed.records.single().raw)
    }
    @Test fun engineWildcardSelectsOnlyTheVerifiedEngineRecordLayout() {
        val body = listOf(112, 0x12, 0x34, 20, 21, 22, 23, 24, 0x56, 0x78)
        val parsed = VagUdsDtcRecords.decode(reply(body), "EV_ECM_UNKNOWN_003")
        assertTrue(parsed.complete)
        assertEquals(listOf(0x1234L, 20L, 21L, 22L, 23L, 24L, 0x5678L), parsed.records.single().fields.map { it.value })
        val unknown = VagUdsDtcRecords.decode(reply(body), "EV_UNKNOWN_003")
        assertFalse(unknown.complete)
        assertTrue(unknown.records.isEmpty())
        assertEquals(body, unknown.opaqueTail)
    }
    @Test fun emptySpecificEntryAndUnknownIdUseConfirmedModelDefaults() {
        for (id in listOf(113, 0xEE)) {
            val body = listOf(id, 0xFF, 2, 99, 4, 5, 1, 10, 11)
            val parsed = VagUdsDtcRecords.decode(reply(body), "EV_BCM37w_005")
            assertTrue(parsed.complete)
            assertEquals(listOf(id, 1), parsed.records.map { it.id })
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L, 3L, 2L, 4L, 5L), parsed.records.first().fields.map { it.value })
            assertEquals(listOf(10L, 11L), parsed.records.last().fields.map { it.value })
        }
    }
    @Test fun inapplicableSpecificEntryDoesNotGuessDefaultRecordLength() {
        val body = listOf(4, 0xFF, 2, 3, 4, 5, 1, 10, 11)
        val parsed = VagUdsDtcRecords.decode(reply(body), "EV_BCM37w_005")
        assertFalse(parsed.complete)
        assertTrue(parsed.records.isEmpty())
        assertEquals(body, parsed.opaqueTail)
    }
    @Test fun unknownLayoutPreservesAllRemainingBytesAfterKnownRecords() {
        val body = listOf(1, 2, 3, 0xEE, 4, 5, 1, 99, 88)
        val parsed = VagUdsDtcRecords.decode(reply(body), "EV_UNKNOWN_001")
        assertFalse(parsed.complete)
        assertEquals(1, parsed.records.size)
        assertEquals(body.drop(3), parsed.opaqueTail)
    }
    @Test fun duplicateRecordIdsAndTruncatedKnownBodiesAreRejected() {
        for (body in listOf(listOf(1), listOf(1, 2), listOf(1, 2, 3, 1, 4, 5)))
            assertTrue(runCatching { VagUdsDtcRecords.decode(reply(body), "EV_UNKNOWN_001") }.isFailure)
        val request = VagDtcDetails.request(VagDtcDetails.Protocol.UDS, Dtc("123456", "123456", DtcKind.OEM))
        val raw = reply(listOf(1, 2, 3, 1, 4, 5))
        val result = VagDtcDetails.decode(request, raw, "EV_UNKNOWN_001")
        assertEquals(VagDtcDetails.State.INVALID_RECORDS, result.state)
        assertEquals(raw, result.raw)
    }
    @Test fun noAdditionalRecordsNeverCreatesFaultsOrInventsAStatus() {
        val parsed = VagUdsDtcRecords.decode(reply(emptyList(), status = 0x24), "EV_UNKNOWN_001")
        assertTrue(parsed.complete)
        assertTrue(parsed.records.isEmpty())
        assertEquals(0x24, parsed.status)
    }
}
