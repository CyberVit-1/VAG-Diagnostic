package com.vagdiagnostic.app.core

/**
 * Legislated read-only OBD-II Mode 09 capability check, transport independent.
 * This does NOT infer any VAG Gateway route or prove addressed ISO-TP.
 * Structural summaries contain no VIN. A separate validated accessor is used
 * only for the local vehicle display, never for journal summaries or exports.
 */
object ObdMode09Readiness {
    data class Support(val responders: Set<String>, val issues: List<String>)

    fun supportedVinEcus(frames: FrameParse, knownEcus: Set<String>): Support {
        val supported = linkedSetOf<String>()
        val issues = frames.problems.toMutableList()
        frames.messagesFor(0x49).forEach { (ecu, messages) ->
            if (ecu !in knownEcus) {
                issues += "Mode09: ответ от неизвестного ЭБУ $ecu"
            } else if (messages.size != 1) {
                issues += "Mode09: неоднозначный ответ 0900 от $ecu"
            } else {
                val data = messages.single()
                if (data.size != 6 || (data[1].toInt() and 255) != 0) {
                    issues += "Mode09: недостоверный список PID от $ecu"
                } else if ((data[2].toInt() and 0x40) != 0) {
                    // Mode 09 PID02 corresponds to bit30 in PID01..20 bitmap.
                    supported += ecu
                }
            }
        }
        if (supported.isEmpty()) issues += "Mode09 PID02 не подтверждён"
        return Support(supported, issues)
    }

    data class Check(
        val supported: Set<String>,
        val verified: Set<String>,
        val issues: List<String>
    ) {
        val confirmed: Boolean get() = verified.isNotEmpty()
        fun journalSummary(): String =
            "Mode09 PID02 structurally verified: ${verified.size}/${supported.size}; " +
                "verified ECU IDs: ${verified.sorted().joinToString(",")}; " +
                "issues: ${issues.joinToString(" | ").ifBlank { "none" }}; VIN omitted from summary"
    }

    fun assessVin(frames: FrameParse, supportedEcus: Set<String>): Check {
        val verified = linkedSetOf<String>()
        val issues = frames.problems.toMutableList()
        frames.messagesFor(0x49).forEach { (ecu, messages) ->
            if (ecu !in supportedEcus) {
                issues += "Mode09: ответ от незапрошенного ECU $ecu"
                return@forEach
            }
            if (messages.size != 1) {
                issues += "Mode09: неоднозначный ответ 0902 от $ecu"
                return@forEach
            }
            val data = messages.single().map { it.toInt() and 255 }
            // CAN OBD-II Mode09 02: SID 49, PID 02, frame number 01,
            // followed by exactly seventeen VIN ASCII symbols.
            if (data.size != 20 || data[1] != 0x02 || data[2] != 0x01 ||
                !data.drop(3).all { it in 0x30..0x39 || (it in 0x41..0x5A && it !in listOf(0x49, 0x4F, 0x51)) }
            ) {
                issues += "Mode09: неполный либо недостоверный ответ VIN от $ecu"
            } else verified += ecu
        }
        (supportedEcus - verified).forEach { ecu ->
            if (issues.none { it.contains(ecu) })
                issues += "Mode09: ECU $ecu не подтвердил полный ответ 0902"
        }
        return Check(supportedEcus, verified, issues)
    }

    /** Multiple responding ECUs must agree; partial, invalid or conflicting
     * values are never substituted with a previous vehicle's identifier. */
    fun vinForDisplay(frames: FrameParse, supportedEcus: Set<String>): String? {
        val check = assessVin(frames, supportedEcus)
        val values = check.verified.map { ecu ->
            frames.messagesFor(0x49).getValue(ecu).single().drop(3)
                .map { (it.toInt() and 255).toChar() }.joinToString("")
        }.distinct()
        return values.singleOrNull()
    }
}
