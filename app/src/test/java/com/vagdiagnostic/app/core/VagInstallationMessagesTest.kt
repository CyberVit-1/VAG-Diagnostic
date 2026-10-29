package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagInstallationMessagesTest {
    private fun parse(vararg hex: String) = VagOemReadOnly.installed04A1Messages(hex.map(VagOemReadOnly::bytes))
    @Test fun duplicateRecordsRetainRawObservationsAndDoNotInflateCount() {
        val entries = parse("6204A1191F0100", "62191F050001010100")
        assertEquals(listOf(0x19, 1), entries.map { it.id })
        assertEquals(1, entries.first().rawStatus)
        assertTrue(entries.first().hasFaultFlag)
        assertEquals(2, entries.first().observations.size)
        assertFalse(entries.first().routeConflict)
    }
    @Test fun conflictingRoutesRemainVisibleButCannotBePolled() {
        val entry = parse("6204A1191F0100", "6219200500").single()
        assertTrue(entry.routeConflict)
        assertEquals(2, entry.observations.size)
    }
    @Test fun incompleteWrongServiceOrWrongDidCannotBecomeAValidList() {
        for (parts in listOf(listOf("6204A1191F0100", "6201"), listOf("6204A1191F0100", "7F2231"),
            listOf("6204A2191F0100"), listOf("6204A1191F0100", "6204A101010100")))
            assertTrue(parts.toString(), runCatching { parse(*parts.toTypedArray()) }.isFailure)
    }
}
