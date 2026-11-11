package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagGatewayDiscoveryTest {
    private fun reply(did: Int, vararg bytes: Int) = listOf(0x62, did ushr 8, did and 255) + bytes.toList()
    private fun bitmap(did: Int) = reply(did, *IntArray(32) { if (it == 1) 0x40 else 0 })
    @Test fun logicalAddressesPreserveUnknownIdsAndDeduplicateWithoutGuessingRoutes() {
        val result = VagGatewayDiscovery.logicalList(reply(0x2A2A, 0x19, 3, 3, 0xF1))
        assertEquals(listOf(0x19, 3, 0xF1), result.entries.map { it.id })
        assertTrue(result.entries.all { it.installationConfirmed })
        assertNull(result.entries.last().mappedRoute)
        assertTrue(result.entries.all { it.rawStatus == null && !it.hasFaultFlag })
    }
    @Test fun malformedLogicalListsCannotAuthorizeAnyRoute() {
        assertTrue(VagGatewayDiscovery.logicalList(reply(0x2A2A)).entries.isEmpty())
        for (bad in listOf(reply(0x2A26, 3), reply(0x2A2A, 0), reply(0x2A2A, 256),
            listOf(0x7F, 0x22, 0x31), listOf(0x62)))
            assertTrue(bad.toString(), runCatching { VagGatewayDiscovery.logicalList(bad) }.isFailure)
    }
    @Test fun addressIndexesResolveOnlyCatalogRoutesAndMergeRepeatedPhysicalAddresses() {
        val result = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0x13, 0, 0x12, 0, 0x13))
        assertEquals(listOf(3, 0x44), result.entries.map { it.id })
        assertEquals(listOf(0x713, 0x712), result.entries.map { it.mappedRoute?.udsTx })
        assertTrue(result.entries.all { it.installationConfirmed })
        assertTrue(result.limitations.isEmpty())
    }
    @Test fun powertrainIndexKeepsKnownPhysicalAlternativesUnderOneLogicalEngine() {
        val result = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0x76))
        assertEquals(1, result.entries.single().id)
        assertTrue(result.entries.single().installationConfirmed)
        assertTrue(result.limitations.isEmpty())
        val route = requireNotNull(result.entries.single().mappedRoute)
        assertEquals(listOf(0x7E0, 0x17FC0076), route.udsPaths(true).map { it.udsTx })
        assertEquals(listOf(0x7E0), route.udsPaths(false).map { it.udsTx })
    }
    @Test fun unknownOrTruncatedIndexesCannotCreateCanAddresses() {
        val unknown = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0xFF, 0xFF))
        assertTrue(unknown.entries.isEmpty()); assertEquals(1, unknown.limitations.size)
        assertTrue(VagGatewayDiscovery.addressIndexes(reply(0xF1B7)).entries.isEmpty())
        for (bad in listOf(reply(0xF1B7, 0), reply(0xF1B8, 0, 0x13)))
            assertTrue(runCatching { VagGatewayDiscovery.addressIndexes(bad) }.isFailure)
    }
    @Test fun codedAndEventBitmapsRemainHintsWithTheirOwnProvenance() {
        val coded = VagGatewayDiscovery.bitmap("5Q0907530", bitmap(0x04A3)).entries.single()
        val events = VagGatewayDiscovery.bitmap("5Q0907530", bitmap(0x2A28), true).entries.single()
        assertEquals(9, coded.id); assertEquals(9, events.id)
        assertFalse(coded.installationConfirmed); assertFalse(events.installationConfirmed)
        assertFalse(coded.hasFaultFlag); assertTrue(events.hasFaultFlag)
        assertEquals(setOf(VagOemReadOnly.DiscoverySource.CODED), coded.discoverySources)
        assertEquals(setOf(VagOemReadOnly.DiscoverySource.EVENTS), events.discoverySources)
    }
    @Test fun supplementalBitmapCannotBorrowAnotherFamilyOrDid() {
        for (part in listOf("", "UNKNOWN", "1K0907530", "5WA907530", "4G0907468"))
            assertTrue(runCatching { VagGatewayDiscovery.bitmap(part, bitmap(0x04A3)) }.isFailure)
        for (bad in listOf(bitmap(0x2A26), bitmap(0x04A3).dropLast(1), bitmap(0x04A3) + 0))
            assertTrue(runCatching { VagGatewayDiscovery.bitmap("5Q0907530", bad) }.isFailure)
    }
    @Test fun mergingSourcesPreservesEvidenceWithoutPromotingAHint() {
        val coded = VagGatewayDiscovery.bitmap("5Q0907530", bitmap(0x04A3))
        val events = VagGatewayDiscovery.bitmap("5Q0907530", bitmap(0x2A28), true)
        val hints = VagGatewayDiscovery.merge(listOf(coded, events))
        assertFalse(hints.entries.single().installationConfirmed)
        val installed = VagGatewayDiscovery.logicalList(reply(0x2A2A, 9))
        val result = VagGatewayDiscovery.merge(listOf(hints, installed, installed))
        assertTrue(result.entries.single().installationConfirmed)
        assertEquals(3, result.entries.single().discoverySources.size)
    }
    @Test fun conflictingEvidenceKeepsTheIdButDisablesItsRoute() {
        val original = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0x13))
        val changed = original.entries.single().copy(mappedRoute = VagOemReadOnly.routes.getValue(3).copy(udsTx = 0x714))
        assertTrue(VagGatewayDiscovery.merge(listOf(original, VagGatewayDiscovery.Result(listOf(changed)))).entries.single().routeConflict)
    }
    @Test fun physicalCatalogLookupHasOneLogicalOwnerPerEndpoint() {
        val owners = VagOemReadOnly.routes.values.filter { it.udsTx != null }.groupBy { it.udsTx }
        assertTrue(owners.filterValues { it.size > 1 }.toString(), owners.values.all { it.size == 1 })
        val repeatedGateway = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0, 0, 0x10))
        assertEquals(listOf(0x19), repeatedGateway.entries.map { it.id })
    }
    @Test fun logicalFfCannotReuseTheInternalEvoEngineMarker() {
        val result = VagGatewayDiscovery.logicalList(reply(0x2A2A, 1, 0xFE, 0xFF))
        assertEquals(listOf(1, 0xFE, 0xFF), result.entries.map { it.id })
        assertTrue(result.entries.drop(1).all { requireNotNull(it.mappedRoute).udsPaths(true).isEmpty() })
        val gearbox = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0x77)).entries.single()
        assertEquals(2, gearbox.id)
        assertEquals(listOf(0x7E1, 0x17FC0077), requireNotNull(gearbox.mappedRoute).udsPaths(true).map { it.udsTx })
        val drive = VagGatewayDiscovery.addressIndexes(reply(0xF1B7, 0, 0x7C)).entries.single()
        assertEquals(0x51, drive.id)
        assertEquals(listOf(0x7E6, 0x17FC007C), requireNotNull(drive.mappedRoute).udsPaths(true).map { it.udsTx })
    }
    @Test fun mergingAWeakerHintCannotDiscardVerifiedAlternativeRoutes() {
        val logical = VagGatewayDiscovery.logicalList(reply(0x2A2A, 1))
        val coded = VagGatewayDiscovery.bitmap("5Q0907530", reply(0x04A3, *IntArray(32) { if (it == 14) 0x40 else 0 }))
        for (parts in listOf(listOf(coded, logical), listOf(logical, coded))) {
            val result = VagGatewayDiscovery.merge(parts).entries.single()
            assertEquals(1, result.id); assertTrue(result.installationConfirmed)
            assertEquals(listOf(0x7E0, 0x17FC0076), requireNotNull(result.mappedRoute).udsPaths(true).map { it.udsTx })
        }
    }
}
