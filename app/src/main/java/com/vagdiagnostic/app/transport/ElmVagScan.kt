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
    private class FunctionComponentUnavailable(cause: IOException, val id: Int = 0x09) : IOException("Тип компонента не подтверждён: ${cause.message}", cause)
    private class UdsNoResponse(dtcAttempted: Boolean = false) : IllegalStateException(
        if (dtcAttempted) "ECU не ответил на служебные запросы и ограниченное чтение DTC"
        else "ECU не ответил на 3E00, 1003 и F187")

    suspend fun scan(engine: ElmEngine,
        onUnit: suspend (ControlUnit) -> Unit,
        event: suspend (String, String, String) -> Unit,
        allowCandidateFallback: Boolean = false,
        readGatewayIdentity: Boolean = false,
        discoverAdditionalUnits: Boolean = true,
        identifyEcus: Boolean = true,
        recreateEngine: (() -> ElmEngine)? = null,
        allowGatewayIdentityFallback: Boolean = true,
        // Supplemental protocol regression seam. The application does not
        // enable it: normal scans need ECU names/routes and DTC, not inventory.
        readSupplementalInfo: Boolean = false
    ): Outcome = ScanSession(engine, onUnit, event, allowCandidateFallback,
        readGatewayIdentity, discoverAdditionalUnits, identifyEcus, recreateEngine,
        allowGatewayIdentityFallback, readSupplementalInfo).run()

    /** A scan owns its state. Keeping it off coroutine local-variable spills
     * avoids a huge DEX state machine, including its exception-resume paths. */
    private class ScanSession(engine: ElmEngine,
        private val emitUnit: suspend (ControlUnit) -> Unit,
        private val event: suspend (String, String, String) -> Unit,
        allowCandidateFallback: Boolean,
        private val readGatewayIdentity: Boolean,
        private val discoverAdditionalUnits: Boolean,
        private val identifyEcus: Boolean,
        private val recreateEngine: (() -> ElmEngine)?,
        private val allowGatewayIdentityFallback: Boolean,
        private val readSupplementalInfo: Boolean
    ) {
        private val observedUnits = linkedMapOf<String, ControlUnit>()
        private suspend fun onUnit(unit: ControlUnit) {
            val id = unit.address.takeIf { it.startsWith("VAG-") }?.drop(4)?.toIntOrNull(16)
            val hint = installed?.firstOrNull { it.id == id }?.takeIf { !it.installationConfirmed }
                ?.discoverySources?.joinToString { it.description }
            val shown = if (unit.source == "CANDIDATE" && hint != null && !unit.note.contains(hint))
                unit.copy(note = "$hint; ${unit.note}") else unit
            observedUnits[shown.address] = shown
            emitUnit(shown)
        }
        private var protocol: Protocol? = null
        private var installed: List<VagOemReadOnly.Installed>? = null
        private var gatewayRejectsService1A = false
        private var legacyListFallbackAllowed = false
        private var identity: VagEcuIdentification.Identity? = null
        private var gatewayIdentityAttempted = false
        private val listLimitations = mutableListOf<String>()
        private val issues = mutableListOf<String>()
        private val gateway = VagOemReadOnly.gateway
        private val confirmedProtocols = mutableMapOf<Int, Protocol>()
        private val identities = mutableMapOf<Int, VagEcuIdentification.Identity?>()
        private val identityAttempts = mutableSetOf<Int>()
        private val udsInfoOverTp = mutableSetOf<Int>()
        private val detailsAttempts = mutableSetOf<Int>()
        private val details = mutableMapOf<Int, Map<VagUdsInfo.Field, VagUdsInfo.Value>>()
        private val absentTp = mutableSetOf<Int>()
        private val absentUds = mutableSetOf<Int>()
        private val connectionIssues = mutableMapOf<Int, String>()
        private val saeAttempts = mutableSetOf<Int>()
        private val saeSupport = mutableMapOf<Int, Boolean?>()
        private val optionalRetries = mutableMapOf<Int, Int>()
        private val gracefulRetries = mutableMapOf<Int, Int>()
        private var centralPart: String? = null
        private var candidateFallbackPermitted = allowCandidateFallback
        private var discoveryReconnectNeeded = false
        private var diagnosticAccess: VagDiagnosticAccess.Status? = null
        private var currentEngine = engine
        // Protocol drivers may close the damaged engine before recovery is
        // called. Retain only this recognition fact, never its capability object.
        private var nativeFamilyRecognized = false
        private var nativeSegmentationRejected = false
        private var channelRecoveries = 0
        private var listConfirmed = false
        private var candidateRoutes: Map<Int, VagOemReadOnly.Route> = emptyMap()
        private var ids: List<Int> = emptyList()
        private var failed = false
        private var gatewayUnit: ControlUnit? = null
        private val queue = mutableListOf<Int>()
        private var next = 0
        private val retriedAfterClose = mutableSetOf<Int>()
        private val retriedPrimaryDtcNoData = mutableSetOf<Int>()

        private suspend fun restoreConnection(key: String, cause: Throwable) {
            // Each discovery stage is finite and each ECU has bounded retries.
            // Do not turn failures of four different ECUs into a global limit
            // that prevents the remaining verified installation scope being read.
            currentCoroutineContext().ensureActive()
            if (cause is ElmNativeFramingMismatch) nativeSegmentationRejected = true
            val reprobeNative = currentEngine.manualUds && nativeFamilyRecognized
            event("VAG_CHANNEL_RECOVERY", "$key: новое подключение перед продолжением очереди", "RECOVERING")
            withContext(NonCancellable) { currentEngine.flushJournal() }
            currentEngine.close()
            currentCoroutineContext().ensureActive()
            // Production supplies a fresh link as well as an engine. A closed
            // BLE notification channel cannot be reopened in the same object.
            currentEngine = recreateEngine?.invoke() ?: currentEngine
            var restored = false
            try {
                try { currentEngine.connect() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { throw IOException("Не удалось подтвердить новое соединение после ошибки $key", failure) }
                if (!currentEngine.vehicleReady) throw IOException("OBD после закрытия TP-канала не подтверждён")
                // close() discards native configuration. Identify the replacement
                // itself before any route is retried; never reuse the old
                // transport's capability object or silently lose its RX window.
                // An unidentified generic ELM must not be sent the same failed
                // vendor probe again after recovering from that probe itself.
                nativeFamilyRecognized = reprobeNative && currentEngine.manualUds &&
                    ElmAdapterNativeProbe.inspect(currentEngine)
                if (nativeSegmentationRejected) currentEngine.nativeSegmentation?.let { mode ->
                    mode.rejectSegmentation()
                    event("VAG_ADAPTER_FORMAT_FALLBACK", "Native-сборка не подтверждена; обычный ISO-TP, настройки ожидания проверяются отдельно", "GENERIC")
                }
                currentCoroutineContext().ensureActive()
                channelRecoveries++
                event("VAG_CHANNEL_RECOVERY", "$key: OBD подтверждён; очередь продолжается; восстановлений=$channelRecoveries", "CONFIRMED")
                restored = true
            } finally {
                // Ownership transfers only after the complete recovery succeeds.
                // Failed vehicle proof, open failure or cancellation must not
                // depend on a UI caller eventually disposing this fresh link.
                if (!restored) runCatching { currentEngine.close() }
            }
        }
        private suspend fun restoreClosedChannel(key: String, cause: ElmTp20Channel.PeerClosedChannel) = restoreConnection(key, cause)
        private suspend fun finishFailedReadStage(key: String, cause: IOException) {
            // Only callers with an exhausted, classified diagnostic failure use
            // this boundary. Socket loss and failed fresh-link proof still abort.
            if (recreateEngine == null) throw cause
            event("VAG_READ_STAGE_INCOMPLETE", "$key: ${cause.message}", "INCOMPLETE")
            restoreConnection(key, cause)
        }
        private suspend fun finishFailedDiscovery(key: String, cause: IOException) {
            issues += "$key: ${cause.message}"
            finishFailedReadStage(key, cause)
        }
        private fun recovery(key: String): (suspend (Throwable) -> ElmEngine)? =
            if (recreateEngine == null) null else { failure -> restoreConnection(key, failure); currentEngine }
        private fun newUds() = Uds(currentEngine, if (recreateEngine == null) null else
            { key, failure -> restoreConnection(key, failure); currentEngine }, minimalRead = !readSupplementalInfo)
        private suspend fun withTp(route: VagOemReadOnly.Route, action: suspend (ElmTp20Channel) -> Unit) {
            val channel = ElmTp20Channel(currentEngine, recoverEngine = recovery(route.key))
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
        private suspend fun tpIdentity(id: Int, channel: ElmTp20Channel): VagEcuIdentification.Identity? {
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
        private suspend fun collectFunctionComponent(id: Int, read: suspend (String) -> List<Int>?) {
            // One missing discriminator for a documented function relationship,
            // after the host's DTC was saved; never a full ECU inventory pass.
            val identity = identities[id] ?: return
            val field = VagUdsInfo.Field.COMPONENT
            val child = if (id == 0x09) 0x46 else if (id == 0x03 || id == 0x44) 0x04 else return
            val needed = if (id == 0x09) VagDiagnosticTopology.needsComfortComponent(identity)
                else VagDiagnosticTopology.needsAngleComponent(id, identity)
            if (!identifyEcus || readSupplementalInfo ||
                installed?.any { it.id == child && it.installationConfirmed } != true ||
                !needed || details[id]?.containsKey(field) == true) return
            val value = try { VagUdsInfo.decode(field, read(field.request)) }
                catch (bad: IllegalArgumentException) {
                    throw FunctionComponentUnavailable(VagProtocolFailure("Неверный тип компонента для связи функций", bad), id)
                } catch (io: IOException) { throw FunctionComponentUnavailable(io, id) }
            details[id] = details[id].orEmpty() + (field to value)
            identities[id] = VagUdsInfo.merge(identity, mapOf(field to value))
            event("VAG_FUNCTION_COMPONENT_%02X".format(id), value.state.name,
                if (value.state == VagUdsInfo.State.PRESENT) "PARSED" else "INCONCLUSIVE")
        }
        private suspend fun collectAngleIdentity(id: Int, read: suspend () -> VagEcuIdentification.Identity?) {
            if (!identifyEcus || readSupplementalInfo || id !in setOf(0x03, 0x44) ||
                installed?.any { it.id == 0x04 && it.installationConfirmed } != true || id in identityAttempts) return
            val value = try { read() }
                catch (bad: IllegalArgumentException) { throw FunctionComponentUnavailable(VagProtocolFailure("Неверная идентификация связи G85", bad), id) }
                catch (io: IOException) { throw FunctionComponentUnavailable(io, id) }
            identityAttempts += id
            identities[id] = value
            event("VAG_FUNCTION_IDENTITY_%02X".format(id), if (value == null) "Не подтверждено" else
                "${value.partNumber}; ${value.component.orEmpty()}", if (value == null) "INCONCLUSIVE" else "PARSED")
        }
        private suspend fun collectDetails(id: Int, fields: List<VagUdsInfo.Field>, read: suspend (String) -> List<Int>?) {
            if (!detailsAttempts.add(id)) return
            val result = try { VagUdsInfo.collectValues(fields, onValue = { field, value ->
                // A later timeout/cancellation must not discard earlier complete fields.
                val saved = details[id].orEmpty() + (field to value)
                details[id] = saved
                identities[id]?.let { identities[id] = VagUdsInfo.merge(it, saved) }
                if (id == 0x19) identity = identities[id]
                event("VAG_ECU_INFO_%02X_%s".format(id, field.name), value.state.name,
                    if (value.state == VagUdsInfo.State.PRESENT) "PARSED" else "INCOMPLETE")
            }, read = { field ->
                try { VagUdsInfo.decode(field, read(field.request)) }
                catch (_: ElmUdsRawTransaction.UnsupportedCommand) {
                    VagUdsInfo.Value(VagUdsInfo.State.ADAPTER_REJECTED)
                }
            }) }
                catch (bad: IllegalArgumentException) { throw VagProtocolFailure("Неверная идентификация ECU: ${bad.message}", bad) }
            event("VAG_ECU_INFO_%02X".format(id), result.entries.joinToString { "${it.key.name}: ${it.value.state}" },
                if (result.values.all { it.state == VagUdsInfo.State.PRESENT }) "PARSED" else "INCOMPLETE")
        }
        private suspend fun collectIdentity(force: Boolean = false) {
            if ((!readGatewayIdentity && !force) || gatewayIdentityAttempted || protocol == null) return
            gatewayIdentityAttempted = true
            event("VAG_GATEWAY_IDENTITY_BEGIN", "Чтение номера Gateway", "READING")
            try {
                if (protocol == Protocol.TP20) {
                    withTp(gateway) { channel -> identity = tpIdentity(0x19, channel) }
                } else {
                    val channel = newUds()
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
            catch (failure: VagProtocolFailure) { finishFailedReadStage("Идентификация Gateway", failure) }
            catch (failure: ElmCompletedCanError) { finishFailedReadStage("Идентификация Gateway", failure) }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) {
                event("VAG_GATEWAY_IDENTITY", "Идентификация не подтверждена: ${failure.message}", "INCONCLUSIVE")
            }
        }
        private suspend fun discoverAdditional() {
            // This is a fixed legacy procedure, not a sweep of the route catalog.
            // Determine the chassis from ECU09's own identity, never from VIN or
            // the Gateway part. An unconfirmed central ECU cannot select it.
            if (!discoverAdditionalUnits || !listConfirmed || 0x61 in queue) return
            if (!identifyEcus) identifyCentral(force = true)
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
                    val channel = newUds()
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
            catch (failure: VagProtocolFailure) { finishFailedReadStage("Идентификация ${central.key}", failure) }
            catch (failure: ElmCompletedCanError) { finishFailedReadStage("Идентификация ${central.key}", failure) }
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
                    // The discovery procedure uses the same identity cache as
                    // the later full scan. Do not repeat a completed read merely
                    // because this ECU was added outside the Gateway list.
                    tpIdentity(0x61, channel)
                    answered = true
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (closed: ElmTp20Channel.PeerClosedChannel) { restoreClosedChannel(extra.key, closed) }
            catch (failure: VagProtocolFailure) { finishFailedReadStage("Дополнительный опрос ${extra.key}", failure) }
            catch (failure: ElmCompletedCanError) { finishFailedReadStage("Дополнительный опрос ${extra.key}", failure) }
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

        private suspend fun identifyCentral(force: Boolean = false) {
            if (identifyEcus || force) {
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
                    catch (failure: VagProtocolFailure) {
                        connectionIssues[0x09] = failure.message.orEmpty()
                        finishFailedReadStage("Предварительный опрос ${central.key}", failure)
                    }
                    catch (failure: ElmCompletedCanError) {
                        connectionIssues[0x09] = failure.message.orEmpty()
                        finishFailedReadStage("Предварительный опрос ${central.key}", failure)
                    }
                    catch (io: IOException) { throw io }
                    catch (unavailable: ElmTp20Channel.ConnectionUnavailable) {
                        absentTp += 0x09; tryUds = true
                        connectionIssues[0x09] = unavailable.message.orEmpty()
                        event("VAG_ECU_CONNECTION_09", unavailable.message.orEmpty(), "INCOMPLETE")
                    }
                    catch (_: ElmTp20Channel.NoApplicationResponse) {
                        if (0x09 !in confirmedProtocols) { absentTp += 0x09; tryUds = true }
                    }
                    catch (_: IllegalStateException) { }
                    if (tryUds && central.udsTx != null) {
                        val channel = newUds()
                        var healthy = true
                        try {
                            channel.open(central)
                            confirmedProtocols[0x09] = Protocol.UDS
                            identityAttempts += 0x09
                            val value = channel.identifyPart()
                            identities[0x09] = value
                            centralPart = value?.partNumber
                        } catch (cancel: CancellationException) { healthy = false; throw cancel }
                        catch (failure: VagProtocolFailure) {
                            healthy = false
                            connectionIssues[0x09] = failure.message.orEmpty()
                            finishFailedReadStage("Предварительный UDS опрос ${central.key}", failure)
                        }
                        catch (failure: ElmCompletedCanError) {
                            healthy = false
                            connectionIssues[0x09] = failure.message.orEmpty()
                            finishFailedReadStage("Предварительный UDS опрос ${central.key}", failure)
                        }
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
        }

        private suspend fun scanUnit(id: Int) {
            val listEntry = installed?.firstOrNull { it.id == id }
            val route = listEntry?.mappedRoute ?: if (listConfirmed) VagOemReadOnly.routes[id] else candidateRoutes[id]
            val responseConfirmed = id in confirmedProtocols
            val declared = listConfirmed && id in ids && listEntry?.installationConfirmed != false
            val discoveryHint = listEntry?.takeIf { !it.installationConfirmed }?.discoverySources
                ?.joinToString { it.description }
            var unit = ControlUnit("VAG-%02X".format(id), route?.name ?: "Блок VAG",
                ReadState.NOT_QUERIED, note = if (declared) "Подтверждён списком ECU " +
                    (listEntry?.listOwners ?: setOf(0x19)).joinToString { "%02X".format(it) } + "; чтение DTC ещё не выполнено"
                else if (responseConfirmed) "Ответ ECU подтверждён; DTC ещё не прочитаны"
                else if (discoveryHint != null) "$discoveryHint; наличие и DTC ещё не подтверждены"
                else "Кандидат профиля; наличие не подтверждено", source = if (responseConfirmed) "RESPONSE" else if (declared)
                    if (listEntry?.listOwners?.any { it != 0x19 } == true) "INSTALLATION_LIST" else "GATEWAY" else "CANDIDATE")
            onUnit(unit)
            event("VAG_ECU_QUERY_BEGIN", unit.name, "READING")
            if (route == null) {
                failed = true
                onUnit(unit.copy(state = ReadState.NOT_QUERIED, note = "Gateway сообщил блок, но проверенного маршрута в профиле нет"))
                return
            }
            if (listEntry?.routeConflict == true) {
                failed = true
                onUnit(unit.copy(note = "Gateway сообщил конфликтующие маршруты; опрос пропущен"))
                event("VAG_ROUTE_CONFLICT", route.key, "UNSUPPORTED")
                return
            }
            val advertisedTp = listEntry?.tpDestination
            if (advertisedTp != null && route.tp != null && advertisedTp != route.tp) {
                failed = true
                val note = "Адрес из списка Gateway не совпал с известным маршрутом; опрос пропущен"
                onUnit(unit.copy(note = note))
                event("VAG_ROUTE_MISMATCH", "${route.key}: list=%02X; profile=%02X".format(advertisedTp, route.tp), "UNSUPPORTED")
                return
            }
            var completedFaults: VagOemReadOnly.Faults? = null
            // A failed optional preflight must not consume this ECU's DTC turn.
            // Keep the marker outside the transport action: IOException must
            // still reach withTp/UDS cleanup as a damaged stream, not be wrapped.
            var optionalBeforeDtc = false
            var primaryTpDtcInProgress = false
            fun canResumeDtc() = optionalBeforeDtc && completedFaults == null &&
                optionalRetries.getOrDefault(id, 0) < 3
            suspend fun resumeDtc() {
                optionalRetries[id] = optionalRetries.getOrDefault(id, 0) + 1
                queue.add(next, id)
                event("VAG_OPTIONAL_READ_RECOVERY", "${route.key}: новый канал; уже проверенные необязательные поля пропускаются, DTC ещё не прочитаны", "RETRYING")
            }
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
                                throw VagProtocolFailure("Некорректный ответ дополнительных записей DTC: ${bad.message}", bad)
                            }
                        event("VAG_DTC_DETAILS_%02X".format(id), "${fault.code}: ${reply.state}" + (reply.kwpRecords?.let { "; записей=${it.records.size}; структура=${if (it.complete) "полная" else "частичная"}" } ?: "") + (reply.udsRecords?.let { "; записей=${it.records.size}; структура=${if (it.complete) "полная" else "частичная"}" } ?: "") + (reply.nrc?.let { "; NRC 0x%02X".format(it) } ?: "") + (reply.issue?.let { "; $it" } ?: "") + "; расшифровка дополнительных записей не подтверждена",
                            if (reply.state == VagDtcDetails.State.RAW_RESPONSE) "RAW_ONLY" else "INCOMPLETE")
                    }
                }
                suspend fun tpReadOnce(): VagOemReadOnly.Faults {
                    var result: VagOemReadOnly.Faults? = null
                    optionalBeforeDtc = false
                    primaryTpDtcInProgress = false
                    withTp(route) { channel ->
                        confirmedConnection(Protocol.TP20)
                        optionalBeforeDtc = true
                        if (identifyEcus && readSupplementalInfo) try {
                            tpIdentity(id, channel)
                        } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                            throw OptionalReadUnavailable(missing)
                        }
                        val powertrain = if (identifyEcus) {
                            try {
                                if (saeAttempts.add(id)) saeSupport[id] = VagKwpCapabilities.supportsSaeDtc(channel.request(VagKwpCapabilities.REQUEST))
                            } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                                throw OptionalReadUnavailable(missing)
                            } catch (_: ElmCompletedCanError) {
                                // This fixed capability command has no retriable
                                // flag; its completed failure selects OEM format.
                                saeSupport[id] = false
                            }
                            saeSupport[id] == true
                        } else id == 1 || id == 2 // Recorded pre-capability test sequences only.
                        optionalBeforeDtc = false
                        primaryTpDtcInProgress = true
                        result = VagOemReadOnly.kwpFaultMessages(
                            channel.requestMessages(if (powertrain) "1800FF00" else "1802FF00"), powertrain).deduplicated()
                        completedFaults = result
                        publishFaults(requireNotNull(result))
                        try { collectAngleIdentity(id) { tpIdentity(id, channel) } }
                        catch (missing: ElmTp20Channel.NoApplicationResponse) {
                            event("VAG_FUNCTION_IDENTITY_%02X".format(id), "Тип не подтверждён; DTC сохранены", "INCONCLUSIVE")
                        }
                        if (id in udsInfoOverTp) try {
                            collectFunctionComponent(id) { channel.request(it) }
                        } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                            event("VAG_FUNCTION_COMPONENT_%02X".format(id), "Тип компонента не подтверждён; основные DTC сохранены", "INCONCLUSIVE")
                        }
                        if (identifyEcus && readSupplementalInfo) try {
                            if (id in udsInfoOverTp) collectDetails(id, VagUdsInfo.scanFields) { channel.request(it) }
                            readDtcDetails(VagDtcDetails.Protocol.KWP, requireNotNull(result)) { channel.requestDetails(it) }
                        } catch (missing: ElmTp20Channel.NoApplicationResponse) {
                            event("VAG_ECU_INFO_%02X".format(id), "Дополнительный ответ не получен; основные DTC сохранены", "INCOMPLETE")
                        }
                    }
                    return requireNotNull(result)
                }
                suspend fun tpRead(): VagOemReadOnly.Faults = try { tpReadOnce() }
                    catch (missing: IllegalStateException) {
                        // A complete NO DATA setup or a healthy unanswered1089
                        // permits one fresh-channel retry on the listed CAN engine.
                        // Never retry a DTC request, pending or damaged transaction.
                        val retryable = missing is ElmTp20Channel.ConnectionUnavailable ||
                            (missing is ElmTp20Channel.NoApplicationResponse && missing.request == "1089")
                        if (!retryable || !identifyEcus || id != 0x01 || listEntry == null ||
                            VagConnectionPolicy.isUdsOnlyChassis(centralPart)) throw missing
                        event("VAG_ENGINE_CONNECTION_RETRY", "01: нет полного ответа подключения; один новый канал через 2000 мс", "RETRYING")
                        delay(2000)
                        currentCoroutineContext().ensureActive()
                        tpReadOnce()
                    }
                suspend fun udsRead(): VagOemReadOnly.Faults {
                    primaryTpDtcInProgress = false
                    val paths = route.udsPaths(listConfirmed)
                    check(paths.isNotEmpty()) { "Нет проверенного UDS-маршрута" }
                    var lastFailure: RuntimeException? = null
                    var onlyNoResponse = true
                    for ((index, path) in paths.withIndex()) {
                        val channel = newUds()
                        var healthy = true
                        optionalBeforeDtc = false
                        try {
                            val responseConfirmed = channel.open(path, allowListedDtcProbe = declared)
                            if (responseConfirmed) confirmedConnection(Protocol.UDS)
                            else event("VAG_LISTED_DTC_PROBE", "${route.key}: служебные ответы не получены; проверка собственной памяти DTC по маршруту из подтверждённого списка", "READING")
                            optionalBeforeDtc = responseConfirmed
                            // Keep the minimal identity before DTC. Optional
                            // descriptions cannot delay publishing the fault memory.
                            if (responseConfirmed && identifyEcus && readSupplementalInfo && id !in identityAttempts) {
                                identityAttempts += id
                                identities[id] = try { channel.identifyPart() }
                                    catch (unsupported: ElmUdsRawTransaction.UnsupportedCommand) {
                                        event("VAG_ECU_IDENTITY_%02X".format(id), unsupported.message.orEmpty(), "ADAPTER_REJECTED")
                                        null
                                    }
                            }
                            optionalBeforeDtc = false
                            val reply = if (responseConfirmed) channel.read(VagUdsReadOnly.DTC_REQUEST)
                                else channel.readListedFaultMemory() ?: throw UdsNoResponse(dtcAttempted = true)
                            // The list authorizes the route, but is not response
                            // evidence. Promote only after this ECU actually replies.
                            if (!responseConfirmed) confirmedConnection(Protocol.UDS)
                            val faults = VagOemReadOnly.udsFaults(reply).deduplicated()
                            completedFaults = faults
                            publishFaults(faults)
                            collectAngleIdentity(id, channel::identifyPart)
                            collectFunctionComponent(id, channel::readOptionalInfo)
                            if (identifyEcus && readSupplementalInfo) collectDetails(id, VagUdsInfo.scanFields + VagUdsInfo.Field.ACCESS_STATUS) {
                                channel.readOptionalInfo(it)
                            }
                            if (identifyEcus && readSupplementalInfo && faults.values.isNotEmpty()) {
                                // A completed refusal is already evidence of an
                                // unavailable field; do not ask for it twice in
                                // the same session just because faults exist.
                                if (VagUdsInfo.Field.ASAM !in details[id].orEmpty()) {
                                    val asam = try { VagUdsInfo.decode(VagUdsInfo.Field.ASAM, channel.readOptionalInfo("22F19E")) }
                                        catch (bad: IllegalArgumentException) { throw VagProtocolFailure("Неверная идентификация ASAM", bad) }
                                    details[id] = details[id].orEmpty() + (VagUdsInfo.Field.ASAM to asam)
                                }
                            }
                            if (identifyEcus && readSupplementalInfo && faults.values.isNotEmpty() && details[id]?.get(VagUdsInfo.Field.ASAM)?.state == VagUdsInfo.State.PRESENT) {
                                val revision = try { VagUdsInfo.decode(VagUdsInfo.Field.ASAM_REVISION, channel.readOptionalInfo("22F1A2")) }
                                    catch (bad: IllegalArgumentException) { throw VagProtocolFailure("Неверная ревизия ASAM", bad) }
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
                        if (failure !is ElmTp20Channel.ConnectionUnavailable && failure !is ElmTp20Channel.NoApplicationResponse) throw failure
                        absentTp += id
                        connectionIssues[id] = failure.message.orEmpty()
                        if (route.udsTx == null) throw failure
                        event("VAG_ECU_PROTOCOL_FALLBACK", "${route.key}: TP2.0 ${failure.message}; known UDS route", "INCONCLUSIVE")
                        udsRead()
                    }
                } else {
                    check(!identifyEcus || VagConnectionPolicy.mustConnectToUds(state, id in absentTp)) {
                        "Нет доступного подтверждаемого протокола ECU" + connectionIssues[id]?.let { "; $it" }.orEmpty()
                    }
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
            catch (can: ElmCompletedCanError) {
                completedFaults?.takeIf { !faultsPublished }?.let { publishFaults(it) }
                val resume = canResumeDtc()
                if (completedFaults == null && !resume) failed = true
                onUnit(unit.copy(state = if (completedFaults != null) ReadState.COMPLETE else
                    if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = if (completedFaults != null) "DTC прочитаны; дополнительные данные не подтверждены: CAN ERROR"
                        else "DTC не подтверждены: CAN ERROR; предел повторов команды исчерпан"))
                event("VAG_DTC_%02X".format(id), "CAN ERROR; остальные ECU после восстановления соединения", "INCOMPLETE")
                restoreConnection(route.key, can)
                if (resume) resumeDtc()
            }
            catch (unsupported: ElmUdsRawTransaction.UnsupportedCommand) {
                completedFaults?.takeIf { !faultsPublished }?.let { publishFaults(it) }
                if (completedFaults == null) failed = true
                onUnit(unit.copy(state = if (completedFaults != null) ReadState.COMPLETE else
                    if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = if (completedFaults != null) "DTC прочитаны; дополнительные данные не подтверждены: ${unsupported.message}"
                        else "DTC не подтверждены: ${unsupported.message}"))
                event("VAG_ADAPTER_COMMAND_REJECTED", "${route.key}: ${unsupported.message}", "INCOMPLETE")
            }
            catch (unavailable: FunctionComponentUnavailable) {
                // Only this post-DTC, optional field can use this boundary.
                // Never repeat the saved memory or resume on an unverified stream.
                if (completedFaults == null) throw unavailable
                if (!faultsPublished) publishFaults(requireNotNull(completedFaults))
                onUnit(unit.copy(note = "Память DTC прочитана; ${unavailable.message}"))
                event("VAG_FUNCTION_COMPONENT_%02X".format(unavailable.id), unavailable.message.orEmpty(), "INCOMPLETE")
                if (recreateEngine == null) throw unavailable
                restoreConnection(route.key, unavailable)
            }
            catch (protocolFailure: VagProtocolFailure) {
                completedFaults?.takeIf { !faultsPublished }?.let { publishFaults(it) }
                onUnit(unit.copy(state = if (completedFaults != null) ReadState.COMPLETE else
                    if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = if (completedFaults != null) "DTC прочитаны; дополнительные данные не подтверждены: ${protocolFailure.message}"
                        else "DTC не подтверждены; предел восстановления команды исчерпан: ${protocolFailure.message}"))
                // Without a fresh-link factory there is no proof that a broken
                // transaction can be replaced. Production always supplies it.
                if (recreateEngine == null) throw VagProtocolFailure(
                    "${route.key}: ${protocolFailure.message}", protocolFailure, protocolFailure.noData)
                val resume = canResumeDtc()
                // A live TP session may ACK a primary DTC request, then fall
                // silent. Consecutive NO_DATA can exhaust the command budget
                // before its inner recovery hook runs. Return this ECU once
                // after the outer fresh-stream proof; never retry mere setup
                // silence, UDS absence, malformed data, IO or cancellation here.
                val primaryResume = primaryTpDtcInProgress && protocolFailure.noData &&
                    completedFaults == null && confirmedProtocols[id] == Protocol.TP20 &&
                    retriedPrimaryDtcNoData.add(id)
                if (completedFaults == null && !resume && !primaryResume) failed = true
                restoreConnection(route.key, protocolFailure)
                if (resume) resumeDtc()
                else if (primaryResume) {
                    queue.add(next, id)
                    event("VAG_PRIMARY_DTC_RECOVERY", "${route.key}: основной DTC не получен; один повтор на новом подтверждённом канале", "RETRYING")
                }
                else event("VAG_ECU_READ_INCOMPLETE", "${route.key}: остальные ECU после подтверждённого нового соединения", "INCOMPLETE")
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
                queue.add(next, id)
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

        suspend fun run(): Outcome {
            if (currentEngine.manualUds) try {
                val nativeCandidate = ElmAdapterNativeProbe.inspect(currentEngine)
                nativeFamilyRecognized = nativeCandidate
                event("VAG_ADAPTER_NATIVE_CANDIDATE", if (nativeCandidate) "Семейство адаптера распознано; команды режима требуют подтверждения"
                    else "Общая ветвь ELM", if (nativeCandidate) "CANDIDATE" else "GENERIC")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IOException) { finishFailedReadStage("Идентификация адаптера", failure) }
            catch (failure: RuntimeException) { finishFailedReadStage("Идентификация адаптера", VagProtocolFailure("Неполный ответ адаптера", failure)) }
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
            catch (failure: VagProtocolFailure) { finishFailedDiscovery("Gateway TP2.0", failure) }
            catch (failure: ElmCompletedCanError) { finishFailedDiscovery("Gateway TP2.0", failure) }
            catch (io: IOException) { throw io }
            catch (failure: ElmTp20Channel.ConnectionUnavailable) { absentTp += 0x19; issues += "Gateway TP2.0: ${failure.message}" }
            catch (failure: IllegalStateException) { issues += "Gateway TP2.0: ${failure.message}" }
            catch (failure: IllegalArgumentException) { issues += "Список TP2.0: ${failure.message}" }

            // A live TP2.0 gateway may expose the UDS-style 04A1 list over that same
            // transport. Do not mistake a service ID for a transport or VAG address.
            if (protocol == Protocol.TP20 && installed == null && legacyListFallbackAllowed) {
                try { withTp(gateway) { installed = VagOemReadOnly.installed04A1Messages(it.requestMessages("2204A1")) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: VagProtocolFailure) { finishFailedDiscovery("Список 04A1/TP2.0", failure) }
                catch (failure: ElmCompletedCanError) { finishFailedDiscovery("Список 04A1/TP2.0", failure) }
                catch (io: IOException) { throw io }
                catch (failure: RuntimeException) { issues += "Список 04A1/TP2.0: ${failure.message}" }
            }
            if (protocol == null) {
                try {
                    val channel = newUds()
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
                                val bitmapReply = channel.readGatewaySource("222A26")
                                if (bitmapReply != null) {
                                    val decoded = VagInstallationBitmap.decode(0x19, part, bitmapReply)
                                    installed = decoded.entries.map { entry ->
                                        val name = VagOemReadOnly.routes[entry.id]?.name ?: "Блок VAG"
                                        VagOemReadOnly.Installed(entry.id, null, false, mappedRoute =
                                            VagOemReadOnly.Route(entry.id, name, entry.tp, entry.udsTx, installedOnly = true))
                                    }.takeIf { it.isNotEmpty() }
                                    event("VAG_INSTALLATION_FORMAT", "2A26; ${decoded.format}; owner=19", "PARSED")
                                    if (decoded.unknownBits.isNotEmpty())
                                        listLimitations += "Неизвестные позиции списка: ${decoded.unknownBits.joinToString()}"
                                }
                            }
                            if (listLimitations.isNotEmpty())
                                event("VAG_INSTALLATION_LIMITATIONS", listLimitations.joinToString("; "), "INCOMPLETE")
                        } else installed = channel.readGatewaySource("2204A1")?.let(VagOemReadOnly::installed04A1)
                        if (installed.isNullOrEmpty() && !discoveryReconnectNeeded) {
                            val extra = ElmGatewayListFallback.read(part, channel::readGatewaySource) { command, result ->
                                event("VAG_INSTALLATION_SOURCE", "$command: " + (result?.entries?.joinToString { entry ->
                                    "%02X".format(entry.id) + " (" + entry.discoverySources.joinToString { it.description } + ")"
                                } ?: "нет поддерживаемого ответа"), if (result != null) "PARSED" else "UNSUPPORTED")
                            }
                            installed = extra.entries.takeIf { it.isNotEmpty() }
                            listLimitations += extra.limitations
                        }
                    } catch (cancel: CancellationException) { healthy = false; throw cancel }
                    catch (io: IOException) { healthy = false; throw io }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: VagProtocolFailure) { finishFailedDiscovery("Gateway UDS", failure) }
                catch (failure: ElmCompletedCanError) { finishFailedDiscovery("Gateway UDS", failure) }
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
            listConfirmed = installed?.any { it.installationConfirmed } == true
            if (!listConfirmed && !candidateFallbackPermitted && allowGatewayIdentityFallback) {
                // A validated, whitelisted Gateway part is direct platform evidence;
                // the fixed catalog must not depend solely on optional VIN service09.
                collectIdentity(force = true)
                if (identity?.partNumber?.let { VagInstallationBitmap.supports(0x19, it) } == true) {
                    candidateFallbackPermitted = true
                    event("VAG_DISCOVERY_SCOPE", "Подтверждена поддерживаемая идентификация Gateway; разрешён фиксированный каталог без VIN", "CONFIRMED")
                }
            }
            // Preserve an independently permitted platform catalog. When only
            // hints authorize discovery, limit that scope to the reported routes.
            val hintsOnlyScope = !listConfirmed && !candidateFallbackPermitted && !installed.isNullOrEmpty()
            if (hintsOnlyScope) candidateFallbackPermitted = true
            if (!listConfirmed && !candidateFallbackPermitted) {
                collectIdentity()
                event("VAG_INSTALLATION_LIST", "Формат списка не подтверждён; кандидаты для этой платформы не опрашиваются", "UNSUPPORTED")
                return Outcome(protocol, false, false, "Gateway ответил; список для этой платформы не подтверждён" +
                    (if (issues.isNotEmpty()) "; " + issues.joinToString("; ") else "") +
                    (if (listLimitations.isNotEmpty()) "; " + listLimitations.joinToString("; ") else "") +
                    (identity?.let { "; " + it.summary() } ?: ""))
            }
            // Filter status alone does not prove that read services are blocked.
            // Keep the verified manufacturer's fixed discovery scope unchanged.
            val hintedRoutes = installed.orEmpty().mapNotNull { it.mappedRoute ?: VagOemReadOnly.routes[it.id] }.associateBy { it.id }
            candidateRoutes = if (listConfirmed) emptyMap() else if (hintsOnlyScope)
                (listOf(gateway) + installed!!.mapNotNull { it.mappedRoute ?: VagOemReadOnly.routes[it.id] }).associateBy { it.id }
                else VagOemCandidateCatalog.routes + hintedRoutes
            event("VAG_INSTALLATION_LIST", if (listConfirmed) "Подтверждены ID: " + installed!!.filter { it.installationConfirmed }.joinToString { "%02X".format(it.id) }
                else "Список не получен; фиксированный каталог ${candidateRoutes.size} кандидатов, наличие требует ответа ECU", if (listConfirmed) "CONFIRMED" else "INCONCLUSIVE")
            ids = if (listConfirmed) (listOf(0x19) + installed!!.map { it.id }).distinct()
                else candidateRoutes.keys.toList()
            failed = !listConfirmed || listLimitations.isNotEmpty()
            queue.addAll(ids)
            // Preserve the whole confirmed scope before any ECU read can fail.
            // Candidate catalog entries still require an actual ECU response.
            if (listConfirmed) for (id in ids) {
                val entry = installed?.firstOrNull { it.id == id }
                val route = entry?.mappedRoute ?: VagOemReadOnly.routes[id]
                onUnit(ControlUnit("VAG-%02X".format(id), route?.name ?: "Блок VAG", ReadState.NOT_QUERIED,
                    note = if (entry?.installationConfirmed == false)
                        entry.discoverySources.joinToString { it.description } + "; наличие и DTC не подтверждены"
                        else "Наличие подтверждено; чтение DTC ещё не выполнено",
                    source = if (id in confirmedProtocols) "RESPONSE" else
                        if (entry?.installationConfirmed == false) "CANDIDATE" else
                        if (entry?.listOwners?.any { it != 0x19 } == true) "INSTALLATION_LIST" else "GATEWAY"))
            }
            // Complete the bounded installation-list procedure before entering
            // the per-ECU info/DTC loop. Additional ECU discovery is not a second
            // pass after the results; each listed ECU is then read in one turn.
            identifyCentral()
            discoverAdditional()
            while (next < queue.size) {
                currentCoroutineContext().ensureActive()
                val id = queue[next++]
                currentCoroutineContext().ensureActive()
                scanUnit(id)
            }
            // Annotate relationships only; each ECU retains its own DTC result.
            for (unit in observedUnits.values.toList()) {
                val host = VagDiagnosticTopology.host(unit, observedUnits, identities) ?: continue
                onUnit(unit.copy(diagnosticHost = host.address, hostReason = host.reason))
                event("VAG_FUNCTION_HOST_" + unit.address.removePrefix("VAG-"), host.reason + " Источник: " + host.evidence, "RELATED")
            }
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
    }

    private class Uds(private var engine: ElmEngine, private val recoverEngine: (suspend (String, Throwable) -> ElmEngine)? = null,
        private val minimalRead: Boolean = false) {
        private suspend fun execute(command: String, timeoutMs: Long = 5500): String =
            engine.command(command, timeoutMs).also { ElmCompletedCanError.check(it, command) }
        private var route: VagOemReadOnly.Route? = null
        private var active = false
        private var pendingMs = 5250L
        private var cachedPartReply: List<Int>? = null
        private var opening = false
        private var listedDtcProbeAllowed = false
        private suspend fun configure(target: VagOemReadOnly.Route) {
            if (engine.manualUds) {
                ElmUdsCanConfiguration.configure(engine, target)
                route = target
                return
            }
            val tx = requireNotNull(target.udsTx) { "Нет проверенного UDS-маршрута" }
            val rx = requireNotNull(target.udsRx)
            suspend fun ok(cmd: String) {
                engine.configureSetting(cmd)
            }
            val protocol = if (target.is29Bit) "7" else "6"
            // Keep automatic FC disabled until the new ID/filter/mode is ready.
            // Use the same connection-scoped acknowledgements as RAW CAN.
            // A protocol/reset/refusal invalidates them; vehicle requests are
            // never cached. Disable FC before changing framing or identifiers.
            for (cmd in listOf("ATSP$protocol", "ATCFC0", "ATH1", "ATD0", "ATCAF1", "ATV0", "ATCEA", "ATSTFA", "ATAT0")) ok(cmd)
            // Per-request timing below applies equally to CAF1, RAW and native
            // paths. A DTC request must not inherit an identification setting.
            if (target.is29Bit) {
                engine.needsFlowControlReset = true
                engine.configureCanHeader29(tx)
                ok("ATCRA%08X".format(rx))
                // The non-OBD 29-bit route needs its exact FC transmit ID.
                ok("ATFCSH%08X".format(tx)); ok("ATFCSD300010"); ok("ATFCSM1")
                if (execute("ATDPN").trim().uppercase() !in setOf("7", "A7")) throw IOException("ELM не подтвердил CAN 7")
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
        suspend fun open(target: VagOemReadOnly.Route, allowListedDtcProbe: Boolean = false): Boolean {
            listedDtcProbeAllowed = allowListedDtcProbe
            repeat(4) { attempt ->
                opening = true
                try { return openOnce(target, allowListedDtcProbe) }
                catch (failure: VagProtocolFailure) {
                    if (recoverEngine == null || attempt == 3) throw failure
                    engine = recoverEngine.invoke(target.key, failure)
                    if (!engine.vehicleReady) throw IOException("Связь после восстановления UDS не подтверждена")
                } finally { opening = false }
            }
            error("Исчерпан предел открытия UDS")
        }
        private suspend fun openOnce(target: VagOemReadOnly.Route, allowListedDtcProbe: Boolean): Boolean {
            active = false
            pendingMs = 5250L
            cachedPartReply = null
            configure(target)
            // Presence is independent of extended-session support. A complete
            // matching negative reply also proves that the addressed ECU answered.
            var presence: List<Int>? = null
            for (attempt in 0 until (if (allowListedDtcProbe && minimalRead) 1 else 3)) {
                presence = readOrNoData("3E00", allowNoData = true)
                if (presence != null) break
                currentCoroutineContext().ensureActive()
            }
            if (!(presence == null || presence == listOf(0x7E, 0) ||
                (presence.size == 3 && presence.take(2) == listOf(0x7F, 0x3E)))) throw VagProtocolFailure("Нет подтверждения присутствия UDS ECU")
            // Establishment's best-effort poke is not an ordinary retriable
            // StartDiagnosticSession command. Keep the presence window/state
            // and send it once; only later validated reads can confirm DTCs.
            val reply = try { readOrNoDataOnce("1003", allowNoData = true, sessionProbe = true) }
                catch (_: ElmCompletedCanError) { null }
                catch (_: ElmUdsRawTransaction.UnsupportedCommand) { null }
            active = reply?.take(2) == listOf(0x50, 3)
            if (presence == null && reply == null) {
                // A validated installation list already authorizes this route.
                // Do not spend another silent identity read before its own DTC.
                if (allowListedDtcProbe && minimalRead) return false
                cachedPartReply = readOrNoData("22F187", allowNoData = true)
                if (cachedPartReply == null) {
                    // Only complete silence on finished transactions reaches
                    // here. Pending, damaged framing, cancellation and physical
                    // IO still propagate; they never authorize another command.
                    if (allowListedDtcProbe) return false
                    throw UdsNoResponse()
                }
            }
            // The researched session operation returns an empty success model
            // after the matching positive envelope. Optional timing bytes do
            // not gate the following read; retain the bounded reader policy.
            return true
        }
        suspend fun close() {
            if (!active) return
            active = false
            val reply = read("1001")
            if (reply.take(2) != listOf(0x50, 1)) throw IOException("ECU не подтвердил возврат в штатную UDS-сессию")
            active = false
        }
        suspend fun read(command: String): List<Int> =
            (if (command == "22F187") cachedPartReply else null) ?: requireNotNull(readOrNoData(command, false))
        suspend fun readListedFaultMemory(): List<Int>? = readOrNoData(VagUdsReadOnly.DTC_REQUEST, true)
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
        suspend fun readGatewaySource(command: String): List<Int>? {
            require(route?.id == 0x19 && command in setOf("2204A1", "222A26", "222A2A", "22F1B7", "2204A3", "222A28"))
            val reply = readOrNoData(command, true) ?: return null
            if (reply.take(2) == listOf(0x7F, 0x22)) {
                if (reply.size == 3 && reply[2] in setOf(0x11, 0x12, 0x31)) return null
                VagOemReadOnly.positive(reply, 0x22)
            }
            return reply
        }

        private suspend fun readOrNoData(command: String, allowNoData: Boolean): List<Int>? {
            val result = VagReadRetry.run(command, classify = { attempt: Result<List<Int>?> ->
                val failure = attempt.exceptionOrNull()
                when (failure) {
                    is VagProtocolFailure -> if (failure.noData) VagReadRetry.Outcome.NO_DATA else VagReadRetry.Outcome.PROTOCOL_ERROR
                    null -> VagReadRetry.response(command, attempt.getOrThrow())
                    else -> VagReadRetry.Outcome.CAN_ERROR
                }
            }, beforeRetry = { attempt ->
                (attempt.exceptionOrNull() as? VagProtocolFailure)?.let { failure ->
                    val target = requireNotNull(route)
                    engine = requireNotNull(recoverEngine).invoke(target.key, failure)
                    if (!engine.vehicleReady) throw IOException("Связь после восстановления UDS не подтверждена")
                    // Recovery must preserve this route's confirmed-list DTC
                    // permission. Otherwise a cleanly silent preflight can
                    // consume the retry before the own-memory request is sent.
                    // Optional reads never acquire the extra permission here.
                    open(target, allowListedDtcProbe = command == VagUdsReadOnly.DTC_REQUEST && listedDtcProbeAllowed)
                }
            }) {
                try { Result.success(readOrNoDataOnce(command, allowNoData = true)) }
                catch (can: ElmCompletedCanError) { Result.failure(can) }
                catch (failure: VagProtocolFailure) {
                    if (recoverEngine == null || opening) throw failure
                    Result.failure(failure)
                }
            }.getOrThrow()
            if (result == null && !allowNoData) error("ELM завершил UDS $command без ответа")
            return result
        }
        private suspend fun readOrNoDataOnce(command: String, allowNoData: Boolean, sessionProbe: Boolean = false): List<Int>? {
            require(ElmUdsReadRequests.allowed(command))
            require(!sessionProbe || command == "1003")
            val target = requireNotNull(route)
            if (!sessionProbe) {
                engine.configureAdaptiveTiming(ElmUdsReadRequests.allowsAdaptiveTiming(command))
                ElmUdsRequestTiming.configure(engine, command)
            }
            if (engine.nativeSegmentation?.enabled == true)
                return ElmUdsNativeDriver.read(engine, target, command, pendingMs, sessionProbe = sessionProbe)
            if (engine.manualUds && target.id !in engine.automaticCanRoutes) {
                try { return ElmUdsRawTransaction.read(engine, target, command, pendingMs,
                    automaticFlowControl = true, sessionProbe = sessionProbe) }
                catch (unsupported: ElmUdsRawTransaction.UnsupportedCommand) {
                    // A complete '?' is an adapter capability refusal, not an
                    // unfinished ECU reply. Configure the already-supported CAF1
                    // path once for this route on this physical connection.
                    engine.automaticCanRoutes += target.id
                    configure(target)
                    return readOrNoDataOnce(command, allowNoData, sessionProbe)
                }
            }
            val result = withTimeoutOrNull(20000) {
                    val rx = requireNotNull(target.udsRx)
                    // Automatic framing still transfers the full CAN response.
                    // Do not expire before a legal STmin-paced CF train; the
                    // enclosing deadline continues to bound the whole read.
                    val wire = if (sessionProbe || command == "3E00") command + "1" else command
                    var raw = execute(wire, 20000)
                    if (wire != command) raw = raw.replace('\r', '\n').split('\n')
                        .filter { it.filterNot(Char::isWhitespace).uppercase() != wire }.joinToString("\r")
                    if (raw.trim() == "?") throw ElmUdsRawTransaction.UnsupportedCommand(command)
                    var pending = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (raw.trim().uppercase() == "NO DATA" && pending == 0) {
                            if (allowNoData) return@withTimeoutOrNull emptyList<Int>()
                            error("ELM завершил UDS $command без ответа")
                        }
                        val decoded = try { VagUdsTransaction.decode(raw, rx, command) }
                            catch (bad: IllegalArgumentException) { throw VagProtocolFailure(bad.message ?: "Повреждённый UDS ответ", bad) }
                        pending += decoded.pendingCount
                        if (pending > 8) throw VagProtocolFailure("Слишком много UDS ResponsePending")
                        decoded.payload?.let { return@withTimeoutOrNull it }
                        // No new diagnostic request, session-close or tester-present
                        // may run until this SID has a final response.
                        return@withTimeoutOrNull ElmUdsPending.read(engine, target, command, raw, pendingMs, pending)
                    }
                    @Suppress("UNREACHABLE_CODE") emptyList<Int>()
            }
            if (result == null) {
                engine.close()
                throw VagProtocolFailure("Превышен общий срок ожидания UDS $command")
            }
            return result.ifEmpty { null }
        }
    }
}
