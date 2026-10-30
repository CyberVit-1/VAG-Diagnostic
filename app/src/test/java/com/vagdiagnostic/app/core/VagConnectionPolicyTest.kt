package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagConnectionPolicyTest {
    @Test fun unknownGenericEcuStartsWithKnownTpThenUdsOnlyAfterAbsence() {
        val s = VagConnectionPolicy.State(hasTp = true, hasUds = true)
        assertTrue(VagConnectionPolicy.mustConnectToTp(s))
        assertFalse(VagConnectionPolicy.mustConnectToUds(s, false))
        assertTrue(VagConnectionPolicy.mustConnectToUds(s, true))
    }
    @Test fun knownUdsAndUdsOnlyChassisDoNotInheritAnotherEcusTpConnection() {
        for (s in listOf(VagConnectionPolicy.State(true, true, connectedUds = true),
            VagConnectionPolicy.State(true, true, udsOnlyChassis = true))) {
            assertFalse(VagConnectionPolicy.mustConnectToTp(s))
            assertTrue(VagConnectionPolicy.mustConnectToUds(s, false))
        }
    }
    @Test fun explicitRouteAndCachedAbsenceHaveTheirOwnBoundaries() {
        val explicit = VagConnectionPolicy.State(true, true, explicit = VagConnectionPolicy.Explicit.TP20)
        assertTrue(VagConnectionPolicy.mustConnectToTp(explicit))
        assertFalse(VagConnectionPolicy.mustConnectToUds(explicit, true))
        val absent = explicit.copy(explicit = VagConnectionPolicy.Explicit.AUTO, tpNoResponse = true)
        assertFalse(VagConnectionPolicy.mustConnectToTp(absent))
        assertTrue(VagConnectionPolicy.mustConnectToUds(absent, true))
        assertFalse(VagConnectionPolicy.mustConnectToUds(absent.copy(udsNoResponse = true), true))
    }
}
