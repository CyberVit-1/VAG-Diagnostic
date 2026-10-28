package com.vagdiagnostic.app.core

/** Profile-specific OEM connection/read paths; not vehicle validation.
 * Display address, TP destination and ISO-TP CAN IDs are separate namespaces.
 * Only these known routes may be used; installed IDs outside the table stay visible, unqueried.
 */
object VagOemReadOnly {
    data class Route(val id: Int, val name: String, val tp: Int?, val udsTx: Int?) {
        val udsRx: Int? get() = udsTx?.let { if (it in 0x7E0..0x7E7) it + 8 else it + 0x6A }
        val key: String get() = "VAG-%02X".format(id)
    }
    val routes = listOf(
        Route(0x19, "CAN Gateway", 0x1F, 0x710),
        Route(0x01, "Двигатель", 0x01, 0x7E0),
        Route(0x02, "Коробка передач", 0x02, 0x7E1),
        Route(0x03, "ABS/ESP", 0x03, 0x713),
        Route(0x08, "Климатическая установка", 0x2C, 0x746),
        Route(0x09, "Центральная электроника", 0x20, 0x70E),
        Route(0x15, "Подушки безопасности", 0x05, 0x715),
        Route(0x16, "Электроника рулевого колеса", 0x2A, 0x70C),
        Route(0x17, "Панель приборов", 0x07, 0x714),
        Route(0x42, "Дверь водителя", 0x22, 0x74A),
        Route(0x44, "Усилитель рулевого управления", 0x09, 0x712),
        Route(0x46, "Центральный комфорт", 0x21, null),
        Route(0x52, "Дверь пассажира", 0x23, 0x74B),
        Route(0x53, "Стояночный тормоз", 0x19, 0x752)
    ).associateBy { it.id }
    val gateway get() = routes.getValue(0x19)
    data class Installed(val id: Int, val rawStatus: Int, val hasFaultFlag: Boolean)
    data class Faults(val values: List<Dtc>, val service: String)

    fun bytes(hex: String): List<Int> {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "Неверный HEX" }
        return hex.chunked(2).map { it.toInt(16) }
    }
    fun hex(data: List<Int>): String = data.joinToString("") { "%02X".format(it) }
    fun positive(reply: List<Int>, service: Int): List<Int> {
        if (reply.size == 3 && reply[0] == 0x7F) {
            require(reply[1] == service) { "Отказ другому сервису" }
            error("Сервис %02X: NRC %02X".format(service, reply[2]))
        }
        require(reply.isNotEmpty() && reply[0] == service + 0x40) { "Нет положительного ответа %02X".format(service) }
        return reply.drop(1)
    }
    /** Legacy installation list: 5A 9F, length including length byte,
     * four-byte records [VAG ID, reserved, reserved, status]. */
    fun installedKwp(reply: List<Int>): List<Installed> {
        val body = positive(reply, 0x1A)
        require(body.firstOrNull() == 0x9F) { "Нет echo 9F" }
        val data = body.drop(1)
        require(data.isNotEmpty() && data[0] == data.size && (data.size - 1) % 4 == 0) { "Неполный список 1A9F" }
        return installedRecords(data.drop(1), 3)
    }
    /** Alternative installation list available on some legacy gateways,
     * may run over TP2.0; UDS service does not imply ISO-TP transport. */
    fun installed04A1(reply: List<Int>): List<Installed> {
        val body = positive(reply, 0x22)
        require(body.take(2) == listOf(0x04, 0xA1)) { "Нет echo 04A1" }
        require((body.size - 2) % 4 == 0) { "Неполный список 04A1" }
        require(body.size > 2) { "Пустой список 04A1" }
        // 04A1: [display ID, transport address, installation flags, extra status].
        // Its flags offset differs from the legacy 1A9F layout.
        return installedRecords(body.drop(2), 2)
    }
    private fun installedRecords(data: List<Int>, flagsIndex: Int): List<Installed> {
        val entries = data.chunked(4).filter { it[flagsIndex] != 0 }.map {
            Installed(it[0], it[flagsIndex], it[flagsIndex] and 4 != 0)
        }
        require(entries.all { it.id in 1..255 } && entries.map { it.id }.distinct().size == entries.size) { "Неверные/повторные ID в списке" }
        return entries
    }
    fun kwpFaults(reply: List<Int>, powertrain: Boolean): Faults {
        val body = positive(reply, 0x18)
        require(body.isNotEmpty() && body.size == 1 + body[0] * 3) { "Неполный список KWP DTC" }
        val values = body.drop(1).chunked(3).map {
            val raw = hex(it)
            val code = if (powertrain) ObdDecoder.codeOf(it[0], it[1]) else "%05d".format((it[0] shl 8) or it[1])
            Dtc(raw, code, DtcKind.OEM, statusText = "KWP: статус 0x%02X (без предположений о значении битов)".format(it[2]))
        }
        return Faults(values, if (powertrain) "1800FF00" else "1802FF00")
    }
    fun udsFaults(reply: List<Int>): Faults = when (val result = VagUdsReadOnly.decodeDtcByStatusMask("OEM", reply.map { it.toByte() }.toByteArray())) {
        is VagUdsReadOnly.DtcReply.Positive -> Faults(result.faults.map {
            Dtc(it.raw24, it.raw24, DtcKind.OEM, statusText = "UDS: статус 0x%02X; маска 0x%02X".format(it.rawStatus, it.availableStatusMask))
        }, "19028D")
        else -> error("Неподтверждённый ответ UDS DTC: $result")
    }
}
