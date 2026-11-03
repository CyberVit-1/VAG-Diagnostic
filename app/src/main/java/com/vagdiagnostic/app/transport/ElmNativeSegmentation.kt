package com.vagdiagnostic.app.transport

import com.vagdiagnostic.app.core.VagOemReadOnly
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Adapter-only configuration. A banner identifies a candidate family; only
 * complete command acknowledgements enable its native message format. */
internal class ElmNativeSegmentation(val info: Info, private val send: suspend (String) -> String) {
    enum class Family { STN, VT }
    data class Info(val chip: Int, val major: Int, val minor: Int, val patch: Int, val family: Family = Family.STN) {
        val version: Int get() = major * 10000 + minor * 100 + patch
    }
    private val rxOn get() = if (info.family == Family.STN) "STCSEGR1" else "VTSWGP GR1, BZF1"
    private val rxOff get() = if (info.family == Family.STN) "STCSEGR0" else "VTSWGP GR0"
    private val txOn get() = if (info.family == Family.STN) "STCSEGT1" else "VTSWGP GT1"
    private val txOff get() = if (info.family == Family.STN) "STCSEGT0" else "VTSWGP GT0"
    private enum class State { OFF, ON, UNKNOWN }
    private var state = State.OFF
    private var activePair: String? = null
    private var responseTimeout: Int? = null
    private var forceModeConfirmation = false
    private var forceDisableConfirmation = true
    private var unavailable = false
    val enabled: Boolean get() = state == State.ON

    suspend fun enableRoute(route: VagOemReadOnly.Route): Boolean {
        checkUsable()
        if (unavailable) return false
        val tx = requireNotNull(route.udsTx)
        val rx = requireNotNull(route.udsRx)
        require(tx in 0..0x1FFFFFFF && rx in 0..0x1FFFFFFF)
        val format = if (route.is29Bit) "%08X" else "%03X"
        val pair = if (info.family == Family.STN) "STCFCPA" + format.format(tx) + "," + format.format(rx)
            else "VTFCTRA " + format.format(tx) + ", " + format.format(rx)
        if (state == State.ON && activePair == pair && !forceModeConfirmation) return true
        val previous = state
        val confirmModes = previous == State.OFF || forceModeConfirmation
        state = State.UNKNOWN
        if (confirmModes && !accepted(rxOn)) {
            if (previous == State.OFF) { state = State.OFF; unavailable = true; return false }
            throw VagProtocolFailure("Не подтверждено состояние native CAN Rx")
        }
        val commands = (if (confirmModes) listOf(txOn) else emptyList()) +
            (if (info.family == Family.STN) listOf("STCFCPC", pair) else listOf(pair))
        for (command in commands) {
            if (!accepted(command)) { resetDisabled(); unavailable = true; return false }
        }
        state = State.ON
        activePair = pair
        forceModeConfirmation = false
        forceDisableConfirmation = false
        return true
    }
    fun onProtocolSelection() {
        forceModeConfirmation = true
        activePair = null
        responseTimeout = null
    }
    suspend fun setResponseTimeout(milliseconds: Int) {
        checkUsable()
        require(state == State.ON && milliseconds in 1..6000)
        if (responseTimeout == milliseconds) return
        state = State.UNKNOWN
        val command = if (info.family == Family.VT && info.version >= 20278) "VTTOST REP:$milliseconds" else "STPTO$milliseconds"
        if (!accepted(command)) throw VagProtocolFailure("Не подтверждён тайм-аут native CAN")
        responseTimeout = milliseconds
        state = State.ON
    }
    suspend fun disable() {
        checkUsable()
        if (state == State.ON || forceDisableConfirmation) resetDisabled()
    }
    private suspend fun resetDisabled() {
        state = State.UNKNOWN
        activePair = null
        responseTimeout = null
        if (!accepted(rxOff) || !accepted(txOff))
            throw VagProtocolFailure("Не подтверждено отключение native CAN segmentation")
        state = State.OFF
        forceDisableConfirmation = false
    }
    private fun checkUsable() {
        if (state == State.UNKNOWN) throw VagProtocolFailure("Состояние native CAN не подтверждено; нужна новая связь")
    }
    private suspend fun accepted(command: String): Boolean {
        val raw = try { send(command) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: IOException) { throw failure }
            catch (failure: Exception) { throw VagProtocolFailure("Не завершена настройка native CAN", failure) }
        val echo = command.filterNot(Char::isWhitespace)
        val rows = raw.uppercase().replace('>', '\n').replace('\r', '\n').split('\n')
            .map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != echo }
        return when (rows) {
            listOf("OK") -> true
            listOf("?") -> false
            else -> throw VagProtocolFailure("Неверный ответ на настройку native CAN")
        }
    }
    companion object {
        fun identify(raw: String): Info? {
            val text = raw.replace('>', ' ').replace(Regex("\\s+"), " ").trim()
            val match = Regex("^STN([0-9]{4}) [vV]([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{1,2})$").matchEntire(text) ?: return null
            return Info(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4].toInt())
        }
        fun identifyVt(raw: String): Info? {
            // The display name is not a capability or an ECU identifier.
            val pattern = Regex("^[A-Za-z][ -~]{1,79} [vV]([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{1,2})$")
            val candidates = raw.replace('>', '\n').replace('\r', '\n').split('\n').mapNotNull { row ->
                val match = pattern.matchEntire(row.replace(Regex("\\s+"), " ").trim()) ?: return@mapNotNull null
                Info(0, match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(), Family.VT)
            }.distinct()
            return candidates.singleOrNull()?.takeIf { it.version >= 20260 }
        }
    }
}
