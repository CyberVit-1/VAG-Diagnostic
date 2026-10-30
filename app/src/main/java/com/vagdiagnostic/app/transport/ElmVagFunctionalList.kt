package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed Mk8 functional installation-list operation. No identifier sweep and
 * no DTC inference from installation/fault-indication bits. */
internal object ElmVagFunctionalList {
    private val owners = setOf(0x19, 0x42, 0x52, 0x09)
    private val routes = owners.associateWith { VagOemReadOnly.routes.getValue(it) }
    private val byRx = routes.entries.associate { requireNotNull(it.value.udsRx) to it.key }
    data class Result(val installed: List<VagOemReadOnly.Installed>, val respondingOwners: Set<Int>,
        val limitations: List<String>, val requiresReconnect: Boolean) {
        val complete: Boolean get() = installed.isNotEmpty() && limitations.isEmpty()
    }

    suspend fun read(engine: ElmEngine, gatewayPart: String, faults: Boolean = false): Result {
        require(VagInstallationBitmap.formatFor(0x19, gatewayPart) == "mk8")
        val request = if (faults) "222A28" else "222A26"
        val collection = VagIsoTpCollection(byRx.keys, request)
        val started = System.nanoTime()
        var reconnect = false
        val failures = mutableListOf<String>()
        suspend fun command(value: String, timeout: Long = 8500) = engine.command(value, timeout, deferJournal = true)
        suspend fun ok(value: String) {
            val raw = command(value).uppercase().replace(value, "").filterNot(Char::isWhitespace)
            if (raw != "OK") throw IOException("ELM отклонил настройку функционального опроса $value")
        }
        fun decoded(): Map<Int, VagInstallationBitmap.Result> = buildMap {
            for ((rx, payload) in collection.payloads) {
                if (payload.firstOrNull() == 0x7F) continue
                val owner = requireNotNull(byRx[rx])
                try { put(owner, VagInstallationBitmap.decode(owner, gatewayPart, payload, faults)) }
                catch (_: RuntimeException) { /* A framed but invalid owner list is not merged. */ }
            }
        }
        fun requiredOwners(decoded: Map<Int, VagInstallationBitmap.Result>): Set<Int> =
            setOf(0x19) + decoded.values.flatMap { it.ids }.filter { it in owners }
        try {
            for (setting in listOf("ATSP6", "ATH1", "ATD0", "ATCAF0", "ATCFC0", "ATV1", "ATCEA", "ATAT0",
                "ATST20", "ATFCSM0", "ATSH700", "ATCRA", "ATCF700", "ATCM700")) ok(setting)
            engine.needsFlowControlReset = false
            // Suppress-positive TesterPresent is a broadcast-mode poke only.
            // Consume the bounded ELM reply window, including any final NRC,
            // before issuing the list request to avoid mixing the two SIDs.
            val poke = VagIsoTpCollection(byRx.keys, "3E00")
            poke.accept(command("023E800000000000"), "023E800000000000")
            if (poke.pending.isNotEmpty() || poke.unfinished.isNotEmpty()) throw IOException("Незавершённый функциональный TesterPresent")
            val rawRequest = "03${request}00000000"
            collection.accept(command(rawRequest), rawRequest)
            var receives = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (elapsed >= 20000) throw IOException("Истёк общий срок функционального списка")
                val flow = collection.flowControlNeeded.firstOrNull()
                if (flow != null) {
                    val route = routes.getValue(byRx.getValue(flow))
                    ok("ATSH%03X".format(requireNotNull(route.udsTx)))
                    collection.flowControlSent(flow)
                    collection.accept(command("3000100000000000"), "3000100000000000")
                    continue
                }
                val expected = requiredOwners(decoded()).map { requireNotNull(routes.getValue(it).udsRx) }.toSet()
                if (expected.all { it in collection.payloads } && collection.pending.isEmpty() && collection.unfinished.isEmpty()) break
                if (++receives > 8) throw IOException("Превышен предел ожидания функционального списка")
                val remaining = (20000 - elapsed).coerceAtMost(5250).coerceAtLeast(1)
                val raw = engine.receivePendingCanFrames((0x700..0x7FF).toSet(), remaining, deferJournal = true)
                collection.accept(raw, "ATMA")
            }
        } catch (cancel: CancellationException) { engine.close(); throw cancel }
        catch (failure: Exception) {
            // Already validated owners may survive a different owner's failure,
            // but the pending/corrupted stream must be replaced before DTC traffic.
            engine.close()
            reconnect = true
            failures += failure.message ?: "Функциональный список не завершён"
        } finally { withContext(NonCancellable) { engine.flushJournal() } }
        val lists = decoded()
        val merged = linkedMapOf<Int, VagOemReadOnly.Installed>()
        for ((owner, list) in lists) {
            if (list.unknownBits.isNotEmpty()) failures += "%02X: неизвестные позиции %s".format(owner, list.unknownBits.joinToString())
            for (entry in list.entries) {
                val old = merged[entry.id]
                val route = VagOemReadOnly.Route(entry.id, VagOemReadOnly.routes[entry.id]?.name ?: "Блок VAG",
                    entry.tp, entry.udsTx, installedOnly = true)
                val conflict = old?.routeConflict == true || (old?.mappedRoute != null &&
                    (old.mappedRoute.tp != route.tp || old.mappedRoute.udsTx != route.udsTx))
                merged[entry.id] = VagOemReadOnly.Installed(entry.id, null, faults,
                    routeConflict = conflict, mappedRoute = old?.mappedRoute ?: route,
                    listOwners = old?.listOwners.orEmpty() + owner)
            }
        }
        val missing = requiredOwners(lists) - lists.keys
        if (missing.isNotEmpty()) failures += "Не подтверждены списки ECU: " + missing.joinToString { "%02X".format(it) }
        if (collection.unknownIds.isNotEmpty()) failures += "Ответы без подтверждённого владельца списка: " + collection.unknownIds.joinToString { "%03X".format(it) }
        if (collection.invalidIds.isNotEmpty()) failures += "Повреждённые ответы: " + collection.invalidIds.joinToString { "%03X".format(it) }
        return Result(merged.values.toList(), lists.keys, failures.distinct(), reconnect)
    }
}
