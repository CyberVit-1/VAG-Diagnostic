package com.vagdiagnostic.app.transport

import java.util.UUID

/** GATT transport metadata, not ECU addresses or diagnostic commands. */
object BleUartProfiles {
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val WRITE_NO_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
    enum class WriteMode { ACKNOWLEDGED, UNACKNOWLEDGED }
    enum class Subscription { NOTIFICATION, INDICATION }
    data class Characteristic(val uuid: UUID, val properties: Int, val descriptors: Set<UUID> = emptySet())
    data class Service(val uuid: UUID, val characteristics: List<Characteristic>)
    data class InitialWrite(val characteristic: UUID, val value: List<Int>, val mode: WriteMode)
    data class Selection(val name: String, val service: UUID, val receive: UUID, val transmit: UUID,
        val writeMode: WriteMode, val subscription: Subscription, val initialWrite: InitialWrite? = null)
    private data class Profile(val name: String, val service: UUID, val receive: UUID, val transmit: UUID,
        val mode: UUID? = null, val deviceName: Regex? = null)
    private fun uuid(value: String) = UUID.fromString(value)
    private val profiles = listOf(
        Profile("UART FFF0", uuid("0000fff0-0000-1000-8000-00805f9b34fb"),
            uuid("0000fff1-0000-1000-8000-00805f9b34fb"), uuid("0000fff2-0000-1000-8000-00805f9b34fb")),
        Profile("UART E47C", uuid("e47c8027-cca1-4e3b-981f-bdc47abeb5b5"),
            uuid("cacc07ff-ffff-4c48-8fae-a9ef71b75e26"), uuid("1cce1ea8-bd34-4813-a00a-c76e028fadcb"),
            uuid("20b9794f-da1a-4d14-8014-a0fb9cefb2f7"), Regex("Kiwi [0-9A-Fa-f]{2}")),
        Profile("Nordic UART", uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e"))
    )

    private fun writeMode(properties: Int): WriteMode? = when {
        properties and WRITE != 0 -> WriteMode.ACKNOWLEDGED
        properties and WRITE_NO_RESPONSE != 0 -> WriteMode.UNACKNOWLEDGED
        else -> null
    }

    /** UUIDs must form a complete profile within the same service. A name alone
     * never authorizes arbitrary characteristic writes. */
    fun select(services: List<Service>, deviceName: String?): Selection? {
        for (profile in profiles) {
            if (profile.deviceName != null && (deviceName == null || !profile.deviceName.matches(deviceName))) continue
            for (service in services.filter { it.uuid == profile.service }) {
                val read = service.characteristics.singleOrNull { it.uuid == profile.receive } ?: continue
                val write = service.characteristics.singleOrNull { it.uuid == profile.transmit } ?: continue
                val mode = writeMode(write.properties) ?: continue
                if (cccd !in read.descriptors) continue
                val subscription = when {
                    read.properties and NOTIFY != 0 -> Subscription.NOTIFICATION
                    read.properties and INDICATE != 0 -> Subscription.INDICATION
                    else -> continue
                }
                val initial = if (profile.mode != null) {
                    val characteristic = service.characteristics.singleOrNull { it.uuid == profile.mode } ?: continue
                    val kind = writeMode(characteristic.properties) ?: continue
                    InitialWrite(profile.mode, listOf(1), kind)
                } else null
                return Selection(profile.name, service.uuid, read.uuid, write.uuid, mode, subscription, initial)
            }
        }
        return null
    }
}
