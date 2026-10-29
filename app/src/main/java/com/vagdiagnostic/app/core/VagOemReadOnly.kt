package com.vagdiagnostic.app.core

/** Profile-specific OEM connection/read paths; not vehicle validation.
 * Display address, TP destination and ISO-TP CAN IDs are separate namespaces.
 * Only these known routes may be used; installed IDs outside the table stay visible, unqueried.
 */
object VagOemReadOnly {
    data class Route(val id: Int, val name: String, val tp: Int?, val udsTx: Int?,
        val installedOnly: Boolean = false, val udsAlternatives: List<Int> = emptyList()) {
        val udsRx: Int? get() = udsTx?.let {
            when { it > 0x7FF -> it + 0x20000; it in 0x7E0..0x7E7 -> it + 8; else -> it + 0x6A }
        }
        val is29Bit: Boolean get() = udsTx?.let { it > 0x7FF } == true
        val key: String get() = "VAG-%02X".format(id)
        fun udsPaths(listConfirmed: Boolean): List<Route> =
            (listOfNotNull(udsTx) + if (listConfirmed) udsAlternatives else emptyList()).map {
                copy(udsTx = it, udsAlternatives = emptyList())
            }
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
        Route(0x46, "Центральный комфорт", 0x21, 0x70D, udsAlternatives = listOf(0x17FC008B)),
        Route(0x52, "Дверь пассажира", 0x23, 0x74B),
        Route(0x53, "Стояночный тормоз", 0x19, 0x752),
        // Added routes require a genuine installation list, not candidate sweeping.
        Route(0x04, "Датчик угла поворота руля", 0x13, 0x751, installedOnly = true),
        Route(0x25, "Иммобилайзер", 0x14, 0x711, installedOnly = true),
        Route(0x62, "Задняя левая дверь", 0x24, null, installedOnly = true),
        Route(0x72, "Задняя правая дверь", 0x25, null, installedOnly = true),
        Route(0x56, "Магнитола", 0x52, 0x718, installedOnly = true),
        // Fixed routes are eligible only when the Gateway reports the display ID.
        // A TP destination disagreement still prevents polling this route.
        Route(0x18, "Дополнительный отопитель", 0x2F, 0x76A, installedOnly = true),
        Route(0x22, "Полный привод", 0x0A, 0x70F, installedOnly = true),
        Route(0x28, "Задняя климатическая установка", 0x45, 0x71A, installedOnly = true),
        Route(0x34, "Регулирование дорожного просвета", 0x04, 0x755, installedOnly = true),
        Route(0x36, "Сиденье водителя", 0x26, 0x74C, installedOnly = true),
        Route(0x37, "Навигация", 0x5B, 0x76C, installedOnly = true),
        Route(0x3C, "Контроль смены полосы", 0x1C, 0x74E, installedOnly = true),
        Route(0x47, "Аудиосистема", 0x53, 0x76F, installedOnly = true),
        Route(0x55, "Корректор фар", 0x06, 0x754, installedOnly = true),
        Route(0x57, "Телевизионный тюнер", 0x57, 0x76D, installedOnly = true),
        Route(0x5F, "Информационная электроника", 0x4D, 0x773, installedOnly = true),
        Route(0x65, "Контроль давления в шинах", 0x29, 0x70B, installedOnly = true),
        Route(0x69, "Прицеп", 0x43, 0x747, installedOnly = true),
        Route(0x6D, "Электроника багажника", 0x34, 0x723, installedOnly = true),
        Route(0x75, "Телематика", 0x55, 0x767, installedOnly = true),
        Route(0x77, "Телефон", 0x5A, 0x76B, installedOnly = true)
    ).associateBy { it.id }
    val gateway get() = routes.getValue(0x19)
    data class Installed(val id: Int, val rawStatus: Int, val hasFaultFlag: Boolean,
        val tpDestination: Int? = null, val extraStatus: Int? = null,
        val observations: List<List<Int>> = emptyList(), val routeConflict: Boolean = false)
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
    fun installedKwp(reply: List<Int>): List<Installed> = installedKwpMessages(listOf(reply))

