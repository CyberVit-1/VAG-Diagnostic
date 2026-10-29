package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic bitmaps, not responses recorded from a vehicle. */
class VagInstallationBitmapTest {
    private fun reply(size: Int, vararg bits: Int, faults: Boolean = false): List<Int> {
        val data = MutableList(size) { 0 }
        for (bit in bits) data[bit / 8] = data[bit / 8] or (0x80 ushr (bit % 8))
        return listOf(0x62, 0x2A, if (faults) 0x28 else 0x26) + data
    }
    @Test fun gatewayPartSelectsDifferentMapsForTheSameBit() {
        val common = VagInstallationBitmap.decode(0x19, "5Q0 907 530", reply(32, 7, 112, 113, 255))
        assertEquals("common", common.format)
        assertEquals(listOf(0x19, 2, 1), common.ids)
        assertEquals(listOf(255), common.unknownBits)
        val modern = VagInstallationBitmap.decode(0x19, "5WA907530", reply(32, 7, 96, 97, 248))
        assertEquals("mk8", modern.format)
        assertEquals(listOf(0x19, 2, 0xFF, 0x22), modern.ids)
        val engine = modern.entries.single { it.id == 0xFF }
        assertEquals(0x17FC0076, engine.udsTx)
        assertTrue(engine.is29Bit)
        assertNull(engine.tp)
    }
    @Test fun submoduleListsRequireTheirActualOwnerAndLength() {
        assertEquals(listOf(0xBB, 0x42), VagInstallationBitmap.decode(0x42, "", reply(1, 6, 7)).ids)
        assertEquals(listOf(0xBC, 0x52), VagInstallationBitmap.decode(0x52, "", reply(1, 6, 7)).ids)
        assertEquals(listOf(5, 9), VagInstallationBitmap.decode(9, "", reply(2, 3, 7)).ids)
        assertTrue(runCatching { VagInstallationBitmap.decode(0x19, "5WA907530", reply(1, 7)) }.isFailure)
        assertTrue(runCatching { VagInstallationBitmap.decode(9, "", reply(1, 7)) }.isFailure)
    }
    @Test fun c7AllowsOnlyKnownLengthsAndMergesDuplicateIndications() {
        for (size in listOf(9, 10, 12)) {
            val decoded = VagInstallationBitmap.decode(0x19, "4G0907468", reply(size, 0, 2, 3, 20))
            assertEquals(listOf(1, 3, 0x19), decoded.ids)
            assertEquals(1, decoded.entries.count { it.id == 3 })
            assertEquals(0x713, decoded.entries.single { it.id == 3 }.udsTx)
        }
        assertTrue(runCatching { VagInstallationBitmap.decode(0x19, "4G0907468", reply(11, 0)) }.isFailure)
    }
    @Test fun malformedUnknownAndFaultListsCannotBecomeInstallationSuccess() {
        for (part in listOf("", "UNKNOWN", "1K0907530"))
            assertTrue(runCatching { VagInstallationBitmap.decode(0x19, part, reply(32, 7)) }.isFailure)
        for (bad in listOf(listOf(0x7F, 0x22, 0x31), listOf(0x62, 0x2A, 0x26),
                reply(32, 7, faults = true), reply(32, 7).dropLast(1), reply(32, 7) + 0))
            assertTrue(runCatching { VagInstallationBitmap.decode(0x19, "5Q0907530", bad) }.isFailure)
        assertEquals(listOf(0x19), VagInstallationBitmap.decode(0x19, "5Q0907530", reply(32, 7, faults = true), faults = true).ids)
        assertTrue(VagInstallationBitmap.decode(0x19, "5Q0907530", reply(32)).ids.isEmpty())
    }
}
