package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagTp20Gateway

/**
 * Single, read-only protocol-channel setup to the documented 0x19 gateway
 * of an explicitly recognized legacy chassis. NOT an ECU scan.
 *
 * Uses only the existing ELM byte transport, regardless of Wi-Fi/SPP/BLE.
 * This MUST run on a disposable ELM session, following positive OBD 0100
 * and a structurally verified Mode09 chassis marker.
 *
 * Opens at most one TP2.0 channel; sends no KWP, UDS, coding or DTC clear.
 * Caller closes the link immediately and restores standard OBD afterward.
 */
object ElmTp20GatewayProbe {
    data class Evidence(
        val accepted: Boolean,
        val status: String,
        val negotiatedTxCanId: Int? = null
    ) {
        fun journalSummary(): String = "Gateway TP2.0 setup: accepted=$accepted; " +
            "result=$status; tx=" + (negotiatedTxCanId?.let { "%03X".format(it) } ?: "none") +
            "; no KWP/UDS commands, no VIN"
    }

    suspend fun inspect(
        engine: ElmEngine,
        profile: VagTp20Gateway.Profile?
    ): Evidence {
        if (profile == null || profile.chassis != "1K" ||
            !engine.vehicleReady || engine.protocol !in setOf("6", "A6") ||
            engine.isCanVehicle != true ||
            engine.observedCanResponderIds.none { Regex("^7E[8-F]$").matches(it) }
        ) return Evidence(false, "Нет подтверждённого профиля/OBD CAN для TP2.0")
        // Requires raw CAN bytes. The ELM automatic ISO-TP mode used for OBD
        // must be disabled in this disposable diagnostic session.
        // TP2.0 channel setup is EXACTLY 7 bytes. In ISO 15765-4 mode
        // ELM may pad to 8 unless variable DLC is explicitly enabled (V1).
        // The physical test of 0.2.2 returned NO DATA with default settings;
        // a longer fixed reply window also avoids an overly eager ELM prompt.
        // ALL settings are isolated in the disposable session and ATZ is
        // used by the caller's normal OBD reinitialization afterwards.
        val setup = listOf(
            "ATCAF0", "ATCFC0", "ATV1", "ATAT0", "ATST96",
            "ATSH200", "ATCRA219"
        )
        for (at in setup) {
            val reply = engine.command(at, timeoutMs = 5500)
            val clean = reply.uppercase().replace(at, "").replace(" ", "")
                .replace("\r", "").replace("\n", "")
            if (clean != "OK")
                return Evidence(false, "ELM не подтвердил $at")
        }
        // One explicitly documented TP2.0 0x200 channel setup to address 0x19.
        // No arbitrary IDs and no retries of non-OBD diagnostic commands.
        val answer = engine.command(VagTp20Gateway.channelRequest(), timeoutMs = 8000)
        return when (val decoded = VagTp20Gateway.decodeReply(answer)) {
            is VagTp20Gateway.Result.Accepted ->
                Evidence(true, "Канал Gateway 19 согласован (только настройка TP2.0)",
                    decoded.testerTransmitCanId)
            is VagTp20Gateway.Result.Rejected ->
                Evidence(false, decoded.reason)
        }
    }
}
