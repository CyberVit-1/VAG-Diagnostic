package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Legacy CAN connection, UDS equivalent and gateway-list driven read path.
 * Runs only after the existing profile/adapter gates, on a disposable ElmEngine.
 */
object ElmVagScan {
    enum class Protocol { TP20, UDS }
    data class Outcome(val protocol: Protocol?, val listConfirmed: Boolean, val complete: Boolean, val note: String)

    suspend fun scan(engine: ElmEngine,
        onUnit: suspend (ControlUnit) -> Unit,
        event: suspend (String, String, String) -> Unit,
        allowCandidateFallback: Boolean = true,
        readGatewayIdentity: Boolean = false
    ): Outcome {
        var protocol: Protocol? = null
        var installed: List<VagOemReadOnly.Installed>? = null
        var gatewayRejectsService1A = false
        val issues = mutableListOf<String>()
        val gateway = VagOemReadOnly.gateway
        suspend fun withTp(route: VagOemReadOnly.Route, action: suspend (ElmTp20Channel) -> Unit) {
            val channel = ElmTp20Channel(engine)
            var healthy = true
            try {
                channel.open(requireNotNull(route.tp))
                val session = channel.request("1089")
                check(session.take(2) == listOf(0x50, 0x89)) { "Нет подтверждения KWP-сессии 1089" }
                action(channel)
            } catch (cancel: CancellationException) {
                healthy = false
                throw cancel
            } catch (failure: IOException) {
                healthy = false
                throw failure
            } finally {
                // Cancellation stops traffic immediately. Broken streams are discarded
                // by the caller rather than used to send a second protocol's commands.
                try {
                    if (healthy && currentCoroutineContext().isActive) {
                        try { channel.close() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (io: IOException) { throw io }
                        catch (failure: IllegalStateException) { throw IOException("Не удалось завершить TP2.0; сессия будет закрыта", failure) }
                    }
                } finally { withContext(NonCancellable) { engine.flushJournal() } }
            }
        }
        try {
            withTp(gateway) { channel ->
                protocol = Protocol.TP20
                event("VAG_GATEWAY_CONNECTED", "TP2.0 destination=1F; VAG=19; read session confirmed", "CONFIRMED")
                val replies = channel.requestMessages("1A9F")
                gatewayRejectsService1A = replies == listOf(listOf(0x7F, 0x1A, 0x11))
                installed = VagOemReadOnly.installedKwpMessages(replies)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (io: IOException) { throw io }
        catch (failure: IllegalStateException) { issues += "Gateway TP2.0: ${failure.message}" }
        catch (failure: IllegalArgumentException) { issues += "Список TP2.0: ${failure.message}" }

        // A live TP2.0 gateway may expose the UDS-style 04A1 list over that same
        // transport. Do not mistake a service ID for a transport or VAG address.
        if (protocol == Protocol.TP20 && installed == null) {
            try { withTp(gateway) { installed = VagOemReadOnly.installed04A1Messages(it.requestMessages("2204A1")) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) { issues += "Список 04A1/TP2.0: ${failure.message}" }
        }
        if (protocol == null) {
            try {
                val channel = Uds(engine)
                var healthy = true
                try {
                    channel.open(gateway)
                    protocol = Protocol.UDS
                    event("VAG_GATEWAY_CONNECTED", "UDS 710/77A; VAG=19; ECU presence confirmed", "CONFIRMED")
                    // Only the recovered legacy 04A1 format is enabled in this 1K
                    // profile; part-number-dependent MQB bitmaps are not guessed.
                    installed = VagOemReadOnly.installed04A1(channel.read("2204A1"))
                } catch (cancel: CancellationException) { healthy = false; throw cancel }
                catch (io: IOException) { healthy = false; throw io }
                finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) { issues += "Gateway UDS: ${failure.message}" }
        }
        if (protocol == null) {
            event("VAG_GATEWAY_DISCOVERY", issues.joinToString("; "), "INCONCLUSIVE")
            return Outcome(null, false, false, "Gateway не подтвердил TP2.0 или UDS; ${issues.joinToString("; ")}")
        }
        // Identification has its own short-lived session; a negative or
        // unknown identification does not change a confirmed installation list.
        var identity: VagEcuIdentification.Identity? = null
        suspend fun collectIdentity() {
            if (!readGatewayIdentity) return
            event("VAG_GATEWAY_IDENTITY_BEGIN", "Чтение номера Gateway", "READING")
            try {
                if (protocol == Protocol.TP20) {
                    withTp(gateway) { channel ->
                        if (gatewayRejectsService1A) {
                            identity = VagEcuIdentification.udsPartNumber(channel.request("22F187"))
                        } else {
                            val replies = channel.requestMessages("1A9B")
                            identity = VagEcuIdentification.kwp(replies.first())
                            if (replies.size == 1 && replies.first() in listOf(
                                    listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12), listOf(0x7F, 0x1A, 0x31)))
                                identity = VagEcuIdentification.udsPartNumber(channel.request("22F187"))
                        }
                    }
                } else {
                    val channel = Uds(engine)
                    var healthy = true
                    try {
                        channel.open(gateway)
                        identity = VagEcuIdentification.udsPartNumber(channel.read("22F187"))
                    } catch (cancel: CancellationException) { healthy = false; throw cancel }
                    catch (io: IOException) { healthy = false; throw io }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                }
                event("VAG_GATEWAY_IDENTITY", identity?.summary() ?: "Идентификация не поддержана или формат не распознан",
                    if (identity != null) "PARSED" else "UNSUPPORTED")
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) {
                event("VAG_GATEWAY_IDENTITY", "Идентификация не подтверждена: ${failure.message}", "INCONCLUSIVE")
            }
        }
        val listConfirmed = installed != null
        if (!listConfirmed && !allowCandidateFallback) {
            collectIdentity()
            event("VAG_INSTALLATION_LIST", "Формат списка не подтверждён; кандидаты для этой платформы не опрашиваются", "UNSUPPORTED")
            return Outcome(protocol, false, false, "Gateway ответил; список для этой платформы не подтверждён" +
                (identity?.let { "; " + it.summary() } ?: ""))
        }
        event("VAG_INSTALLATION_LIST", if (listConfirmed) "Подтверждены ID: " + installed!!.joinToString { "%02X".format(it.id) }
            else "Список не получен; ограниченная очередь известных адресов профиля, не список установленных блоков", if (listConfirmed) "CONFIRMED" else "INCONCLUSIVE")
        val ids = if (listConfirmed) (listOf(0x19) + installed!!.map { it.id }).distinct()
            else VagOemReadOnly.routes.values.filterNot { it.installedOnly }.map { it.id }
        var failed = !listConfirmed
        var gatewayUnit: ControlUnit? = null
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            val route = VagOemReadOnly.routes[id]
            val declared = listConfirmed || id == 0x19
            var unit = ControlUnit("VAG-%02X".format(id), route?.name ?: "Блок VAG",
                ReadState.NOT_QUERIED, note = if (declared) "Подтверждён Gateway; чтение DTC ещё не выполнено"
                else "Кандидат профиля; наличие не подтверждено", source = if (declared) "GATEWAY" else "CANDIDATE")
            onUnit(unit)
            if (route == null) {
                failed = true
                onUnit(unit.copy(state = ReadState.NOT_QUERIED, note = "Gateway сообщил блок, но проверенного маршрута в профиле нет"))
                continue
            }
            val listEntry = installed?.firstOrNull { it.id == id }
            if (listEntry?.routeConflict == true) {
                failed = true
                onUnit(unit.copy(note = "Gateway сообщил конфликтующие маршруты; опрос пропущен"))
                event("VAG_ROUTE_CONFLICT", route.key, "UNSUPPORTED")
                continue
            }
            val advertisedTp = listEntry?.tpDestination
            if (advertisedTp != null && route.tp != null && advertisedTp != route.tp) {
                failed = true
                val note = "Адрес из списка Gateway не совпал с известным маршрутом; опрос пропущен"
                onUnit(unit.copy(note = note))
                event("VAG_ROUTE_MISMATCH", "${route.key}: list=%02X; profile=%02X".format(advertisedTp, route.tp), "UNSUPPORTED")
                continue
            }
            try {
                suspend fun confirmedConnection() {
                    unit = unit.copy(source = "RESPONSE", note = "Ответ ECU подтверждён; чтение DTC")
                    onUnit(unit)
                }
                suspend fun tpRead(): VagOemReadOnly.Faults {
                    var result: VagOemReadOnly.Faults? = null
                    withTp(route) { channel ->
                        confirmedConnection()
                        val powertrain = id == 1 || id == 2
                        result = VagOemReadOnly.kwpFaultMessages(
                            channel.requestMessages(if (powertrain) "1800FF00" else "1802FF00"), powertrain)
                    }
                    return requireNotNull(result)
                }
                suspend fun udsRead(): VagOemReadOnly.Faults {
                    val paths = route.udsPaths(listConfirmed)
                    check(paths.isNotEmpty()) { "Нет проверенного UDS-маршрута" }
                    var lastFailure: RuntimeException? = null
                    for ((index, path) in paths.withIndex()) {
                        val channel = Uds(engine)
                        var healthy = true
                        try {
                            channel.open(path)
                            confirmedConnection()
                            return VagOemReadOnly.udsFaults(channel.read("19028D"))
                        } catch (cancel: CancellationException) { healthy = false; throw cancel }
                        catch (io: IOException) { healthy = false; throw io }
                        catch (failure: RuntimeException) {
                            lastFailure = failure
                            if (index < paths.lastIndex) event("VAG_ECU_ADDRESS_FALLBACK",
                                "${route.key}: завершённый отказ ${path.udsTx?.toString(16)}; ${failure.message}", "INCONCLUSIVE")
                        } finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                    }
                    throw requireNotNull(lastFailure)
                }
                val result = if (route.tp != null && (protocol == Protocol.TP20 || route.udsTx == null)) {
                    try { tpRead() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (io: IOException) { throw io }
                    catch (failure: RuntimeException) {
                        // No fallback on a transport failure. A completed negative or
                        // invalid protocol result may try the known equivalent route.
                        if (route.udsTx == null) throw failure
                        event("VAG_ECU_PROTOCOL_FALLBACK", "${route.key}: TP2.0 ${failure.message}; known UDS route", "INCONCLUSIVE")
                        udsRead()
                    }
                } else udsRead()
                unit = unit.copy(state = ReadState.COMPLETE, dtcs = result.values, answeredModes = setOf(DtcKind.OEM),
                    note = "${result.service}: положительный полный ответ; статус OEM сохранён", source = "RESPONSE")
                if (id == 0x19) gatewayUnit = unit
                onUnit(unit)
                event("VAG_DTC_%02X".format(id), "${result.service}; DTC=${result.values.size}", "PARSED")
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) {
                onUnit(unit.copy(state = if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = "Соединение потеряно; DTC не подтверждены: ${io.message}"))
                throw IOException("${route.key}: ${io.message}", io)
            }
            catch (failure: RuntimeException) {
                failed = true
                unit = unit.copy(state = if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = "DTC не подтверждены: ${failure.message}")
                if (id == 0x19) gatewayUnit = unit
                onUnit(unit)
                event("VAG_DTC_%02X".format(id), unit.note, "INCOMPLETE")
            }
        }
        // DTC results have already been checkpointed before optional metadata.
        collectIdentity()
        val savedGateway = gatewayUnit
        if (identity != null && savedGateway != null)
            onUnit(savedGateway.copy(note = savedGateway.note + "; " + identity!!.summary()))
        val summary = if (listConfirmed) "список Gateway получен; опрос ${if (failed) "неполный" else "завершён"}"
            else "список Gateway недоступен; опрошены только кандидаты профиля"
        return Outcome(protocol, listConfirmed, !failed, "VAG ${protocol.name}; " + summary +
            (identity?.let { "; " + it.summary() } ?: ""))
    }

    private class Uds(private val engine: ElmEngine) {
        private var route: VagOemReadOnly.Route? = null
        private var active = false
        private var pendingMs = 5250L
        suspend fun open(target: VagOemReadOnly.Route) {
            val tx = requireNotNull(target.udsTx) { "Нет проверенного UDS-маршрута" }
            val rx = requireNotNull(target.udsRx)
            suspend fun ok(cmd: String) {
                val raw = engine.command(cmd).uppercase().replace(cmd, "").filterNot { it.isWhitespace() }
                check(raw == "OK") { "ELM отклонил $cmd" }
            }
            val protocol = if (target.is29Bit) "7" else "6"
            // Keep automatic FC disabled until the new ID/filter/mode is ready.
            for (cmd in listOf("ATSP$protocol", "ATH1", "ATD0", "ATCAF1", "ATCFC0", "ATV0", "ATCEA", "ATAT0", "ATSTFF")) ok(cmd)
            if (target.is29Bit) {
                engine.needsFlowControlReset = true
                ok("ATCP%02X".format(tx ushr 24))
                ok("ATSH%06X".format(tx and 0xFFFFFF))
                ok("ATCRA%08X".format(rx))
                // The non-OBD 29-bit route needs its exact FC transmit ID.
                ok("ATFCSH%08X".format(tx)); ok("ATFCSD300000"); ok("ATFCSM1")
                check(engine.command("ATDPN").trim().uppercase() in setOf("7", "A7")) { "ELM не подтвердил CAN 7" }
            } else {
                if (engine.needsFlowControlReset) {
                    try { ok("ATFCSM0") }
                    catch (bad: IllegalStateException) { throw IOException("Не удалось восстановить автоматический flow control", bad) }
                    engine.needsFlowControlReset = false
                }
                ok("ATSH%03X".format(tx)); ok("ATCRA%03X".format(rx))
            }
            ok("ATCFC1")
            route = target
            // Presence is independent of extended-session support. A complete
            // matching negative reply also proves that the addressed ECU answered.
            val presence = read("3E00")
            check(presence == listOf(0x7E, 0) ||
                (presence.size == 3 && presence.take(2) == listOf(0x7F, 0x3E))) { "Нет подтверждения присутствия UDS ECU" }
            val reply = readOrNoData("1003", allowNoData = true)
            active = reply?.take(2) == listOf(0x50, 3)
            if (active && reply != null && reply.size >= 6) {
                val star = ((reply[4] shl 8) or reply[5]) * 10L
                check(star in 1..5750) { "UDS P2* вне поддержанного диапазона" }
                pendingMs = star + 250
            }
        }
        suspend fun close() {
            if (!active) return
            active = false
            val reply = read("1001")
            if (reply.take(2) != listOf(0x50, 1)) throw IOException("ECU не подтвердил возврат в штатную UDS-сессию")
        }
        suspend fun read(command: String): List<Int> = requireNotNull(readOrNoData(command, false))

        private suspend fun readOrNoData(command: String, allowNoData: Boolean): List<Int>? {
            require(command in setOf("3E00", "1003", "1001", "2204A1", "19028D", "22F187"))
            val target = requireNotNull(route)
            val result = withTimeoutOrNull(20000) {
                    val rx = requireNotNull(target.udsRx)
                    var raw = engine.command(command, 8500)
                    var pending = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (raw.trim().uppercase() == "NO DATA" && pending == 0) {
                            if (allowNoData) return@withTimeoutOrNull emptyList<Int>()
                            error("ELM завершил UDS $command без ответа")
                        }
                        val decoded = try { VagUdsTransaction.decode(raw, rx, command) }
                            catch (bad: IllegalArgumentException) { throw IOException(bad.message, bad) }
                        pending += decoded.pendingCount
                        if (pending > 8) throw IOException("Слишком много UDS ResponsePending")
                        decoded.payload?.let { return@withTimeoutOrNull it }
                        // No new diagnostic request, session-close or tester-present
                        // may run until this SID has a final response.
                        raw = engine.receivePendingUdsFrame(rx, pendingMs)
                    }
                    @Suppress("UNREACHABLE_CODE") emptyList<Int>()
            }
            if (result == null) {
                engine.close()
                throw IOException("Превышен общий срок ожидания UDS $command")
            }
            return result.ifEmpty { null }
        }
    }
}
