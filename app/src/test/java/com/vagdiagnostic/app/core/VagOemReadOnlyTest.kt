package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagOemReadOnlyTest {
    @Test fun actual04A1RecordsUseThirdBytePresenceRatherThanFourthByte() {
        val found = VagOemReadOnly.installed04A1(VagOemReadOnly.bytes("6204A101010100111500000202010003030100"))
        assertEquals(listOf(1, 2, 3), found.map { it.id })
        assertEquals(listOf(1, 2, 3), found.map { it.tpDestination })
        assertEquals(listOf(0, 0, 0), found.map { it.extraStatus })
    }
    @Test fun expandedRoutesAreClosedAndAddedOnlyForGatewayListedModules() {
        val expected = mapOf(0x04 to 0x13, 0x25 to 0x14, 0x62 to 0x24, 0x72 to 0x25, 0x56 to 0x52)
        assertEquals(19, VagOemReadOnly.routes.size)
        for ((id, tp) in expected) {
            assertEquals(tp, VagOemReadOnly.routes.getValue(id).tp)
            assertTrue(VagOemReadOnly.routes.getValue(id).installedOnly)
        }
        assertNull(VagOemReadOnly.routes.getValue(0x62).udsTx)
        assertNull(VagOemReadOnly.routes.getValue(0x72).udsTx)
        assertFalse(VagOemReadOnly.routes.containsKey(0x7F))
    }
    @Test fun comfort29BitPairAndListGateAreExplicit() {
        val route = VagOemReadOnly.routes.getValue(0x46)
        assertEquals(listOf(0x70D), route.udsPaths(false).map { it.udsTx })
        val paths = route.udsPaths(true)
        assertEquals(listOf(0x70D, 0x17FC008B), paths.map { it.udsTx })
        assertEquals(listOf(0x777, 0x17FE008B), paths.map { it.udsRx })
        assertFalse(paths.first().is29Bit)
        assertTrue(paths.last().is29Bit)
    }
    @Test fun addressNamespacesAndStrictInstallationList() {
        assertEquals(0x19, VagOemReadOnly.gateway.id)
        assertEquals(0x1F, VagOemReadOnly.gateway.tp)
        assertEquals(0x710, VagOemReadOnly.gateway.udsTx)
        assertEquals(0x77A, VagOemReadOnly.gateway.udsRx)
        val found = VagOemReadOnly.installedKwp(VagOemReadOnly.bytes("5A9F090100000119000005"))
        assertEquals(listOf(1, 0x19), found.map { it.id })
        assertTrue(found.last().hasFaultFlag)
        for (bad in listOf("5A9E0100", "5A9F0901000001", "7F1A11", "5A9F0500000001")) {
            assertTrue(bad, runCatching { VagOemReadOnly.installedKwp(VagOemReadOnly.bytes(bad)) }.isFailure)
        }
    }
    @Test fun zeroAndIncompleteDtcAreDifferentAndStatusIsNotInvented() {
        assertTrue(VagOemReadOnly.kwpFaults(VagOemReadOnly.bytes("5800"), false).values.isEmpty())
        val dtc = VagOemReadOnly.kwpFaults(VagOemReadOnly.bytes("5801029908"), true).values.single()
        assertEquals("P0299", dtc.code)
        assertEquals(DtcKind.OEM, dtc.kind)
        assertTrue(dtc.statusText!!.contains("08"))
        for (bad in listOf("", "5801", "58011234", "7F1812", "7F1878"))
            assertTrue(bad, runCatching { VagOemReadOnly.kwpFaults(VagOemReadOnly.bytes(bad), true) }.isFailure)
    }
    @Test fun udsRetains24BitCodeAndAvailableMask() {
        val dtc = VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("59028D12345608")).values.single()
        assertEquals("123456", dtc.code)
        assertTrue(dtc.statusText!!.contains("8D"))
        assertTrue(runCatching { VagOemReadOnly.udsFaults(VagOemReadOnly.bytes("59028D1234")) }.isFailure)
    }
}
