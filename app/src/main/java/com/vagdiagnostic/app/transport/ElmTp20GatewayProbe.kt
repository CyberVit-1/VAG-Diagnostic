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
        // A physical 0.2.3 test confirmed a 7-byte TP2.0 request under
        // ISO 15765-4 protocol 6 (ATV1) still receives NO DATA. Published
        // TP2.0 examples with ELM327 instead use USER1 raw CAN protocol B:
        // ATPB C0 01 => 11-bit IDs, variable DLC, 500 kbit/s, no ISO-TP
        // framing. ATSPB selects it and ATBI explicitly makes it active
        // without an OBD-II initialization request.
        //
        // The trial is gated to the known 1K chassis, a single request on
        // 0x200 and a single expected reply on 0x219. The engine is
        // disposable, and the caller establishes a fresh ATSP0 OBD session
        // immediately after. Never assume a clone accepts these commands.
        val setup = listOf(
            "ATPBC001", "ATSPB", "ATCAF0", "ATCFC0",
            "ATV1", "ATAT0", "ATST96", "ATBI",
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
        // In the ELM327 text protocol an odd trailing nibble is the
        // maximum *number of responses* (1), NOT an eighth CAN data byte.
        // Keep the seven-byte TP2.0 payload isolated in channelRequest().
        val oneResponse = VagTp20Gateway.channelRequest() + "1"
        val answer = engine.command(oneResponse, timeoutMs = 8000)
        return when (val decoded = VagTp20Gateway.decodeReply(answer)) {
            is VagTp20Gateway.Result.Accepted ->
                Evidence(true, "Канал Gateway 19 согласован (только настройка TP2.0)",
                    decoded.testerTransmitCanId)
            is VagTp20Gateway.Result.Rejected ->
                Evidence(false, decoded.reason)
        }
    }
}
