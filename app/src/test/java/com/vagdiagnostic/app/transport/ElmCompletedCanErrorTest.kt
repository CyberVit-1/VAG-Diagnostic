package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagUdsTransaction
import org.junit.Assert.*
import org.junit.Test

class ElmCompletedCanErrorTest {
    @Test fun onlyOneCompleteStandaloneAdapterErrorGetsTypedClassification() {
        for (raw in listOf("CAN ERROR", "19028D\rCAN ERROR\r", "can error\r>"))
            assertTrue(runCatching { ElmCompletedCanError.check(raw, "19028D") }.exceptionOrNull() is ElmCompletedCanError)
    }
    @Test fun mixedOrAmbiguousErrorsRemainInvalidInsteadOfBecomingRetryableNoData() {
        for (raw in listOf("CAN ERROR\r77A0359028D", "CAN ERROR\rCAN ERROR", "BUFFER FULL", "STOPPED")) {
            assertNull(runCatching { ElmCompletedCanError.check(raw, "19028D") }.exceptionOrNull())
            assertTrue(runCatching { VagUdsTransaction.decode(raw, 0x77A, "19028D") }.isFailure)
        }
    }
}
