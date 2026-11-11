package com.vagdiagnostic.app.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Scoped clock seam for offline catalog tests; never global mutable state. */
internal class VagRetryBackoff(val wait: suspend (Long) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VagRetryBackoff>
}

/** Command-level retry budget, separate from channel setup and frame ACKs.
 * Completed silence, matching BusyRepeatRequest and standalone CAN errors
 * can repeat. Protocol failures require the caller's fresh transport boundary
 * before another attempt; pending/partial data is never completed NO_DATA. */
internal object VagReadRetry {
    enum class Outcome { COMPLETE, NO_DATA, BUSY, CAN_ERROR, PROTOCOL_ERROR }
    fun enabled(command: String): Boolean = command in setOf(
        "1089", "1003", "1001", "1A9F", "1A9B", "2204A1", "1800FF00", "1802FF00",
        "19028D", "222A26", "222A28", "222A2A", "2204A3", "22F1B7", "22F187", "22F197", "22F189", "22F19E", "22F1A2", "220174", "22539B"
    ) || command.matches(Regex("120004[0-9A-F]{4}")) || command.matches(Regex("1906[0-9A-F]{6}FF"))

    fun response(command: String, payload: List<Int>?): Outcome = when {
        payload == null -> Outcome.NO_DATA
        payload == listOf(0x7F, command.take(2).toInt(16), 0x21) -> Outcome.BUSY
        else -> Outcome.COMPLETE
    }

    suspend fun <T> run(command: String, classify: (T) -> Outcome,
        backoff: suspend (Long) -> Unit = { milliseconds ->
            val clock = coroutineContext[VagRetryBackoff]
            if (clock == null) delay(milliseconds) else clock.wait(milliseconds)
        }, beforeRetry: suspend (T) -> Unit = {}, read: suspend () -> T): T {
        var consecutiveSilence = 0
        repeat(4) { attempt ->
            coroutineContext.ensureActive()
            val result = read()
            val outcome = classify(result)
            consecutiveSilence = if (outcome == Outcome.NO_DATA) consecutiveSilence + 1 else 0
            if (!enabled(command) || outcome == Outcome.COMPLETE || consecutiveSilence == 2 || attempt == 3)
                return result
            backoff(300)
            coroutineContext.ensureActive()
            beforeRetry(result)
        }
        error("Исчерпан предел чтения")
    }
}
