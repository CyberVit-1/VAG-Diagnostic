package com.vagdiagnostic.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VagReadRetryTest {
    @Test fun completedSilenceStopsAfterTwoConsecutiveAttempts() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = VagReadRetry.run("1902AF", classify = { _: Int -> VagReadRetry.Outcome.NO_DATA }, backoff = { waits += it }) { ++calls }
        assertEquals(2, result); assertEquals(listOf(300L), waits)
    }
    @Test fun completedBusyStopsAtFourTotalAttempts() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = VagReadRetry.run("1A9F", classify = { _: Int -> VagReadRetry.Outcome.BUSY }, backoff = { waits += it }) { ++calls }
        assertEquals(4, result); assertEquals(listOf(300L, 300L, 300L), waits)
    }
    @Test fun otherCompletedResultResetsConsecutiveSilenceButNotTotalBudget() = runBlocking {
        val outcomes = listOf(VagReadRetry.Outcome.NO_DATA, VagReadRetry.Outcome.BUSY, VagReadRetry.Outcome.NO_DATA, VagReadRetry.Outcome.COMPLETE)
        var calls = 0
        val result = VagReadRetry.run("22F187", classify = { index: Int -> outcomes[index] }, backoff = {}) { calls++ }
        assertEquals(3, result); assertEquals(4, calls)
    }
    @Test fun capabilityAndUnlistedCommandsNeverRetry() = runBlocking {
        for (command in listOf("31B80000", "3E00", "22F190", "04", "14000000", "2E031D00")) {
            var calls = 0
            VagReadRetry.run(command, classify = { _: Int -> VagReadRetry.Outcome.BUSY }, backoff = { fail("Unexpected delay") }) { ++calls }
            assertEquals(command, 1, calls)
        }
    }
    @Test fun onlyExactMatchingFinalBusyReplyIsBusy() {
        assertEquals(VagReadRetry.Outcome.BUSY, VagReadRetry.response("1902AF", listOf(0x7F, 0x19, 0x21)))
        for (payload in listOf(listOf(0x7F, 0x18, 0x21), listOf(0x7F, 0x19, 0x78), listOf(0x7F, 0x19, 0x31), listOf(0x59, 2, 0x21)))
            assertEquals(VagReadRetry.Outcome.COMPLETE, VagReadRetry.response("1902AF", payload))
    }
    @Test fun ioAndCancellationDoNotRepeatRequests() = runBlocking {
        for (cancel in listOf(false, true)) {
            var calls = 0
            val failure = runCatching {
                VagReadRetry.run<Int>("1802FF00", classify = { VagReadRetry.Outcome.NO_DATA }, backoff = {}) {
                    calls++
                    if (cancel) throw CancellationException("Stopped") else throw IOException("Lost stream")
                }
            }.exceptionOrNull()
            if (cancel) assertTrue(failure is CancellationException) else assertTrue(failure is IOException)
            assertEquals(1, calls)
        }
    }
    @Test fun cancellationInBackoffPreventsNextRead() = runBlocking {
        var calls = 0
        val failure = runCatching {
            VagReadRetry.run("1A9B", classify = { _: Int -> VagReadRetry.Outcome.NO_DATA },
                backoff = { throw CancellationException("Stopped") }) { ++calls }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(1, calls)
    }
}
