package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.FrameParse
import com.vagdiagnostic.app.core.ObdMode09Readiness
import com.vagdiagnostic.app.core.VagAddressedCan

/**
 * Legislated OBD 11-bit request/response pairs: 7E0..7E7 -> 7E8..7EF.
 * The response ID must be observed first; no gateway route is inferred.
 *
 * Does not guess a VAG Gateway CAN ID, issue UDS or mutate ECU state.
 * Caller must run on a disposable ELM link and restore normal OBD afterwards.
 * Works over any ELM byte transport (TCP, SPP, BLE NUS).
 */
object ElmAddressedObdProbe {
    data class Outcome(
        val verified: Boolean,
        val reason: String,
        val atAccepted: Boolean
    ) {
        fun journalSummary(): String =
            "Addressed legislated OBD; verified=$verified; " +
                "AT accepted=$atAccepted; $reason; no Gateway requests; VIN omitted"
    }

    suspend fun runOnDisposableEngine(engine: ElmEngine, expectedResponder: String): Outcome {
        // Never use a hard-coded request/response unless the active car has
        // actually sent 41 00 from 7E8 and ELM reported 11-bit/500kbps.
        if (!engine.vehicleReady || engine.protocol !in setOf("6", "A6") ||
            !Regex("^7E[8-F]$").matches(expectedResponder) ||
            expectedResponder !in engine.observedCanResponderIds
        ) return Outcome(false, "Нет подтверждённого CAN ответчика $expectedResponder", false)
        val acceptance = ElmAdapterCapabilities.inspect(engine, expectedResponder)
        if (!acceptance.completeAcceptance || acceptance.responder != expectedResponder) {
            return Outcome(false, "Адаптер не подтвердил все адресные AT-команды", false)
        }
        // 0902 (legislated Mode 09 PID 02) is read-only. Its response can be
        // multi-frame, allowing a *hardware* ISO-TP flow-control check.
        val raw = engine.command("0902", timeoutMs = 13500)
        val iso = VagAddressedCan.decodeSingleEcu(raw, expectedResponder.toInt(16))
        if (!iso.valid) {
            return Outcome(false, "Адресный ответ: " + iso.problems.joinToString("; "), true)
        }
        val decoded = ObdMode09Readiness.assessVin(
            FrameParse(mapOf(expectedResponder to listOf(iso.payload!!)), emptyList()),
            setOf(expectedResponder)
        )
        return if (decoded.verified == setOf(expectedResponder)) {
            Outcome(true, "Полный ответ стандартного OBD 0902 подтверждён", true)
        } else {
            Outcome(false, "Данные 0902: " + decoded.issues.joinToString("; "), true)
        }
    }
}
