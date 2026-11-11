package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic complete-message format, never a physical adapter result. */
class VagUdsNativeTransactionTest {
    @Test fun assembledPayloadHasOwnerButNoPci() {
        val result = VagUdsNativeTransaction.decode("77A59028D0102030804050608", 0x77A, "1902AF")
        assertEquals(VagOemReadOnly.bytes("59028D0102030804050608"), result.payload)
        assertEquals(0, result.pendingCount)
    }
    @Test fun exactTwentyNineBitOwnerIsRetained() {
        assertEquals(listOf(0x59, 2, 0x8D), VagUdsNativeTransaction.decode("17FE008B59028D", 0x17FE008B, "1902AF").payload)
        assertInvalid("17FE008C59028D", 0x17FE008B)
    }
    @Test fun pendingMessagesPrecedeOneMatchingFinalWithoutResend() {
        val result = VagUdsNativeTransaction.decode("77A7F1978\r77A7F1978\r77A59028D", 0x77A, "1902AF")
        assertEquals(2, result.pendingCount)
        assertEquals(listOf(0x59, 2, 0x8D), result.payload)
        assertNull(VagUdsNativeTransaction.decode("77A7F1978", 0x77A, "1902AF").payload)
    }
    @Test fun matchingFinalNegativeIsNotAnEmptyDtcSuccess() {
        assertEquals(listOf(0x7F, 0x19, 0x31), VagUdsNativeTransaction.decode("77A7F1931", 0x77A, "1902AF").payload)
        assertInvalid("77A7F2231")
    }
    @Test fun rawCanAndDifferentSubfunctionCannotBeGuessedAsNative() {
        assertInvalid("77A0359028D")
        assertInvalid("77A59038D")
        assertInvalid("77A62017400")
    }
    @Test fun didEchoMustMatchAndRequestEchoMayBeRemoved() {
        assertEquals(listOf(0x62, 0x01, 0x74, 0),
            VagUdsNativeTransaction.decode("220174\r77A62017400", 0x77A, "220174").payload)
        assertTrue(runCatching { VagUdsNativeTransaction.decode("77A62017500", 0x77A, "220174") }.isFailure)
    }
    @Test fun contradictoryRepliesAndPendingAfterFinalAreRejected() {
        assertInvalid("77A59028D\r77A7F1978")
        assertInvalid("77A59028D\r77A59028D01020308")
        assertInvalid(List(9) { "77A7F1978" }.joinToString("\r"))
    }
    @Test fun statusNoiseMalformedHexAndWrongOwnerCannotProduceSuccess() {
        for (raw in listOf("", "NO DATA", "NO DATA\r77A59028D", "7E859028D", "77A59028", "77AGG")) assertInvalid(raw)
    }
    private fun assertInvalid(raw: String, owner: Int = 0x77A) {
        assertTrue(raw.take(40), runCatching { VagUdsNativeTransaction.decode(raw, owner, "1902AF") }.exceptionOrNull() is IllegalArgumentException)
    }
}
