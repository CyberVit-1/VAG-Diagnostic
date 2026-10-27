package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.io.IOException

/** Legacy CAN connection, UDS equivalent and gateway-list driven read path.
 * Runs only after the existing profile/adapter gates, on a disposable ElmEngine.
 */
object ElmVagScan {
    enum class Protocol { TP20, UDS }
    data class Outcome(val protocol: Protocol?, val listConfirmed: Boolean, val complete: Boolean, val note: String)

    suspend fun scan(engine: ElmEngine,
        onUnit: suspend (ControlUnit) -> Unit,
        event: suspend (String, String, String) -> Unit
    ): Outcome {
        var protocol: Protocol? = null
        var installed: List<VagOemReadOnly.Installed>? = null
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
                val reply = channel.request("1A9F")
                installed = VagOemReadOnly.installedKwp(reply)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (io: IOException) { throw io }
        catch (failure: IllegalStateException) { issues += "Gateway TP2.0: ${failure.message}" }
        catch (failure: IllegalArgumentException) { issues += "Список TP2.0: ${failure.message}" }

        // A live TP2.0 gateway may expose the UDS-style 04A1 list over that same
        // transport. Do not mistake a service ID for a transport or VAG address.
        if (protocol == Protocol.TP20 && installed == null) {
            try { withTp(gateway) { installed = VagOemReadOnly.installed04A1(it.request("2204A1")) } }
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
                    event("VAG_GATEWAY_CONNECTED", "UDS 710/77A; VAG=19; session confirmed", "CONFIRMED")
                    // Only the recovered legacy 04A1 format is enabled in this 1K
                    // profile; part-number-dependent MQB bitmaps are not guessed.
                    installed = VagOemReadOnly.installed04A1(channel.read("2204A1"))
                } catch (io: IOException) { healthy = false; throw io }
                finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) { throw io }
            catch (failure: RuntimeException) { issues += "Gateway UDS: ${failure.message}" }
        }
        if (protocol == null) {
            event("VAG_GATEWAY_DISCOVERY", issues.joinToString("; "), "INCONCLUSIVE")
            return Outcome(null, false, false, "Gateway не подтвердил TP2.0 или UDS; ${issues.joinToString("; ")}")
        }
        val listConfirmed = installed != null
        event("VAG_INSTALLATION_LIST", if (listConfirmed) "Подтверждены ID: " + installed!!.joinToString { "%02X".format(it.id) }
            else "Список не получен; ограниченная очередь известных адресов профиля, не список установленных блоков", if (listConfirmed) "CONFIRMED" else "INCONCLUSIVE")
        val ids = if (listConfirmed) (listOf(0x19) + installed!!.map { it.id }).distinct() else VagOemReadOnly.routes.keys.toList()
        var failed = !listConfirmed
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
            try {
                suspend fun confirmedConnection() {
                    unit = unit.copy(source = "RESPONSE", note = "Сессия ECU подтверждена; чтение DTC")
                    onUnit(unit)
                }
                suspend fun tpRead(): VagOemReadOnly.Faults {
                    var result: VagOemReadOnly.Faults? = null
                    withTp(route) { channel ->
                        confirmedConnection()
                        val powertrain = id == 1 || id == 2
                        result = VagOemReadOnly.kwpFaults(channel.request(if (powertrain) "1800FF00" else "1802FF00"), powertrain)
                    }
                    return requireNotNull(result)
                }
                suspend fun udsRead(): VagOemReadOnly.Faults {
                    val channel = Uds(engine)
                    var healthy = true
                    try {
                        channel.open(route)
                        confirmedConnection()
                        return VagOemReadOnly.udsFaults(channel.read("19028D"))
                    } catch (io: IOException) { healthy = false; throw io }
                    finally { if (healthy && currentCoroutineContext().isActive) channel.close() }
                }
                val result = if (protocol == Protocol.TP20 && route.tp != null) {
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
                onUnit(unit)
                event("VAG_DTC_%02X".format(id), "${result.service}; DTC=${result.values.size}", "PARSED")
            } catch (cancel: CancellationException) { throw cancel }
            catch (io: IOException) {
                onUnit(unit.copy(state = if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = "Соединение потеряно; DTC не подтверждены: ${io.message}"))
                throw io
            }
            catch (failure: RuntimeException) {
                failed = true
                unit = unit.copy(state = if (unit.source == "RESPONSE") ReadState.PARTIAL else ReadState.NO_RESPONSE,
                    note = "DTC не подтверждены: ${failure.message}")
                onUnit(unit)
                event("VAG_DTC_%02X".format(id), unit.note, "INCOMPLETE")
            }
        }
        return Outcome(protocol, listConfirmed, !failed, "VAG ${protocol.name}; " +
            if (listConfirmed) "список Gateway получен; опрос ${if (failed) "неполный" else "завершён"}"
            else "список Gateway недоступен; опрошены только кандидаты профиля")
    }

    private class Uds(private val engine: ElmEngine) {
        private var route: VagOemReadOnly.Route? = null
        private var active = false
        suspend fun open(target: VagOemReadOnly.Route) {
            val tx = requireNotNull(target.udsTx) { "Нет проверенного UDS-маршрута" }
            val rx = requireNotNull(target.udsRx)
            for (cmd in listOf("ATSP6", "ATH1", "ATD0", "ATCAF1", "ATCFC1", "ATV0", "ATCEA", "ATAT1", "ATST96",
                "ATSH%03X".format(tx), "ATCRA%03X".format(rx))) {
                val raw = engine.command(cmd).uppercase().replace(cmd, "").filterNot { it.isWhitespace() }
                check(raw == "OK") { "ELM отклонил $cmd" }
            }
            route = target
            val reply = read("1003")
            check(reply.size >= 2 && reply.take(2) == listOf(0x50, 3)) { "Нет подтверждения UDS-сессии 1003" }
            active = true
        }
        suspend fun close() {
            if (!active) return
            active = false
            val reply = read("1001")
            if (reply.take(2) != listOf(0x50, 1)) throw IOException("ECU не подтвердил возврат в штатную UDS-сессию")
        }
        suspend fun read(command: String): List<Int> {
            require(command in setOf("1003", "1001", "2204A1", "19028D"))
            val target = requireNotNull(route)
            val raw = engine.command(command, 8500)
            val decoded = VagAddressedCan.decodeSingleEcu(raw, requireNotNull(target.udsRx))
            check(decoded.valid) { "Неполный адресный UDS: ${decoded.problems.joinToString()}" }
            return requireNotNull(decoded.payload).map { it.toInt() and 255 }
        }
    }
}
