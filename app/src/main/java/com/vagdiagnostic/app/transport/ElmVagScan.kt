package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
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
    private class UdsNoResponse : IllegalStateException("ECU не ответил на 3E00, 1003 и F187")

    suspend fun scan(engine: ElmEngine,
        onUnit: suspend (ControlUnit) -> Unit,
        event: suspend (String, String, String) -> Unit,
        allowCandidateFallback: Boolean = false,
        readGatewayIdentity: Boolean = false,
        discoverAdditionalUnits: Boolean = true,
        identifyEcus: Boolean = true,
        recreateEngine: (() -> ElmEngine)? = null,
        allowGatewayIdentityFallback: Boolean = true
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
        val udsInfoOverTp = mutableSetOf<Int>()
        val detailsAttempts = mutableSetOf<Int>()
        val details = mutableMapOf<Int, Map<VagUdsInfo.Field, VagUdsInfo.Value>>()
        val absentTp = mutableSetOf<Int>()
        val absentUds = mutableSetOf<Int>()
        val saeAttempts = mutableSetOf<Int>()
        val saeSupport = mutableMapOf<Int, Boolean?>()
        val optionalRetries = mutableMapOf<Int, Int>()
        val gracefulRetries = mutableMapOf<Int, Int>()
        var centralPart: String? = null
        var candidateFallbackPermitted = allowCandidateFallback
        var discoveryReconnectNeeded = false
        var diagnosticAccess: VagDiagnosticAccess.Status? = null
        var currentEngine = engine
        var channelRecoveries = 0
        suspend fun restoreConnection(key: String, cause: Throwable) {
            if (channelRecoveries >= 4) throw IOException("Исчерпан предел восстановления TP-каналов", cause)
            currentCoroutineContext().ensureActive()
            event("VAG_CHANNEL_RECOVERY", "$key: новое подключение перед продолжением очереди", "RECOVERING")
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
        suspend fun restoreClosedChannel(key: String, cause: ElmTp20Channel.PeerClosedChannel) = restoreConnection(key, cause)
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
            val value = if (id == 0x19 && gatewayRejectsService1A) {
                udsInfoOverTp += id
                VagEcuIdentification.udsPartNumber(channel.request("22F187"))
            }
            else {
                val replies = channel.requestMessages("1A9B")
                val kwp = VagEcuIdentification.kwpMessages(replies)
                if (replies.size == 1 && replies.first() in listOf(
                        listOf(0x7F, 0x1A, 0x10), listOf(0x7F, 0x1A, 0x11), listOf(0x7F, 0x1A, 0x12))) {
                    udsInfoOverTp += id
                    VagEcuIdentification.udsPartNumber(channel.request("22F187"))
                } else kwp
            }
            identities[id] = value
            if (id == 0x19) { identity = value; gatewayIdentityAttempted = true }
            return value
        }
        suspend fun collectDetails(id: Int, fields: List<VagUdsInfo.Field>, read: suspend (String) -> List<Int>?) {
            if (!detailsAttempts.add(id)) return
            val result = try { VagUdsInfo.collect(fields, onValue = { field, value ->
                // A later timeout/cancellation must not discard earlier complete fields.
                val saved = details[id].orEmpty() + (field to value)
                details[id] = saved
                identities[id]?.let { identities[id] = VagUdsInfo.merge(it, saved) }
                if (id == 0x19) identity = identities[id]
                event("VAG_ECU_INFO_%02X_%s".format(id, field.name), value.state.name,
                    if (value.state == VagUdsInfo.State.PRESENT) "PARSED" else "INCOMPLETE")
            }, read = read) }
                catch (bad: IllegalArgumentException) { throw IOException("Неверная идентификация ECU: ${bad.message}", bad) }
            event("VAG_ECU_INFO_%02X".format(id), result.entries.joinToString { "${it.key.name}: ${it.value.state}" },
                if (result.values.all { it.state == VagUdsInfo.State.PRESENT }) "PARSED" else "INCOMPLETE")
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
        catch (closed: ElmTp20Channel.PeerClosedChannel) {
            issues += "Gateway TP2.0: ${closed.message}"
            restoreClosedChannel(gateway.key, closed)
        }
        catch (io: IOException) { throw io }
        catch (failure: ElmTp20Channel.NoChannelResponse) { absentTp += 0x19; issues += "Gateway TP2.0: ${failure.message}" }
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
                    diagnosticAccess = channel.readDiagnosticAccess()
                    event("VAG_DIAGNOSTIC_ACCESS", diagnosticAccess?.let {
                        "Gateway: функция=${it.functionCode}; диагностический фильтр=" + if (it.active) "активен" else "неактивен"
                    } ?: "Статус диагностического фильтра не подтверждён", if (diagnosticAccess != null) "PARSED" else "INCONCLUSIVE")
                    val part = identity?.partNumber
                    if (part != null && VagInstallationBitmap.supports(0x19, part)) {
                        if (VagInstallationBitmap.formatFor(0x19, part) == "mk8") {
                            val collected = ElmVagFunctionalList.read(currentEngine, part)
                            installed = collected.installed.takeIf { it.isNotEmpty() }
                            listLimitations += collected.limitations
                            discoveryReconnectNeeded = collected.requiresReconnect
                            if (discoveryReconnectNeeded) healthy = false
                            else channel.restoreConfiguration()
                            event("VAG_INSTALLATION_FORMAT", "2A26; mk8; owners=" + collected.respondingOwners.joinToString { "%02X".format(it) },
                                if (collected.complete) "PARSED" else "INCOMPLETE")
                        } else {
                        val decoded = VagInstallationBitmap.decode(0x19, part, channel.read("222A26"))
                        installed = decoded.entries.map { entry ->
                            val name = VagOemReadOnly.routes[entry.id]?.name ?: "Блок VAG"
                            VagOemReadOnly.Installed(entry.id, null, false, mappedRoute =
                                VagOemReadOnly.Route(entry.id, name, entry.tp, entry.udsTx, installedOnly = true))
                        }.takeIf { it.isNotEmpty() }
                        event("VAG_INSTALLATION_FORMAT", "2A26; ${decoded.format}; owner=19", "PARSED")
                        if (decoded.unknownBits.isNotEmpty())
                            listLimitations += "Неизвестные позиции списка: ${decoded.unknownBits.joinToString()}"
                        }
                        if (listLimitations.isNotEmpty())
                            event("VAG_INSTALLATION_LIMITATIONS", listLimitations.joinToString("; "), "INCOMPLETE")
                    } else installed = VagOemReadOnly.installed04A1(channel.read("2204A1"))
                } catch (cancel: CancellationException) { healthy = false; throw cancel }
                catch (io: IOException) { healthy = false; throw io }
                finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: UdsNoResponse) { absentUds += 0x19; issues += "Gateway UDS: ${failure.message}" }
            catch (failure: RuntimeException) { issues += "Gateway UDS: ${failure.message}" }
        }
        if (discoveryReconnectNeeded) restoreConnection("Сбор списка ECU", IOException("Функциональный обмен не завершён"))
        if (protocol == null) {
            event("VAG_GATEWAY_DISCOVERY", issues.joinToString("; "), "INCONCLUSIVE")
            if (!candidateFallbackPermitted)
                return Outcome(null, false, false, "Gateway не подтвердил TP2.0 или UDS; ${issues.joinToString("; ")}")
        }
        // Identification has its own short-lived session; a negative or
        // unknown identification does not change a confirmed installation list.
        suspend fun collectIdentity(force: Boolean = false) {
            if ((!readGatewayIdentity && !force) || gatewayIdentityAttempted || protocol == null) return
            gatewayIdentityAttempted = true
            event("VAG_GATEWAY_IDENTITY_BEGIN", "Чтение номера Gateway", "READING")
            try {
                if (protocol == Protocol.TP20) {
                    withTp(gateway) { channel -> identity = tpIdentity(0x19, channel) }
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
        if (!listConfirmed && !candidateFallbackPermitted && allowGatewayIdentityFallback) {
            // A validated, whitelisted Gateway part is direct platform evidence;
            // the fixed catalog must not depend solely on optional VIN service09.
            collectIdentity(force = true)
            if (identity?.partNumber?.let { VagInstallationBitmap.supports(0x19, it) } == true) {
                candidateFallbackPermitted = true
                event("VAG_DISCOVERY_SCOPE", "Подтверждена поддерживаемая идентификация Gateway; разрешён фиксированный каталог без VIN", "CONFIRMED")
            }
        }
        if (!listConfirmed && !candidateFallbackPermitted) {
            collectIdentity()
            event("VAG_INSTALLATION_LIST", "Формат списка не подтверждён; кандидаты для этой платформы не опрашиваются", "UNSUPPORTED")
            return Outcome(protocol, false, false, "Gateway ответил; список для этой платформы не подтверждён" +
                (if (listLimitations.isNotEmpty()) "; " + listLimitations.joinToString("; ") else "") +
                (identity?.let { "; " + it.summary() } ?: ""))
        }
        // Filter status alone does not prove that read services are blocked.
        // Keep the verified manufacturer's fixed discovery scope unchanged.
        val candidateRoutes = if (listConfirmed) emptyMap() else VagOemCandidateCatalog.routes
        event("VAG_INSTALLATION_LIST", if (listConfirmed) "Подтверждены ID: " + installed!!.joinToString { "%02X".format(it.id) }
            else "Список не получен; фиксированный каталог ${candidateRoutes.size} кандидатов, наличие требует ответа ECU", if (listConfirmed) "CONFIRMED" else "INCONCLUSIVE")
        val ids = if (listConfirmed) (listOf(0x19) + installed!!.map { it.id }).distinct()
            else candidateRoutes.keys.toList()
        var failed = !listConfirmed || listLimitations.isNotEmpty()
        var gatewayUnit: ControlUnit? = null
        val queue = ids.toMutableList()
        var next = 0
        var supplementalAttempted = false
        val retriedAfterClose = mutableSetOf<Int>()
        if (identifyEcus) {
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
                catch (_: ElmTp20Channel.NoApplicationResponse) {
                    if (0x09 !in confirmedProtocols) { absentTp += 0x09; tryUds = true }
                }
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
                    catch (_: UdsNoResponse) { absentUds += 0x09 }
                    catch (_: IllegalStateException) { }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                }
                event("VAG_CHASSIS_IDENTITY", centralPart?.let { "09: $it" } ?: "09: номер детали не подтверждён", if (centralPart != null) "PARSED" else "INCONCLUSIVE")
                if (0x09 in confirmedProtocols && 0x09 !in queue) {
                    queue += 0x09
                    event("VAG_ADDITIONAL_DISCOVERY", "09: ответ фиксированного ECU подтверждён вне исходного списка", "RESPONSE")
                }
            }
        }
        suspend fun discoverAdditional() {
            // This is a fixed legacy procedure, not a sweep of the route catalog.
            // Determine the chassis from ECU09's own identity, never from VIN or
            // the Gateway part. An unconfirmed central ECU cannot select it.
            if (!discoverAdditionalUnits || !listConfirmed || 0x61 in queue) return
            val centralProtocol = confirmedProtocols[0x09]
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
                } else if (centralProtocol == Protocol.UDS) {
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
            val route = listEntry?.mappedRoute ?: if (listConfirmed) VagOemReadOnly.routes[id] else candidateRoutes[id]
            val responseConfirmed = id in confirmedProtocols
            val declared = listConfirmed && id in ids
            var unit = ControlUnit("VAG-%02X".format(id), route?.name ?: "Блок VAG",
                ReadState.NOT_QUERIED, note = if (declared) "Подтверждён списком ECU " +
                    (listEntry?.listOwners ?: setOf(0x19)).joinToString { "%02X".format(it) } + "; чтение DTC ещё не выполнено"
                else if (responseConfirmed) "Ответ ECU подтверждён; DTC ещё не прочитаны"
                else "Кандидат профиля; наличие не подтверждено", source = if (responseConfirmed) "RESPONSE" else if (declared)
                    if (listEntry?.listOwners?.any { it != 0x19 } == true) "INSTALLATION_LIST" else "GATEWAY" else "CANDIDATE")
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
                suspend fun readDtcDetails(selected: VagDtcDetails.Protocol, faults: VagOemReadOnly.Faults,
                    read: suspend (VagDtcDetails.Request) -> List<Int>?) {
                    for (fault in faults.values.distinctBy { it.raw.take(if (selected == VagDtcDetails.Protocol.KWP) 4 else 6) }) {
                        currentCoroutineContext().ensureActive()
                        val request = VagDtcDetails.request(selected, fault)
                        val asamKey = if (selected == VagDtcDetails.Protocol.UDS) VagUdsDtcRecords.asamKey(
                            details[id]?.get(VagUdsInfo.Field.ASAM)?.text, details[id]?.get(VagUdsInfo.Field.ASAM_REVISION)?.text) else null
                        val reply = try { VagDtcDetails.decode(request, read(request), asamKey) }
                            catch (missing: ElmTp20Channel.NoApplicationResponse) {
                                event("VAG_DTC_DETAILS_%02X".format(id), "${fault.code}: нет дополнительного ответа; основной DTC сохранён", "INCOMPLETE")
                                break
                            } catch (bad: IllegalArgumentException) {
                                throw IOException("Некорректный ответ дополнительных записей DTC: ${bad.message}", bad)
                            }
                        event("VAG_DTC_DETAILS_%02X".format(id), "${fault.code}: ${reply.state}" + (reply.kwpRecords?.let { "; записей=${it.records.size}; структура=${if (it.complete) "полная" else "частичная"}" } ?: "") + (reply.udsRecords?.let { "; записей=${it.records.size}; структура=${if (it.complete) "полная" else "частичная"}" } ?: "") + (reply.nrc?.let { "; NRC 0x%02X".format(it) } ?: "") + (reply.issue?.let { "; $it" } ?: "") + "; расшифровка дополнительных записей не подтверждена",
                            if (reply.state == VagDtcDetails.State.RAW_RESPONSE) "RAW_ONLY" else "INCOMPLETE")
                    }
                }
                suspend fun tpReadOnce(): VagOemReadOnly.Faults {
                    var result: VagOemReadOnly.Faults? = null
                    withTp(route) { channel ->
                        confirmedConnection(Protocol.TP20)
                        val powertrain = if (identifyEcus) {
                            try {
                                tpIdentity(id, channel)
                                if (id in udsInfoOverTp) collectDetails(id, VagUdsInfo.scanFields) { channel.request(it) }
                                if (saeAttempts.add(id)) saeSupport[id] = VagKwpCapabilities.supportsSaeDtc(channel.request(VagKwpCapabilities.REQUEST))
                            } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                                throw OptionalReadUnavailable(missing)
                            }
                            saeSupport[id] == true
                        } else id == 1 || id == 2 // Recorded pre-capability test sequences only.
                        result = VagOemReadOnly.kwpFaultMessages(
                            channel.requestMessages(if (powertrain) "1800FF00" else "1802FF00"), powertrain)
                        completedFaults = result
                        publishFaults(requireNotNull(result))
                        if (identifyEcus) readDtcDetails(VagDtcDetails.Protocol.KWP, requireNotNull(result)) { channel.requestDetails(it) }
                    }
                    return requireNotNull(result)
                }
                suspend fun tpRead(): VagOemReadOnly.Faults = try { tpReadOnce() }
                    catch (missing: ElmTp20Channel.NoChannelResponse) {
                        // Only a complete NO DATA setup on the listed CAN engine
                        // permits this single retry. Never replay after a broken stream.
                        if (!identifyEcus || id != 0x01 || listEntry == null ||
                            VagConnectionPolicy.isUdsOnlyChassis(centralPart)) throw missing
                        event("VAG_ENGINE_CONNECTION_RETRY", "01: нет ответа CHANNEL SETUP; один повтор через 2000 мс", "RETRYING")
                        delay(2000)
                        currentCoroutineContext().ensureActive()
                        tpReadOnce()
                    }
                suspend fun udsRead(): VagOemReadOnly.Faults {
                    val paths = route.udsPaths(listConfirmed)
                    check(paths.isNotEmpty()) { "Нет проверенного UDS-маршрута" }
                    var lastFailure: RuntimeException? = null
                    var onlyNoResponse = true
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
                            if (identifyEcus) collectDetails(id, VagUdsInfo.scanFields + VagUdsInfo.Field.ACCESS_STATUS) {
                                channel.readOptionalInfo(it)
                            }
                            val faults = VagOemReadOnly.udsFaults(channel.read("19028D"))
                            completedFaults = faults
                            publishFaults(faults)
                            if (identifyEcus && faults.values.isNotEmpty()) {
                                // This DTC enrichment requires ASAM and revision. A
                                // failed optional preflight is not a cached success.
                                if (details[id]?.get(VagUdsInfo.Field.ASAM)?.state != VagUdsInfo.State.PRESENT) {
                                    val asam = try { VagUdsInfo.decode(VagUdsInfo.Field.ASAM, channel.readOptionalInfo("22F19E")) }
                                        catch (bad: IllegalArgumentException) { throw IOException("Неверная идентификация ASAM", bad) }
                                    details[id] = details[id].orEmpty() + (VagUdsInfo.Field.ASAM to asam)
                                }
                            }
                            if (identifyEcus && faults.values.isNotEmpty() && details[id]?.get(VagUdsInfo.Field.ASAM)?.state == VagUdsInfo.State.PRESENT) {
                                val revision = try { VagUdsInfo.decode(VagUdsInfo.Field.ASAM_REVISION, channel.readOptionalInfo("22F1A2")) }
                                    catch (bad: IllegalArgumentException) { throw IOException("Неверная ревизия ASAM", bad) }
                                details[id] = details[id].orEmpty() + (VagUdsInfo.Field.ASAM_REVISION to revision)
                                if (revision.state == VagUdsInfo.State.PRESENT)
                                    readDtcDetails(VagDtcDetails.Protocol.UDS, faults) { channel.readDtcDetails(it) }
                                else event("VAG_DTC_DETAILS_%02X".format(id), "Ревизия ASAM не подтверждена; основной DTC сохранён", "INCOMPLETE")
                            }
                            return faults
                        } catch (cancel: CancellationException) { healthy = false; throw cancel }
                        catch (io: IOException) { healthy = false; throw io }
                        catch (failure: RuntimeException) {
                            if (failure is VagOemReadOnly.ServiceRejected) throw failure
                            lastFailure = failure
                            if (failure !is UdsNoResponse) onlyNoResponse = false
                            if (index < paths.lastIndex) event("VAG_ECU_ADDRESS_FALLBACK",
                                "${route.key}: завершённый отказ ${path.udsTx?.toString(16)}; ${failure.message}", "INCONCLUSIVE")
                        } finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                    }
                    if (onlyNoResponse) absentUds += id
                    throw requireNotNull(lastFailure)
                }
                val state = VagConnectionPolicy.State(route.tp != null, route.udsTx != null,
                    udsOnlyChassis = identifyEcus && VagConnectionPolicy.isUdsOnlyChassis(centralPart),
                    connectedTp = confirmedProtocols[id] == Protocol.TP20,
                    connectedUds = confirmedProtocols[id] == Protocol.UDS,
                    tpNoResponse = id in absentTp, udsNoResponse = id in absentUds)
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
                if (!faultsPublished) publishFaults(result)
            } catch (cancel: CancellationException) {
                completedFaults?.takeIf { !faultsPublished }?.let { result ->
                    withContext(NonCancellable) { publishFaults(result) }
                }
                throw cancel
            }
            catch (closed: ElmTp20Channel.PeerClosedChannel) {
                if (completedFaults != null) {
                    if (!faultsPublished) publishFaults(requireNotNull(completedFaults))
                    event("VAG_DTC_DETAILS_%02X".format(id), "ECU закрыл канал после основного DTC; дополнительные записи не подтверждены", "INCOMPLETE")
                    restoreClosedChannel(route.key, closed)
                } else {
                    onUnit(unit.copy(state = ReadState.PARTIAL,
                        note = "ECU закрыл канал до DTC; остальные блоки будут опрошены", source = "RESPONSE"))
                    restoreClosedChannel(route.key, closed)
                    if (retriedAfterClose.add(id)) queue += id else failed = true
                }
            }
            catch (io: IOException) {
                completedFaults?.takeIf { !faultsPublished }?.let { result ->
                    withContext(NonCancellable) { publishFaults(result) }
                }
                onUnit(unit.copy(state = if (completedFaults != null) ReadState.COMPLETE else
                    if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = if (completedFaults != null) "Память DTC прочитана; дальнейший обмен прерван: ${io.message}"
                        else "Соединение потеряно; DTC не подтверждены: ${io.message}"))
                throw IOException("${route.key}: ${io.message}", io)
            }
            catch (closed: ElmTp20Channel.GracefullyClosedChannel) {
                if (completedFaults != null) {
                    if (!faultsPublished) publishFaults(requireNotNull(completedFaults))
                    event("VAG_DTC_DETAILS_%02X".format(id), "Канал штатно завершён после DTC; дополнительные записи не получены", "INCOMPLETE")
                } else {
                    val retries = gracefulRetries.getOrDefault(id, 0)
                    val retry = retries < 3
                    unit = unit.copy(state = ReadState.PARTIAL,
                        note = if (retry) "ECU завершил служебный ответ; DTC будут прочитаны в новом канале"
                            else "ECU завершает канал до DTC; предел повторов достигнут", source = "RESPONSE")
                    onUnit(unit)
                    if (retry) {
                        gracefulRetries[id] = retries + 1
                        queue += id
                    } else failed = true
                    event("VAG_CHANNEL_GRACEFUL_CLOSE", "${route.key}: ${unit.note}", if (retry) "RETRYING" else "INCOMPLETE")
                }
            }
            catch (optional: OptionalReadUnavailable) {
                val retries = optionalRetries.getOrDefault(id, 0)
                if (retries >= 3) throw IOException("Исчерпан предел дополнительного чтения ${route.key}", optional)
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
        val summary = if (listConfirmed) "список установленных ECU получен; опрос ${if (failed) "неполный" else "завершён"}"
            else "список Gateway недоступен; проверены кандидаты фиксированного каталога"
        return Outcome(protocol, listConfirmed, !failed, "VAG ${protocol?.name ?: "Gateway не ответил"}; " + summary +
            (if (listLimitations.isNotEmpty()) "; " + listLimitations.joinToString("; ") else "") +
            (if (diagnosticAccess?.active == true) "; На Gateway активен диагностический фильтр; возможные ограничения оцениваются по ответам ECU" else "") +
            (identity?.let { "; " + it.summary() } ?: ""))
    }

    private class Uds(private val engine: ElmEngine) {
        private var route: VagOemReadOnly.Route? = null
        private var active = false
        private var pendingMs = 5250L
        private var cachedPartReply: List<Int>? = null
        private suspend fun configure(target: VagOemReadOnly.Route) {
            val tx = requireNotNull(target.udsTx) { "Нет проверенного UDS-маршрута" }
            val rx = requireNotNull(target.udsRx)
            suspend fun ok(cmd: String) {
                val raw = engine.command(cmd).uppercase().replace(cmd, "").filterNot { it.isWhitespace() }
                if (raw != "OK") throw IOException("ELM отклонил $cmd")
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
                if (engine.command("ATDPN").trim().uppercase() !in setOf("7", "A7")) throw IOException("ELM не подтвердил CAN 7")
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
        }
        suspend fun restoreConfiguration() = configure(requireNotNull(route))
        suspend fun open(target: VagOemReadOnly.Route) {
            configure(target)
            // Presence is independent of extended-session support. A complete
            // matching negative reply also proves that the addressed ECU answered.
            val presence = readOrNoData("3E00", allowNoData = true)
            if (!(presence == null || presence == listOf(0x7E, 0) ||
                (presence.size == 3 && presence.take(2) == listOf(0x7F, 0x3E)))) throw IOException("Нет подтверждения присутствия UDS ECU")
            val reply = readOrNoData("1003", allowNoData = true)
            active = reply?.take(2) == listOf(0x50, 3)
            if (presence == null && reply == null) {
                cachedPartReply = readOrNoData("22F187", allowNoData = true)
                        if (cachedPartReply == null) throw UdsNoResponse()
            }
            if (active && reply != null && reply.size >= 6) {
                val star = ((reply[4] shl 8) or reply[5]) * 10L
                if (star !in 1..5750) throw IOException("UDS P2* вне поддержанного диапазона")
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
        suspend fun readOptionalInfo(command: String): List<Int>? {
            require(command in VagUdsInfo.Field.entries.map { it.request })
            return readOrNoData(command, true)
        }
        suspend fun readDtcDetails(request: VagDtcDetails.Request): List<Int>? {
            require(request.protocol == VagDtcDetails.Protocol.UDS)
            return readOrNoData(request.command, true)
        }
        suspend fun readDiagnosticAccess(): VagDiagnosticAccess.Status? {
            require(route?.id == 0x19)
            return try { VagDiagnosticAccess.decode(readOrNoData(VagDiagnosticAccess.REQUEST, true)) }
                catch (bad: IllegalArgumentException) { throw IOException("Некорректный статус доступа: ${bad.message}", bad) }
        }

        private suspend fun readOrNoData(command: String, allowNoData: Boolean): List<Int>? {
            require(Regex("1906[0-9A-F]{6}FF").matches(command) || command in setOf("3E00", "1003", "1001", "2204A1", "222A26", "19028D", "22F187", VagDiagnosticAccess.REQUEST) ||
                command in VagUdsInfo.Field.entries.map { it.request })
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
                        return@withTimeoutOrNull ElmUdsPending.read(engine, target, command, raw, pendingMs, pending)
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
