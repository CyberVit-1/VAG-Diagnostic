package com.vagdiagnostic.app.core

/**
 * Initial VW TP2.0 CHANNEL SETUP only; not an Auto-Scan.
 *
 * TP2.0 is a distinct protocol from ISO-TP/UDS, used by some older VAG
 * vehicles. No arbitrary CAN ID sweeps. The 0x200 setup ID and its
 * 0x200+moduleLogicalId response follow the published TP2.0 protocol.
 *
 * Never issue KWP services, TesterPresent, coding, adaptations or writing.
 * This module only validates the single gateway (logical 0x19) channel
 * negotiation and does NOT claim that a channel can yet carry KWP traffic.
 */
object VagTp20Gateway {
    const val GATEWAY_LOGICAL_ADDRESS = 0x19
    const val SETUP_CAN_ID = 0x200
    const val GATEWAY_REPLY_CAN_ID = SETUP_CAN_ID + GATEWAY_LOGICAL_ADDRESS

    /** 1K is a documented legacy CAN/VAG chassis; others require explicit profiles. */
    data class Profile(val chassis: String, val source: String)
    fun knownCandidate(chassis: String?): Profile? =
        if (chassis == "1K") Profile(
            chassis = "1K",
            source = "Ross-Tech Golf Mk5/1K chassis + public VW TP2.0 channel setup"
        ) else null

    /** RX ID invalid (ECU chooses), tester RX ID 0x300, KWP application 0x01. */
    fun channelRequest(): String = "19C00010000301"

    sealed class Result {
        data class Accepted(
            val gatewayCanId: Int,
            val testerReceiveCanId: Int,
            val testerTransmitCanId: Int
        ) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun decodeReply(raw: String): Result {
        val hex = Regex("^[0-9A-F]+$")
        val incoming = mutableListOf<List<Int>>()
        val errors = mutableListOf<String>()
        for (row in raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')) {
            val line = row.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty() || line == "SEARCHING..." || line == channelRequest()) continue
            if (line.contains("NO DATA") || line.contains("CAN ERROR") ||
                line.contains("STOPPED") || line == "?") {
                errors += "ELM: " + line
                continue
            }
            val match = Regex("^([0-9A-F]{3}) ?(.*)$").matchEntire(line)
            if (match == null || match.groupValues[1] != "%03X".format(GATEWAY_REPLY_CAN_ID)) {
                errors += "Неожиданный CAN адрес или отсутствие заголовка TP2.0"
                continue
            }
            var bytes = match.groupValues[2].trim().replace(" ", "")
            // ATH1+CAF0 may show the DLC separately; some ECUs pad the
            // 7-byte channel response to eight bytes with 00.
            val spaced = match.groupValues[2].trim().split(Regex("\\s+"))
            if (spaced.firstOrNull()?.length == 1) {
                val dlc = spaced.first().toIntOrNull(16)
                if (dlc == null || dlc !in 7..8 || spaced.size != dlc + 1 ||
                    spaced.drop(1).any { it.length != 2 || !hex.matches(it) }) {
                    errors += "Неверный DLC ответа TP2.0"
                    continue
                }
                bytes = spaced.drop(1).joinToString("")
            }
            if (bytes.length == 16 && bytes.endsWith("00")) {
                bytes = bytes.dropLast(2)
            }
            if (bytes.length != 14 || !hex.matches(bytes)) {
                errors += "Неполный/неверный ответ настройки TP2.0"
                continue
            }
            incoming += bytes.chunked(2).map { it.toInt(16) }
        }
        if (errors.isNotEmpty()) return Result.Rejected(errors.joinToString("; "))
        if (incoming.size != 1) return Result.Rejected("Ожидался один ответ Gateway 19 TP2.0")
        val data = incoming.single()
        if (data[0] != 0x00 || data[6] != 0x01) {
            return Result.Rejected("Неверный адрес тестера или TP2.0 application")
        }
        if (data[1] in 0xD6..0xD8)
            return Result.Rejected("Шлюз отклонил открытие TP2.0: 0x%02X".format(data[1]))
        if (data[1] != 0xD0) return Result.Rejected("Неверный opcode настройки TP2.0")
        fun canId(low: Int, vp: Int): Int? {
            if ((vp and 0xF0) != 0 || low !in 0..255) return null
            return ((vp and 0x0F) shl 8) or low
        }
        val testerReceive = canId(data[2], data[3])
        val testerTransmit = canId(data[4], data[5])
        if (testerReceive != 0x300 || testerTransmit == null ||
            testerTransmit == SETUP_CAN_ID || testerTransmit !in 1..0x7FF) {
            return Result.Rejected("ECU не подтвердил согласованный канал TP2.0")
        }
        return Result.Accepted(GATEWAY_REPLY_CAN_ID, testerReceive, testerTransmit)
    }

    /**
     * Only the two-character VAG chassis marker leaves this function.
     * Never persist or export VIN, including as ASCII/hex diagnostics.
     */
    fun chassisFromVerifiedMode09(frames: FrameParse, sourceEcu: String): String? {
        val all = frames.messagesFor(0x49)[sourceEcu].orEmpty()
        if (all.size != 1) return null
        val bytes = all.single().map { it.toInt() and 0xFF }
        if (bytes.size != 20 || bytes[1] != 0x02 || bytes[2] != 0x01) return null
        val chars = bytes.drop(3)
        if (chars.size != 17 || !chars.all {
            it in 0x30..0x39 || it in 0x41..0x5A
        }) return null
        // Limit this 1K profile to a Volkswagen VIN manufacturer prefix.
        // Unknown WMI/chassis combinations never enable experimental TP2.0.
        val wmi = chars.take(3).map { it.toChar() }.joinToString("")
        if (wmi !in setOf("WVW", "3VW")) return null
        // Character positions seven and eight, 1-based, form chassis code.
        return "" + chars[6].toChar() + chars[7].toChar()
    }
}
