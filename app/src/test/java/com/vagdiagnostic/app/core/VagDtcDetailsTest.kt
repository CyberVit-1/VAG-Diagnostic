package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagDtcDetailsTest {
    private val kwp = Dtc("051824", "01304", DtcKind.OEM)
    private val uds = Dtc("123456", "123456", DtcKind.OEM)
    @Test fun requestsUseOnlyTheActualDtcAndFixedReadServices() {
        assertEquals("1200040518", VagDtcDetails.request(VagDtcDetails.Protocol.KWP, kwp).command)
        assertEquals("1906123456FF", VagDtcDetails.request(VagDtcDetails.Protocol.UDS, uds).command)
        for (fault in listOf(uds.copy(kind = DtcKind.CONFIRMED), uds.copy(raw = "1234"), uds.copy(raw = "12345600")))
            assertTrue(runCatching { VagDtcDetails.request(VagDtcDetails.Protocol.UDS, fault) }.isFailure)
    }
    @Test fun aCompleteRawEnvelopeDoesNotClaimDecodedManufacturerRecords() {
        val request = VagDtcDetails.request(VagDtcDetails.Protocol.UDS, uds)
        for (hex in listOf("590612345608", "590612345608010203")) {
            val bytes = VagOemReadOnly.bytes(hex)
            val reply = VagDtcDetails.decode(request, bytes)
            assertEquals(VagDtcDetails.State.RAW_RESPONSE, reply.state)
            assertEquals(bytes, reply.raw)
        }
    }
    @Test fun wrongCodeSubfunctionSidAndTruncationAreRejected() {
        val request = VagDtcDetails.request(VagDtcDetails.Protocol.UDS, uds)
        for (hex in listOf("590612345708010203", "590412345608", "620612345608", "7F1978", "7F1231"))
            assertTrue(hex, runCatching { VagDtcDetails.decode(request, VagOemReadOnly.bytes(hex)) }.isFailure)
    }
    @Test fun matchingCompleteEnvelopeWithInvalidOptionalRecordsIsNonfatal() {
        val request = VagDtcDetails.request(VagDtcDetails.Protocol.UDS, uds)
        assertEquals(VagDtcDetails.State.INVALID_RECORDS,
            VagDtcDetails.decode(request, VagOemReadOnly.bytes("5906123456")).state)
        val kwpRequest = VagDtcDetails.request(VagDtcDetails.Protocol.KWP, kwp)
        val raw = VagOemReadOnly.bytes("520001020304")
        val result = VagDtcDetails.decode(kwpRequest, raw)
        assertEquals(VagDtcDetails.State.INVALID_RECORDS, result.state)
        assertEquals(raw, result.raw)
        assertNull(result.kwpRecords)
    }
    @Test fun noDataAndFinalRejectionDoNotBecomeAnEmptyFaultList() {
        val request = VagDtcDetails.request(VagDtcDetails.Protocol.KWP, kwp)
        assertEquals(VagDtcDetails.State.NO_DATA, VagDtcDetails.decode(request, null).state)
        val rejected = VagDtcDetails.decode(request, listOf(0x7F, 0x12, 0x31))
        assertEquals(VagDtcDetails.State.REJECTED, rejected.state)
        assertEquals(0x31, rejected.nrc)
        assertTrue(rejected.raw.isEmpty())
        assertEquals(VagDtcDetails.State.INVALID_RECORDS, VagDtcDetails.decode(request, listOf(0x52, 0, 0, 0)).state)
    }
}
