package com.vagdiagnostic.app.core

/**
 * Read-only UDS response validation. Here SID 0x19 means UDS ReadDTCInformation,
 * NOT VAG's logical module address 19 (CAN Gateway). No ECU requests are sent.
 *
 * Synthetic fixtures and ISO-TP reassembly can be tested before any vehicle
 * protocol/addressing support is enabled.
 */
object VagUdsReadOnly {
    data class Fault(
        val raw24: String,
        val rawStatus: Int,
        val availableStatusMask: Int
    ) {
        /** Do not interpret status flags that this ECU has not declared supported. */
        val supportedStatus: Int get() = rawStatus and availableStatusMask
    }

    sealed class DtcReply {
        data class Positive(
            val ecu: String,
            val availableStatusMask: Int,
            val faults: List<Fault>
        ) : DtcReply()

        data class Negative(val ecu: String, val nrc: Int) : DtcReply()
        data class Pending(val ecu: String) : DtcReply()
        data class Invalid(val ecu: String, val reason: String) : DtcReply()
    }

    /** 0x19 0x02: reportDTCByStatusMask; 0x59 0x02 is its only valid positive reply. */
    fun decodeDtcByStatusMask(ecu: String, payload: ByteArray): DtcReply {
        fun byte(index: Int) = payload[index].toInt() and 0xFF
        if (payload.isEmpty()) return DtcReply.Invalid(ecu, "Пустой ответ UDS")
        if (byte(0) == 0x7F) {
            if (payload.size != 3 || byte(1) != 0x19) {
                return DtcReply.Invalid(ecu, "Неверный отрицательный ответ UDS 19")
            }
            return if (byte(2) == 0x78) DtcReply.Pending(ecu)
            else DtcReply.Negative(ecu, byte(2))
        }
        if (payload.size < 3 || byte(0) != 0x59 || byte(1) != 0x02) {
            return DtcReply.Invalid(ecu, "Ожидался подтверждённый UDS 59 02")
        }
        if ((payload.size - 3) % 4 != 0) {
            return DtcReply.Invalid(ecu, "Неполная запись UDS DTC (3 байта кода + статус)")
        }
        val mask = byte(2)
        val faults = (3 until payload.size step 4).map { i ->
            Fault(
                raw24 = "%02X%02X%02X".format(byte(i), byte(i + 1), byte(i + 2)),
                rawStatus = byte(i + 3),
                availableStatusMask = mask
            )
        }
        return DtcReply.Positive(ecu, mask, faults)
    }

    data class Assessment(
        val confirmed: Map<String, DtcReply.Positive>,
        val problems: List<String>
    ) {
        val complete: Boolean get() = confirmed.isNotEmpty() && problems.isEmpty()
    }

    /**
     * Accept only ECU-addressed, ISO-TP-reassembled, positive UDS responses.
     * A negative response or "response pending" is not equivalent to zero DTC.
     * No retry or TesterPresent is initiated here.
     */
    fun assessDtcByStatusMask(
        frames: FrameParse,
        expectedEcus: Set<String>
    ): Assessment {
        val confirmed = linkedMapOf<String, DtcReply.Positive>()
        val problems = frames.problems.toMutableList()
        val positives = frames.messagesFor(0x59)
        val negatives = frames.messagesFor(0x7F)

        (positives.keys + negatives.keys).forEach { ecu ->
            if (ecu == "OBD-UNIDENTIFIED") {
                problems += "UDS: ответ без достоверного адреса ЭБУ"
                return@forEach
            }
            if (ecu !in expectedEcus) {
                problems += "UDS: ответ от незапрошенного ЭБУ $ecu"
                return@forEach
            }
            val replies = positives[ecu].orEmpty() + negatives[ecu].orEmpty()
            if (replies.size != 1) {
                problems += "UDS: неоднозначное количество ответов от $ecu: ${replies.size}"
                return@forEach
            }
            when (val result = decodeDtcByStatusMask(ecu, replies.single())) {
                is DtcReply.Positive -> confirmed[ecu] = result
                is DtcReply.Negative ->
                    problems += "UDS 19: ЭБУ $ecu отклонил запрос, NRC 0x%02X".format(result.nrc)
                is DtcReply.Pending ->
                    problems += "UDS 19: ЭБУ $ecu ещё обрабатывает запрос (NRC 0x78)"
                is DtcReply.Invalid -> problems += "UDS 19: ЭБУ $ecu: ${result.reason}"
            }
        }
        (expectedEcus - confirmed.keys).forEach { ecu ->
            if (problems.none { it.contains(ecu) }) {
                problems += "UDS 19: нет подтверждённого ответа ЭБУ $ecu"
            }
        }
        return Assessment(confirmed, problems)
    }
}

/**
 * Gateway 19 discovery must use a VERIFIED physical route and platform-specific
 * Installation List definition. Neither an OBD response at 7E8 nor an ELM
 * firmware label establishes that route. Fail closed with no CAN traffic.
 */
object VagGatewayReadiness {
    data class VerifiedProfile(
        val platform: String,
        val diagnosticRequestCanId: Int,
        val diagnosticResponseCanId: Int,
        val installationListDid: Int,
        val source: String
    ) {
        fun valid(): Boolean =
            platform.isNotBlank() &&
            source.isNotBlank() &&
            diagnosticRequestCanId in 0..0x7FF &&
            diagnosticResponseCanId in 0..0x7FF &&
            diagnosticRequestCanId != diagnosticResponseCanId &&
            diagnosticRequestCanId != 0x7DF &&
            installationListDid in 1..0xFFFF
    }

    sealed class Decision {
        data class Blocked(val reason: String) : Decision()
        data class EligibleForImplementation(val profile: VerifiedProfile) : Decision()
    }

    fun evaluate(
        vehicleConnected: Boolean,
        confirmedCan: Boolean?,
        verifiedProfile: VerifiedProfile?,
        adapterHasValidatedIsoTp: Boolean
    ): Decision = when {
        !vehicleConnected -> Decision.Blocked("Связь с автомобилем не подтверждена")
        confirmedCan != true -> Decision.Blocked("CAN-диагностика не подтверждена")
        verifiedProfile == null || !verifiedProfile.valid() ->
            Decision.Blocked("Нет проверенной адресации Gateway 19 и формата Installation List")
        !adapterHasValidatedIsoTp ->
            Decision.Blocked("ISO-TP и адресный обмен через адаптер не подтверждены")
        else -> Decision.EligibleForImplementation(verifiedProfile)
    }
}
