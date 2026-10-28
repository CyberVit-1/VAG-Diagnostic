package com.vagdiagnostic.app.core

import org.junit.Assert.*
import org.junit.Test

class VagUdsTransactionTest {
    @Test fun pendingIsNotAZeroDtcReply() {
        val reply = VagUdsTransaction.decode("77F037F1978", 0x77F, "19028D")
        assertNull(reply.payload)
        assertEquals(1, reply.pendingCount)
    }
    @Test fun repeatedPendingAndFinalMultiframeRemainStrictlyAddressed() {
        val reply = VagUdsTransaction.decode("77A037F1978\r77A037F1978\r77A100B59028D123456\r77A2108654321040000", 0x77A, "19028D")
        assertEquals(2, reply.pendingCount)
        assertEquals(2, VagOemReadOnly.udsFaults(reply.payload!!).values.size)
    }
    @Test fun wrongSidWrongAddressAndTwoFinalsFailClosed() {
        for (raw in listOf("77B037F1978", "77A037F2278", "77A025001",
            "77A037F1978\r77B03590299", "77A03590299\r77A03590299", "77A03590699",
            "77A03590299\r77A037F1978")) {
            assertTrue(raw, runCatching { VagUdsTransaction.decode(raw, 0x77A, "19028D") }.isFailure)
        }
    }
    @Test fun terminalNrcIsPreservedRatherThanWaitedOrTreatedAsClean() {
        val reply = VagUdsTransaction.decode("77A037F1931", 0x77A, "19028D")
        assertEquals(listOf(0x7F, 0x19, 0x31), reply.payload)
        assertEquals(0, reply.pendingCount)
    }
}