    fun installedKwpMessages(replies: List<List<Int>>): List<Installed> {
        require(replies.isNotEmpty()) { "Нет сообщений списка 1A9F" }
        val body = positive(replies.first(), 0x1A)
        require(body.firstOrNull() == 0x9F) { "Нет echo 9F" }
        val data = body.drop(1) + replies.drop(1).flatMap {
            positive(it, 0x1A).also { part -> require(part.isNotEmpty()) { "Пустое продолжение 1A9F" } }
        }
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
    /** First packet echoes the DID; subsequent packets contain SID + complete records. */
    fun installed04A1Messages(replies: List<List<Int>>): List<Installed> {
        require(replies.isNotEmpty()) { "Нет сообщений списка 04A1" }
        val first = positive(replies.first(), 0x22)
        require(first.take(2) == listOf(4, 0xA1)) { "Нет echo 04A1" }
        val records = first.drop(2).toMutableList()
        require(records.size % 4 == 0) { "Неполное первое сообщение 04A1" }
        for (reply in replies.drop(1)) {
            val body = positive(reply, 0x22)
            require(body.isNotEmpty() && body.size % 4 == 0) { "Неполное продолжение 04A1" }
            records += body
        }
        require(records.isNotEmpty()) { "Пустой список 04A1" }
        return installedRecords(records, 2)
    }
    private fun installedRecords(data: List<Int>, flagsIndex: Int): List<Installed> {
        val entries = linkedMapOf<Int, Installed>()
        for (record in data.chunked(4).filter { it[flagsIndex] != 0 }) {
            require(record[0] in 1..255) { "Неверный ID в списке" }
            val id = record[0]
            val previous = entries[id]
            val destination = if (flagsIndex == 2) record[1] else null
            entries[id] = Installed(id, previous?.rawStatus ?: record[flagsIndex],
                previous?.hasFaultFlag == true || record[flagsIndex] and 4 != 0,
                previous?.tpDestination ?: destination,
                previous?.extraStatus ?: if (flagsIndex == 2) record[3] else null,
                (previous?.observations ?: emptyList()) + listOf(record.toList()),
                previous?.routeConflict == true || (previous != null && previous.tpDestination != destination))
        }
        return entries.values.toList()
    }
    fun kwpFaults(reply: List<Int>, powertrain: Boolean): Faults =
        kwpFaultMessages(listOf(reply), powertrain)

    /** Each advertised application message is a complete service response.
     * Accept counted, counted/padded and uncounted record layouts; never
     * salvage a valid prefix from a malformed message or skip a failed one.
     */
    fun kwpFaultMessages(replies: List<List<Int>>, powertrain: Boolean): Faults {
        require(replies.isNotEmpty()) { "Нет сообщений KWP DTC" }
        val records = replies.flatMap { reply ->
            val body = positive(reply, 0x18)
            require(body.isNotEmpty()) { "Пустое сообщение KWP DTC" }
            val data = when (body.size % 3) {
                0 -> body
                1 -> {
                    require(body[0] == (body.size - 1) / 3) { "Неполный список KWP DTC" }
                    body.drop(1)
                }
                else -> {
                    require(body.last() in listOf(0, 0xFF) && body[0] == (body.size - 2) / 3) {
                        "Неверная длина или заполнение KWP DTC"
                    }
                    body.drop(1).dropLast(1)
                }
            }
            data.chunked(3)
        }.distinct()
        val values = records.map {
            val raw = hex(it)
            val code = if (powertrain) ObdDecoder.codeOf(it[0], it[1]) else "%05d".format((it[0] shl 8) or it[1])
            Dtc(raw, code, DtcKind.OEM, statusText = "KWP: статус 0x%02X (без предположений о значении битов)".format(it[2]))
        }
        return Faults(values, if (powertrain) "1800FF00" else "1802FF00")
    }
    fun udsFaults(reply: List<Int>): Faults = when (val result = VagUdsReadOnly.decodeDtcByStatusMask("OEM", reply.map { it.toByte() }.toByteArray())) {
        is VagUdsReadOnly.DtcReply.Positive -> Faults(result.faults.map {
            Dtc(it.raw24, it.raw24, DtcKind.OEM, statusText = it.statusDescription())
        }, "19028D")
        else -> error("Неподтверждённый ответ UDS DTC: $result")
    }
}
