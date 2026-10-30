package com.vagdiagnostic.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VagUdsInfoTest {
    private fun reply(field: VagUdsInfo.Field, body: List<Int>) = listOf(0x62, field.did ushr 8, field.did and 255) + body
    @Test fun scanUsesOnlyVerifiedFixedIdentificationDids() {
        assertEquals(listOf("22F197", "22F189", "22F19E"), VagUdsInfo.scanFields.map { it.request })
        assertEquals("220174", VagUdsInfo.Field.ACCESS_STATUS.request)
        assertFalse(VagUdsInfo.Field.entries.any { it.request in setOf("22F190", "2701", "2E539B00") })
    }
    @Test fun componentNormalizesWhitespaceAndDecodesLatin1WithoutTruncating() {
        val value = VagUdsInfo.decode(VagUdsInfo.Field.COMPONENT,
            reply(VagUdsInfo.Field.COMPONENT, listOf(32, 0xC4, 9, 32, 65, 13, 10, 66, 0, 0)))
        assertEquals(VagUdsInfo.State.PRESENT, value.state)
        assertEquals("Ä A B", value.text)
    }
    @Test fun noDataFinalRejectionAndInvalidOptionalBodyHaveDistinctStates() {
        val field = VagUdsInfo.Field.SOFTWARE
        assertEquals(VagUdsInfo.State.NO_DATA, VagUdsInfo.decode(field, null).state)
        for (nrc in listOf(0x11, 0x12, 0x31, 0x33)) {
            val value = VagUdsInfo.decode(field, listOf(0x7F, 0x22, nrc))
            assertEquals(VagUdsInfo.State.REJECTED, value.state)
            assertEquals(nrc, value.nrc)
        }
        for (body in listOf(emptyList(), listOf(1, 2), List(33) { 65 }))
            assertEquals(VagUdsInfo.State.INVALID, VagUdsInfo.decode(field, reply(field, body)).state)
    }
    @Test fun foreignDidSidAndPendingCannotBeConsumedAsOptionalRejection() {
        for (raw in listOf(listOf(0x62, 0xF1, 0x89, 65), listOf(0x7F, 0x19, 0x31), listOf(0x7F, 0x22, 0x78)))
            assertTrue(runCatching { VagUdsInfo.decode(VagUdsInfo.Field.COMPONENT, raw) }.isFailure)
    }
    @Test fun optionalFailuresDoNotSkipLaterFields() = runBlocking {
        val commands = mutableListOf<String>()
        val result = VagUdsInfo.collect(VagUdsInfo.scanFields) { command ->
            commands += command
            when (command) {
                "22F197" -> null
                "22F189" -> listOf(0x7F, 0x22, 0x31)
                else -> reply(VagUdsInfo.Field.ASAM, "EV_TEST_UNIT".map { it.code })
            }
        }
        assertEquals(listOf("22F197", "22F189", "22F19E"), commands)
        assertEquals("EV_TEST_UNIT", result[VagUdsInfo.Field.ASAM]?.text)
    }
    @Test fun completedFieldsSurviveLaterIoFailureAndCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            val saved = linkedMapOf<VagUdsInfo.Field, VagUdsInfo.Value>()
            val commands = mutableListOf<String>()
            val failure = runCatching {
                VagUdsInfo.collect(VagUdsInfo.scanFields, onValue = { field, value -> saved[field] = value }) { command ->
                    commands += command
                    if (command == "22F189") {
                        if (cancel) throw kotlinx.coroutines.CancellationException("Stop")
                        throw java.io.IOException("Disconnected")
                    }
                    reply(VagUdsInfo.Field.COMPONENT, "TEST UNIT".map { it.code })
                }
            }.exceptionOrNull()
            if (cancel) assertTrue(failure is kotlinx.coroutines.CancellationException)
            else assertTrue(failure is java.io.IOException)
            assertEquals(listOf("22F197", "22F189"), commands)
            assertEquals(setOf(VagUdsInfo.Field.COMPONENT), saved.keys)
            assertEquals("TEST UNIT", saved.values.single().text)
        }
    }
    @Test fun accessStatusRequiresCompleteFourByteBodyWithoutInventingBitMeaning() {
        val field = VagUdsInfo.Field.ACCESS_STATUS
        assertEquals(listOf(0, 1, 2, 3), VagUdsInfo.decode(field, reply(field, listOf(0, 1, 2, 3))).raw)
        for (body in listOf(listOf(3), listOf(0, 1, 2), listOf(0, 1, 2, 3, 4)))
            assertEquals(VagUdsInfo.State.INVALID, VagUdsInfo.decode(field, reply(field, body)).state)
    }
}
