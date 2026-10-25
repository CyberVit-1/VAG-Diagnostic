package com.vagdiagnostic.app.core

/**
 * Human-readable ELM327 ATDPN result. Preserves the raw protocol code
 * separately for engine logic; no transport-specific behavior here.
 */
object ElmProtocolDescription {
    fun describe(rawCode: String): String {
        val raw = rawCode.uppercase().trim()
        if (raw.isEmpty() || raw == "?" || raw == "НЕ ОПРЕДЕЛЁН") return "Не определён"
        val auto = raw.length == 2 && raw[0] == 'A'
        val code = if (auto) raw.substring(1) else raw
        val name = when (code) {
            "0" -> "Автоматическое определение"
            "1" -> "SAE J1850 PWM (41,6 кбит/с)"
            "2" -> "SAE J1850 VPW (10,4 кбит/с)"
            "3" -> "ISO 9141-2 (5-бодная инициализация)"
            "4" -> "ISO 14230-4 KWP (5-бодная инициализация)"
            "5" -> "ISO 14230-4 KWP (быстрая инициализация)"
            "6" -> "ISO 15765-4 CAN (11 бит, 500 кбит/с)"
            "7" -> "ISO 15765-4 CAN (29 бит, 500 кбит/с)"
            "8" -> "ISO 15765-4 CAN (11 бит, 250 кбит/с)"
            "9" -> "ISO 15765-4 CAN (29 бит, 250 кбит/с)"
            "A" -> "SAE J1939 CAN (29 бит, 250 кбит/с)"
            "B" -> "Пользовательский CAN-протокол 1"
            "C" -> "Пользовательский CAN-протокол 2"
            else -> null
        }
        if (name == null) return "Неизвестный протокол ELM (код: ${raw.take(16)})"
        return if (auto && code != "0") "$name · Авто" else name
    }
}
