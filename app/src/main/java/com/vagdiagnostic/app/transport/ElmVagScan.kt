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
    private class OptionalReadUnavailable(cause: Throwable) : IllegalStateException("Дополнительная информация не получена; DTC требуют нового канала", cause)

    suspend fun scan(engine: ElmEngine,
        onUnit: suspend (ControlUnit) -> Unit,
        event: suspend (String, String, String) -> Unit,
        allowCandidateFallback: Boolean = true,
        readGatewayIdentity: Boolean = false,
        discoverAdditionalUnits: Boolean = true,
        identifyEcus: Boolean = true,
        recreateEngine: (() -> ElmEngine)? = null
    ): Outcome {
        var protocol: Protocol? = null
        var installed: List<VagOemReadOnly.Installed>? = null
        var gatewayRejectsService1A = false
        var legacyListFallbackAllowed = false
        var identity: VagEcuIdentification.Identity? = null
        var gatewayIdentityAttempted = false
        val listLimitations = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val gateway = VagOemReadOnly.gateway
        val confirmedProtocols = mutableMapOf<Int, Protocol>()
        val identities = mutableMapOf<Int, VagEcuIdentification.Identity?>()
        val identityAttempts = mutableSetOf<Int>()
        val absentTp = mutableSetOf<Int>()
        val saeAttempts = mutableSetOf<Int>()
        val saeSupport = mutableMapOf<Int, Boolean?>()
        val optionalRetries = mutableMapOf<Int, Int>()
        var centralPart: String? = null
        var currentEngine = engine
        var channelRecoveries = 0
        suspend fun restoreClosedChannel(key: String, cause: ElmTp20Channel.PeerClosedChannel) {
            if (channelRecoveries >= 4) throw IOException("Исчерпан предел восстановления TP-каналов", cause)
            currentCoroutineContext().ensureActive()
            event("VAG_CHANNEL_RECOVERY", "$key: явный A8; новое подключение перед продолжением очереди", "RECOVERING")
            currentEngine.close()
            // Production supplies a fresh link as well as an engine. A closed
            // BLE notification channel cannot be reopened in the same object.
            currentEngine = recreateEngine?.invoke() ?: currentEngine
            currentEngine.connect()
            if (!currentEngine.vehicleReady) throw IOException("OBD после закрытия TP-канала не подтверждён")
            currentCoroutineContext().ensureActive()
            channelRecoveries++
            event("VAG_CHANNEL_RECOVERY", "$key: OBD подтверждён; очередь продолжается; повтор не более одного", "CONFIRMED")
        }
        suspend fun withTp(route: VagOemReadOnly.Route, action: suspend (ElmTp20Channel) -> Unit) {
            val channel = ElmTp20Channel(currentEngine)
            var healthy = true
            try {
                channel.open(requireNotNull(route.tp))
                val reply = channel.request("1089")
                // A final rejection of the optional session request proves a
                // responding ECU; default-session read services may still work.
                if (reply.take(2) != listOf(0x7F, 0x10)) {
                    val session = VagOemReadOnly.positive(reply, 0x10)
                    check(session.firstOrNull() == 0x89) { "Нет подтверждения KWP-сессии 1089" }
                }
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
                } finally { withContext(NonCancellable) { currentEngine.flushJournal() } }
            }
        }
        suspend fun tpIdentity(id: Int, channel: ElmTp20Channel): VagEcuIdentification.Identity? {
            if (id in identityAttempts) return identities[id]
            identityAttempts += id
            val value = if (id == 0x19 && gatewayRejectsService1A)
                VagEcuIdentification.udsPartNumber(channel.request("22F187"))
            else {
                val replies = channel.requestMessages("1A9B")
                val kwp = VagEcuIdentification.kwpMessages(replies)
                if (replies.size == 1 && replies.first() in listOf(
                        listOf(0x7F, 0x1A, 0x10), listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12)))
                    VagEcuIdentification.udsPartNumber(channel.request("22F187")) else kwp
            }
            identities[id] = value
            if (id == 0x19) { identity = value; gatewayIdentityAttempted = true }
            return value
        }
        try {
            withTp(gateway) { channel ->
                protocol = Protocol.TP20
                confirmedProtocols[0x19] = Protocol.TP20
                event("VAG_GATEWAY_CONNECTED", "TP2.0 destination=1F; VAG=19; read session confirmed", "CONFIRMED")
                val replies = channel.requestMessages("1A9F")
                gatewayRejectsService1A = replies == listOf(listOf(0x7F, 0x1A, 0x11))
                legacyListFallbackAllowed = replies.size == 1 && replies.first() in listOf(
                    listOf(0x7F, 0x1A, 0x10), listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12))
                installed = VagOemReadOnly.installedKwpMessages(replies)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (io: IOException) { throw io }
        catch (failure: IllegalStateException) { issues += "Gateway TP2.0: ${failure.message}" }
        catch (failure: IllegalArgumentException) { issues += "Список TP2.0: ${failure.message}" }

        // A live TP2.0 gateway may expose the UDS-style 04A1 list over that same
        // transport. Do not mistake a service ID for a transport or VAG address.
        if (protocol == Protocol.TP20 && installed == null && legacyListFallbackAllowed) {
            try { withTp(gateway) { installed = VagOemReadOnly.installed04A1Messages(it.requestMessages("2204A1")) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) { issues += "Список 04A1/TP2.0: ${failure.message}" }
        }
        if (protocol == null) {
            try {
                val channel = Uds(currentEngine)
                var healthy = true
                try {
                    channel.open(gateway)
                    protocol = Protocol.UDS
                    confirmedProtocols[0x19] = Protocol.UDS
                    event("VAG_GATEWAY_CONNECTED", "UDS 710/77A; VAG=19; ECU presence confirmed", "CONFIRMED")
                    // Select a bitmap only from a validated ECU part number.
                    gatewayIdentityAttempted = true
                    identity = try { VagEcuIdentification.udsPartNumber(channel.read("22F187")) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (io: IOException) { throw io }
                        catch (_: IllegalStateException) { null }
                    identities[0x19] = identity
                    identityAttempts += 0x19
                    event("VAG_GATEWAY_IDENTITY", identity?.summary() ?: "Номер детали не подтверждён",
                        if (identity != null) "PARSED" else "UNSUPPORTED")
                    val part = identity?.partNumber
                    if (part != null && VagInstallationBitmap.supports(0x19, part)) {
                        val decoded = VagInstallationBitmap.decode(0x19, part, channel.read("222A26"))
                        installed = decoded.entries.map { entry ->
                            val name = VagOemReadOnly.routes[entry.id]?.name ?: "Блок VAG"
                            VagOemReadOnly.Installed(entry.id, null, false, mappedRoute =
                                VagOemReadOnly.Route(entry.id, name, entry.tp, entry.udsTx, installedOnly = true))
                        }
                        event("VAG_INSTALLATION_FORMAT", "2A26; ${decoded.format}; owner=19", "PARSED")
                        if (decoded.unknownBits.isNotEmpty())
                            listLimitations += "Неизвестные позиции списка: ${decoded.unknownBits.joinToString()}"
                        if (decoded.format == "mk8")
                            listLimitations += "Дополнительные списки ECU этой платформы ещё не собраны"
                        if (listLimitations.isNotEmpty())
                            event("VAG_INSTALLATION_LIMITATIONS", listLimitations.joinToString("; "), "INCOMPLETE")
                    } else installed = VagOemReadOnly.installed04A1(channel.read("2204A1"))
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
        suspend fun collectIdentity() {
            if (!readGatewayIdentity || gatewayIdentityAttempted) return
            event("VAG_GATEWAY_IDENTITY_BEGIN", "Чтение номера Gateway", "READING")
            try {
                if (protocol == Protocol.TP20) {
                    withTp(gateway) { channel ->
                        if (gatewayRejectsService1A) {
                            identity = VagEcuIdentification.udsPartNumber(channel.request("22F187"))
                        } else {
                            val replies = channel.requestMessages("1A9B")
                            identity = VagEcuIdentification.kwpMessages(replies)
                            if (replies.size == 1 && replies.first() in listOf(
                                    listOf(0x7F, 0x1A, 0x10), listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12)))
                                identity = VagEcuIdentification.udsPartNumber(channel.request("22F187"))
                        }
                    }
                } else {
                    val channel = Uds(currentEngine)
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
        var failed = !listConfirmed || listLimitations.isNotEmpty()
        var gatewayUnit: ControlUnit? = null
        val queue = ids.toMutableList()
        var next = 0
        var supplementalAttempted = false
        val retriedAfterClose = mutableSetOf<Int>()
        if (identifyEcus && 0x09 in ids) {
            val centralEntry = installed?.firstOrNull { it.id == 0x09 }
            val central = centralEntry?.mappedRoute ?: VagOemReadOnly.routes.getValue(0x09)
            val routeValid = centralEntry?.routeConflict != true &&
                (centralEntry?.tpDestination == null || centralEntry.tpDestination == central.tp)
            if (routeValid) {
                var tryUds = central.tp == null
                if (central.tp != null) try {
                    withTp(central) { channel ->
                        confirmedProtocols[0x09] = Protocol.TP20
                        centralPart = tpIdentity(0x09, channel)?.partNumber
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (closed: ElmTp20Channel.PeerClosedChannel) { restoreClosedChannel(central.key, closed) }
                catch (io: IOException) { throw io }
                catch (_: ElmTp20Channel.NoChannelResponse) { absentTp += 0x09; tryUds = true }
                catch (_: IllegalStateException) { }
                if (tryUds && central.udsTx != null) {
                    val channel = Uds(currentEngine)
                    var healthy = true
                    try {
                        channel.open(central)
                        confirmedProtocols[0x09] = Protocol.UDS
                        identityAttempts += 0x09
                        val value = channel.identifyPart()
                        identities[0x09] = value
                        centralPart = value?.partNumber
                    } catch (cancel: CancellationException) { healthy = false; throw cancel }
                    catch (io: IOException) { healthy = false; throw io }
                    catch (_: IllegalStateException) { }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                }
                event("VAG_CHASSIS_IDENTITY", centralPart?.let { "09: $it" } ?: "09: номер детали не подтверждён", if (centralPart != null) "PARSED" else "INCONCLUSIVE")
            }
        }
        suspend fun discoverAdditional() {
            // This is a fixed legacy procedure, not a sweep of the route catalog.
            // Determine the chassis from ECU09's own identity, never from VIN or
            // the Gateway part. An unconfirmed central ECU cannot select it.
            if (!discoverAdditionalUnits || !listConfirmed || 0x61 in queue || 0x09 !in ids) return
            val centralProtocol = confirmedProtocols[0x09] ?: return
            val central = installed?.firstOrNull { it.id == 0x09 }?.mappedRoute
                ?: VagOemReadOnly.routes.getValue(0x09)
            try {
                if (0x09 in identityAttempts) centralPart = identities[0x09]?.partNumber
                else if (centralProtocol == Protocol.TP20) withTp(central) { channel ->
                    val replies = channel.requestMessages("1A9B")
                    centralPart = VagEcuIdentification.kwpMessages(replies)?.partNumber
                    if (replies.size == 1 && replies.first() in listOf(
                            listOf(0x7F, 0x1A, 0x10), listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12)))
                        centralPart = VagEcuIdentification.udsPartNumber(channel.request("22F187"))?.partNumber
                } else {
                    val channel = Uds(currentEngine)
                    var healthy = true
                    try {
                        channel.open(central)
                        centralPart = VagEcuIdentification.udsPartNumber(channel.read("22F187"))?.partNumber
                    } catch (cancel: CancellationException) { healthy = false; throw cancel }
                    catch (io: IOException) { healthy = false; throw io }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (closed: ElmTp20Channel.PeerClosedChannel) { restoreClosedChannel(central.key, closed) }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) {
                event("VAG_CHASSIS_IDENTITY", "09: идентификация не подтверждена; ${failure.message}", "INCONCLUSIVE")
            }
            val udsOnly = VagConnectionPolicy.isUdsOnlyChassis(centralPart)
            if (udsOnly) {
                event("VAG_ADDITIONAL_DISCOVERY", "09: платформа без legacy дополнительного опроса", "SKIPPED")
                return
            }
            val extra = VagOemReadOnly.routes.getValue(0x61)
            var answered = false
            try {
                withTp(extra) { channel ->
                    // A matching complete positive or final diagnostic rejection
                    // is response evidence. A quiet live channel alone is not.
                    channel.requestMessages("1A9B")
                    answered = true
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (closed: ElmTp20Channel.PeerClosedChannel) { restoreClosedChannel(extra.key, closed) }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) {
                // A final session NRC also proves this specific ECU responded.
                answered = answered || failure is VagOemReadOnly.ServiceRejected
                event("VAG_ADDITIONAL_DISCOVERY", "61: ${failure.message}", if (answered) "RESPONSE" else "NO_RESPONSE")
            } finally {
                if (answered) withContext(NonCancellable) {
                    confirmedProtocols[0x61] = Protocol.TP20
                    queue += 0x61
                    onUnit(ControlUnit(extra.key, extra.name, ReadState.NOT_QUERIED,
                        note = "Ответ ECU подтверждён дополнительным опросом; DTC ещё не прочитаны", source = "RESPONSE"))
                }
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (next == queue.size) {
                if (supplementalAttempted) break
                supplementalAttempted = true
                discoverAdditional()
                if (next == queue.size) break
            }
            val id = queue[next++]
            currentCoroutineContext().ensureActive()
            val listEntry = installed?.firstOrNull { it.id == id }
            val route = listEntry?.mappedRoute ?: VagOemReadOnly.routes[id]
            val responseConfirmed = id in confirmedProtocols
            val declared = id in ids && (listConfirmed || id == 0x19)
            var unit = ControlUnit("VAG-%02X".format(id), route?.name ?: "Блок VAG",
                ReadState.NOT_QUERIED, note = if (declared) "Подтверждён Gateway; чтение DTC ещё не выполнено"
                else if (responseConfirmed) "Ответ ECU подтверждён; DTC ещё не прочитаны"
                else "Кандидат профиля; наличие не подтверждено", source = if (responseConfirmed) "RESPONSE" else if (declared) "GATEWAY" else "CANDIDATE")
            onUnit(unit)
            if (route == null) {
                failed = true
                onUnit(unit.copy(state = ReadState.NOT_QUERIED, note = "Gateway сообщил блок, но проверенного маршрута в профиле нет"))
                continue
            }
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
            var completedFaults: VagOemReadOnly.Faults? = null
            var faultsPublished = false
            suspend fun publishFaults(result: VagOemReadOnly.Faults) {
                unit = unit.copy(state = ReadState.COMPLETE, dtcs = result.values, answeredModes = setOf(DtcKind.OEM),
                    note = "${result.service}: положительный полный ответ; статус OEM сохранён", source = "RESPONSE")
                if (id == 0x19) gatewayUnit = unit
                faultsPublished = true
                onUnit(unit)
                event("VAG_DTC_%02X".format(id), "${result.service}; DTC=${result.values.size}", "PARSED")
            }
            try {
                suspend fun confirmedConnection(selected: Protocol) {
                    confirmedProtocols[id] = selected
                    unit = unit.copy(source = "RESPONSE", note = "Ответ ECU подтверждён; чтение DTC")
                    onUnit(unit)
                }
                suspend fun tpRead(): VagOemReadOnly.Faults {
                    var result: VagOemReadOnly.Faults? = null
                    withTp(route) { channel ->
                        confirmedConnection(Protocol.TP20)
                        val powertrain = if (identifyEcus) {
                            try {
                                tpIdentity(id, channel)
                                if (saeAttempts.add(id)) saeSupport[id] = VagKwpCapabilities.supportsSaeDtc(channel.request(VagKwpCapabilities.REQUEST))
                            } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                                throw OptionalReadUnavailable(missing)
                            }
                            saeSupport[id] == true
                        } else id == 1 || id == 2 // Recorded pre-capability test sequences only.
                        result = VagOemReadOnly.kwpFaultMessages(
                            channel.requestMessages(if (powertrain) "1800FF00" else "1802FF00"), powertrain)
                        completedFaults = result
                    }
                    return requireNotNull(result)
                }
                suspend fun udsRead(): VagOemReadOnly.Faults {
                    val paths = route.udsPaths(listConfirmed)
                    check(paths.isNotEmpty()) { "Нет проверенного UDS-маршрута" }
                    var lastFailure: RuntimeException? = null
                    for ((index, path) in paths.withIndex()) {
                        val channel = Uds(currentEngine)
                        var healthy = true
                        try {
                            channel.open(path)
                            confirmedConnection(Protocol.UDS)
                            if (identifyEcus && id !in identityAttempts) {
                                identityAttempts += id
                                identities[id] = channel.identifyPart()
                            }
                            return VagOemReadOnly.udsFaults(channel.read("19028D")).also { completedFaults = it }
                        } catch (cancel: CancellationException) { healthy = false; throw cancel }
                        catch (io: IOException) { healthy = false; throw io }
                        catch (failure: RuntimeException) {
                            if (failure is VagOemReadOnly.ServiceRejected) throw failure
                            lastFailure = failure
                            if (index < paths.lastIndex) event("VAG_ECU_ADDRESS_FALLBACK",
                                "${route.key}: завершённый отказ ${path.udsTx?.toString(16)}; ${failure.message}", "INCONCLUSIVE")
                        } finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                    }
                    throw requireNotNull(lastFailure)
                }
                val state = VagConnectionPolicy.State(route.tp != null, route.udsTx != null,
                    udsOnlyChassis = identifyEcus && VagConnectionPolicy.isUdsOnlyChassis(centralPart),
                    connectedTp = confirmedProtocols[id] == Protocol.TP20,
                    connectedUds = confirmedProtocols[id] == Protocol.UDS,
                    tpNoResponse = id in absentTp)
                val preferTp = if (identifyEcus) VagConnectionPolicy.mustConnectToTp(state)
                    else route.tp != null && ((confirmedProtocols[id] ?: protocol) == Protocol.TP20 || route.udsTx == null)
                val result = if (preferTp) {
                    try { tpRead() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (io: IOException) { throw io }
                    catch (failure: RuntimeException) {
                        if (failure is OptionalReadUnavailable ||
                            (failure is VagOemReadOnly.ServiceRejected && failure.service in setOf(0x18, 0x19)) || completedFaults != null) throw failure
                        // A completed service rejection stays with this ECU. Only a
                        // completed setup/healthy no-response can choose another route.
                        if (failure !is ElmTp20Channel.NoChannelResponse && failure !is ElmTp20Channel.NoApplicationResponse) throw failure
                        absentTp += id
                        if (route.udsTx == null) throw failure
                        event("VAG_ECU_PROTOCOL_FALLBACK", "${route.key}: TP2.0 ${failure.message}; known UDS route", "INCONCLUSIVE")
                        udsRead()
                    }
                } else {
                    check(!identifyEcus || VagConnectionPolicy.mustConnectToUds(state, id in absentTp)) { "Нет доступного подтверждаемого протокола ECU" }
                    udsRead()
                }
                publishFaults(result)
            } catch (cancel: CancellationException) {
                completedFaults?.takeIf { !faultsPublished }?.let { result ->
                    withContext(NonCancellable) { publishFaults(result) }
                }
                throw cancel
            }
            catch (closed: ElmTp20Channel.PeerClosedChannel) {
                onUnit(unit.copy(state = ReadState.PARTIAL,
                    note = "ECU закрыл канал до DTC; остальные блоки будут опрошены", source = "RESPONSE"))
                restoreClosedChannel(route.key, closed)
                if (retriedAfterClose.add(id)) queue += id else failed = true
            }
            catch (io: IOException) {
                completedFaults?.takeIf { !faultsPublished }?.let { result ->
                    withContext(NonCancellable) { publishFaults(result) }
                }
                onUnit(unit.copy(state = if (completedFaults != null) ReadState.COMPLETE else
                    if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = if (completedFaults != null) "Память DTC прочитана; сбой завершения соединения: ${io.message}"
                        else "Соединение потеряно; DTC не подтверждены: ${io.message}"))
                throw IOException("${route.key}: ${io.message}", io)
            }
            catch (optional: OptionalReadUnavailable) {
                val retries = optionalRetries.getOrDefault(id, 0)
                if (retries >= 2) throw IOException("Исчерпан предел дополнительного чтения ${route.key}", optional)
                optionalRetries[id] = retries + 1
                queue += id
                onUnit(unit.copy(state = ReadState.PARTIAL, note = optional.message.orEmpty(), source = "RESPONSE"))
                event("VAG_OPTIONAL_READ_INCOMPLETE", route.key + "; следующий канал пропустит уже проверенные необязательные данные", "INCOMPLETE")
            }
            catch (failure: RuntimeException) {
                completedFaults?.let { result ->
                    if (!faultsPublished) withContext(NonCancellable) { publishFaults(result) }
                    onUnit(unit.copy(note = "Память DTC прочитана; ошибка завершения операции: ${failure.message}"))
                    throw failure
                }
                failed = true
                unit = unit.copy(state = if (unit.source == "RESPONSE" || failure is VagOemReadOnly.ServiceRejected) ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    source = if (failure is VagOemReadOnly.ServiceRejected) "RESPONSE" else unit.source,
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
            (if (listLimitations.isNotEmpty()) "; " + listLimitations.joinToString("; ") else "") +
            (identity?.let { "; " + it.summary() } ?: ""))
    }

    private class Uds(private val engine: ElmEngine) {
        private var route: VagOemReadOnly.Route? = null
        private var active = false
        private var pendingMs = 5250L
        private var cachedPartReply: List<Int>? = null
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
                ok("ATFCSH%08X".format(tx)); ok("ATFCSD300010"); ok("ATFCSM1")
                check(engine.command("ATDPN").trim().uppercase() in setOf("7", "A7")) { "ELM не подтвердил CAN 7" }
            } else {
                if (engine.needsFlowControlReset) {
                    try { ok("ATFCSM0") }
                    catch (bad: IllegalStateException) { throw IOException("Не удалось восстановить автоматический flow control", bad) }
                    engine.needsFlowControlReset = false
                }
                ok("ATSH%03X".format(tx)); ok("ATCRA%03X".format(rx))
                // Non-OBD request/response IDs need the actual physical TX ID
                // for FC as well. OBD's default receive-ID arithmetic is not a
                // substitute for this ECU route. STmin=0x10, block size zero.
                engine.needsFlowControlReset = true
                ok("ATFCSH%03X".format(tx)); ok("ATFCSD300010"); ok("ATFCSM1")
            }
            ok("ATCFC1")
            route = target
            // Presence is independent of extended-session support. A complete
            // matching negative reply also proves that the addressed ECU answered.
            val presence = readOrNoData("3E00", allowNoData = true)
            check(presence == null || presence == listOf(0x7E, 0) ||
                (presence.size == 3 && presence.take(2) == listOf(0x7F, 0x3E))) { "Нет подтверждения присутствия UDS ECU" }
            val reply = readOrNoData("1003", allowNoData = true)
            active = reply?.take(2) == listOf(0x50, 3)
            if (presence == null && reply == null) {
                cachedPartReply = readOrNoData("22F187", allowNoData = true)
                check(cachedPartReply != null) { "ECU не ответил на 3E00, 1003 и F187" }
            }
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
        suspend fun read(command: String): List<Int> =
            (if (command == "22F187") cachedPartReply else null) ?: requireNotNull(readOrNoData(command, false))
        suspend fun identifyPart(): VagEcuIdentification.Identity? =
            (cachedPartReply ?: readOrNoData("22F187", true))?.let { VagEcuIdentification.udsPartNumber(it) }

        private suspend fun readOrNoData(command: String, allowNoData: Boolean): List<Int>? {
            require(command in setOf("3E00", "1003", "1001", "2204A1", "222A26", "19028D", "22F187"))
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
