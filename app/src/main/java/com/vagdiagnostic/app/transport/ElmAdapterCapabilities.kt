package com.vagdiagnostic.app.transport

/**
 * Non-destructive ELM AT command acceptance probe.
 *
 * An AT "OK" is NOT proof of real ECU-addressed ISO-TP filtering or flow
 * control. Only AT configuration commands are used, never UDS or gateway
 * queries. This MUST be run on an isolated, disposable ELM session.
 */
object ElmAdapterCapabilities {
    enum class Verdict { ACCEPTED, REJECTED }
    data class Check(val command: String, val verdict: Verdict, val detail: String)
    data class Report(
        val can11Bit500k: Boolean,
        val responder: String?,
        val checks: List<Check>,
        val notes: List<String>
    ) {
        val completeAcceptance: Boolean get() =
            can11Bit500k && responder != null &&
                checks.size == 4 && checks.all { it.verdict == Verdict.ACCEPTED }

        fun logText(): String = buildString {
            append("AT-only; CAN 11-bit/500kbps: " + can11Bit500k)
            append("; observed ECU: " + (responder ?: "none"))
            checks.forEach {
                append("\n" + it.command + ": " + it.verdict + " (" + it.detail + ")")
            }
            notes.forEach { append("\nNOTE: " + it) }
            append("\nAT OK does NOT confirm addressed ISO-TP or Flow Control on a vehicle")
        }
    }

    private val diagnostic11Bit = Regex("^7E[8-F]$")

    /** Use an ECU ID verified by the previous vehicle 0100 reply. */
    suspend fun inspect(engine: ElmEngine, expectedResponder: String? = null): Report {
        check(engine.vehicleReady) { "Сначала подтвердите OBD-II связь" }
        val isCan11 = engine.protocol in setOf("6", "A6") && engine.isCanVehicle == true
        val responder = engine.observedCanResponderIds.firstOrNull {
            diagnostic11Bit.matches(it) && (expectedResponder == null || it == expectedResponder)
        }
        if (!isCan11 || responder == null) {
            return Report(
                can11Bit500k = isCan11,
                responder = responder,
                checks = emptyList(),
                notes = listOf(
                    "Для пробного AT-адресного режима нет проверенного 11-битного CAN-ответчика; никаких AT-настроек не изменено"
                )
            )
        }
        val checks = mutableListOf<Check>()
        // Legislated OBD 11-bit physical request IDs 7E0..7E7 map to
        // observed reply IDs 7E8..7EF (offset 8); no OEM ID is guessed.
        // These are AT settings, not transmitted CAN diagnostic requests.
        val physicalRequest = "%03X".format(responder.toInt(16) - 8)
        for (cmd in listOf("ATCAF1", "ATCFC1",
            "ATSH" + physicalRequest, "ATCRA" + responder)) {
            val raw = engine.command(cmd, timeoutMs = 5500)
            val normalized = raw.uppercase().replace(cmd, "").trim()
                .replace("\r", "").replace("\n", "").replace(" ", "")
            val verdict = if (normalized == "OK") Verdict.ACCEPTED else Verdict.REJECTED
            checks += Check(cmd, verdict,
                if (verdict == Verdict.ACCEPTED) "ELM ответил OK"
                else "ELM ответил: " + raw.take(120).trim())
            // Do not continue changing the adapter if it rejects anything.
            if (verdict != Verdict.ACCEPTED) break
        }
        return Report(
            can11Bit500k = true,
            responder = responder,
            checks = checks,
            notes = listOf(
                "Проверялось принятие команд, не работа CAN-фильтра/Flow Control",
                "UDS/Gateway запросы не отправлялись",
                "Нужна новая штатная OBD-II сессия после AT-настроек"
            )
        )
    }
}
