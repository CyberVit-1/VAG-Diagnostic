package com.vagdiagnostic.app.core

/** Documented function endpoints, never substitute DTC replies or presence.
 * Apply only after a declared address failed and its identified host was read. */
object VagDiagnosticTopology {
    data class Host(val address: String, val reason: String, val evidence: String)
    private fun normalized(text: String?) = text?.uppercase()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    private fun part(identity: VagEcuIdentification.Identity?) = normalized(identity?.partNumber).replace(" ", "").replace("-", "")
    fun needsComfortComponent(identity: VagEcuIdentification.Identity): Boolean =
        identity.component.isNullOrBlank() && Regex("(?:1K0|5K0)93708[0-9][A-Z0-9]{0,3}").matches(part(identity))
    fun needsAngleComponent(id: Int, identity: VagEcuIdentification.Identity): Boolean =
        identity.component.isNullOrBlank() && when (id) {
            0x03 -> Regex("1K0907379[A-Z0-9]{0,3}").matches(part(identity))
            0x44 -> Regex("1K[01]909144[A-Z0-9]{0,3}").matches(part(identity))
            else -> false
        }
    fun host(unit: ControlUnit, units: Map<String, ControlUnit>, identities: Map<Int, VagEcuIdentification.Identity?>): Host? {
        if (unit.state != ReadState.NO_RESPONSE || unit.source !in setOf("GATEWAY", "INSTALLATION_LIST") ||
            unit.dtcs.isNotEmpty() || unit.answeredModes.isNotEmpty()) return null
        fun read(address: String) = units[address]?.let { it.state == ReadState.COMPLETE && it.source == "RESPONSE" } == true
        if (unit.address == "VAG-46" && read("VAG-09")) {
            val central = identities[0x09]
            if (Regex("(?:1K0|5K0)93708[0-9][A-Z0-9]{0,3}").matches(part(central)) &&
                Regex("^BCM PQ35(?: |$)").containsMatchIn(normalized(central?.component))) {
                return Host("VAG-09", "Функции комфорта размещены в BCM PQ35, блок 09 прочитан. Отдельный ответ 46 не получен; собственные DTC ему не приписываются.",
                    "https://wiki.ross-tech.com/wiki/index.php/VW_Golf_(5K)_Body_Control_Module")
            }
        }
        if (unit.address == "VAG-04" && read("VAG-03") && read("VAG-44")) {
            val brakes = identities[0x03]
            val steering = identities[0x44]
            if (Regex("1K0907379[A-Z0-9]{0,3}").matches(part(brakes)) &&
                Regex("^MK70(?: |$)").containsMatchIn(normalized(brakes?.component)) &&
                Regex("1K[01]909144[A-Z0-9]{0,3}").matches(part(steering)) &&
                normalized(steering?.component).startsWith("EPS_ZFLS ")) {
                return Host("VAG-44", "Для конфигурации MK70 базовая установка G85 документирована через блок 44, он прочитан. Это связь функции, а не подтверждение отдельной памяти DTC по адресу 04.",
                    "https://wiki.ross-tech.com/wiki/index.php/VW_Golf_(1K)_Brake_Electronics_(MK70)")
            }
        }
        return null
    }
}
