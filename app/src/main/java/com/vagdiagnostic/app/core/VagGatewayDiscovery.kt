package com.vagdiagnostic.app.core

/** Additional fixed Gateway read formats. A coded/event hint is not an installed
 * or responding ECU. Address indexes resolve only against the known catalog. */
object VagGatewayDiscovery {
    data class Result(val entries: List<VagOemReadOnly.Installed>, val limitations: List<String> = emptyList())
    // Fixed alternatives for the same logical powertrain units, not new ECU IDs.
    // They are available only through a decoded installation list.
    private fun logicalRoute(id: Int): VagOemReadOnly.Route? = when (id) {
        1 -> VagOemReadOnly.routes.getValue(1).copy(udsAlternatives = listOf(0x17FC0076))
        2 -> VagOemReadOnly.routes.getValue(2).copy(udsAlternatives = listOf(0x17FC0077))
        0x51 -> VagOemReadOnly.routes.getValue(0x51).copy(name = "Электропривод", udsAlternatives = listOf(0x17FC007C))
        // FF is an internal marker in one existing bitmap format, not a
        // logical-address-list proof of the EVO engine's physical endpoint.
        0xFE, 0xFF -> VagOemReadOnly.Route(id, "Блок VAG %02X".format(id), null, null, installedOnly = true)
        else -> VagOemReadOnly.routes[id]
    }
    private fun body(reply: List<Int>, did: Int): List<Int> {
        require(reply.all { it in 0..255 }) { "Неверные байты списка Gateway" }
        val payload = VagOemReadOnly.positive(reply, 0x22)
        require(payload.take(2) == listOf(did ushr 8, did and 255)) { "Ответ другому DID списка Gateway" }
        return payload.drop(2)
    }
    fun logicalList(reply: List<Int>): Result {
        val ids = body(reply, 0x2A2A)
        require(ids.size <= 255 && ids.none { it == 0 }) { "Неверный список адресов Gateway" }
        return Result(ids.distinct().map { id -> VagOemReadOnly.Installed(id, null, false, mappedRoute = logicalRoute(id),
            discoverySources = setOf(VagOemReadOnly.DiscoverySource.LOGICAL_LIST)) })
    }
    fun addressIndexes(reply: List<Int>): Result {
        val data = body(reply, 0xF1B7)
        require(data.size <= 512 && data.size % 2 == 0) { "Обрезанный адресный список Gateway" }
        val entries = mutableListOf<VagOemReadOnly.Installed>()
        val unresolved = mutableListOf<String>()
        for (pair in data.chunked(2).distinct()) {
            val index = (pair[0] shl 8) or pair[1]
            val header11 = when (index) { 0 -> 0x710; 0x76 -> 0x7E0; 0x77 -> 0x7E1; else -> 0x700 + index }
            val header29 = 0x17FC0000 + index
            val routes = VagOemReadOnly.routes.keys.filter { it !in setOf(0xFE, 0xFF) }.mapNotNull(::logicalRoute).filter {
                val paths = it.udsPaths(true).map { path -> path.udsTx }
                (header11 <= 0x7FF && header11 in paths) || header29 in paths
            }
            if (routes.isEmpty()) { unresolved += "Неизвестный адресный индекс %04X".format(index); continue }
            val ambiguous = routes.size > 1
            if (ambiguous) unresolved += "Неоднозначный адресный индекс %04X".format(index)
            for (route in routes) entries += VagOemReadOnly.Installed(route.id, null, false, mappedRoute = route,
                discoverySources = setOf(if (ambiguous) VagOemReadOnly.DiscoverySource.AMBIGUOUS_INDEX
                    else VagOemReadOnly.DiscoverySource.ADDRESS_INDEX))
        }
        return merge(listOf(Result(entries, unresolved)))
    }
    /** These two bitmaps use only the already validated common 32-byte mapping.
     * No inference from an unknown part or another platform's bit positions. */
    fun bitmap(part: String, reply: List<Int>, events: Boolean = false): Result {
        require(VagInstallationBitmap.formatFor(0x19, part) == "common") { "Формат дополнительного bitmap не подтверждён" }
        val did = if (events) 0x2A28 else 0x04A3
        val data = body(reply, did)
        // Reuse the common bit mapping after independently validating this DID.
        val decoded = VagInstallationBitmap.decode(0x19, part, listOf(0x62, 0x2A, 0x26) + data)
        return Result(decoded.entries.map { entry ->
            val name = VagOemReadOnly.routes[entry.id]?.name ?: "Блок VAG"
            VagOemReadOnly.Installed(entry.id, null, events,
                mappedRoute = VagOemReadOnly.Route(entry.id, name, entry.tp, entry.udsTx, installedOnly = true),
                discoverySources = setOf(if (events) VagOemReadOnly.DiscoverySource.EVENTS else VagOemReadOnly.DiscoverySource.CODED))
        }, decoded.unknownBits.map { "Неизвестная позиция дополнительного bitmap: $it" })
    }
    fun merge(results: List<Result>): Result {
        val merged = linkedMapOf<Int, VagOemReadOnly.Installed>()
        for (entry in results.flatMap { it.entries }) {
            val old = merged[entry.id]
            val oldRoute = old?.mappedRoute
            val newRoute = entry.mappedRoute
            val conflict = oldRoute != null && newRoute != null &&
                (oldRoute.tp != newRoute.tp || oldRoute.udsTx != newRoute.udsTx)
            merged[entry.id] = if (old == null) entry else old.copy(
                hasFaultFlag = old.hasFaultFlag || entry.hasFaultFlag,
                mappedRoute = if (oldRoute != null && newRoute != null && !conflict)
                    oldRoute.copy(udsAlternatives = (oldRoute.udsAlternatives + newRoute.udsAlternatives).distinct())
                    else oldRoute ?: newRoute,
                routeConflict = old.routeConflict || entry.routeConflict || conflict,
                discoverySources = old.discoverySources + entry.discoverySources)
        }
        return Result(merged.values.toList(), results.flatMap { it.limitations }.distinct())
    }
}
