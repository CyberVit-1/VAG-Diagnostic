package com.vagdiagnostic.app.transport

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BleUartProfilesTest {
    private fun uuid(value: String) = UUID.fromString(value)
    private val service = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
    private val receive = uuid("0000fff1-0000-1000-8000-00805f9b34fb")
    private val transmit = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
    private fun layout(readProperties: Int = BleUartProfiles.NOTIFY, writeProperties: Int = BleUartProfiles.WRITE,
        descriptor: Boolean = true) = BleUartProfiles.Service(service, listOf(
            BleUartProfiles.Characteristic(receive, readProperties,
                if (descriptor) setOf(BleUartProfiles.cccd) else emptySet()),
            BleUartProfiles.Characteristic(transmit, writeProperties)))

    @Test fun completeFff0ProfileCanBeSelectedWithoutADeviceName() {
        val selected = BleUartProfiles.select(listOf(layout()), null)
        assertNotNull("The complete FFF0/FFF1/FFF2 layout must be supported", selected)
        assertEquals(receive, selected!!.receive)
        assertEquals(transmit, selected.transmit)
    }

    @Test fun missingCapabilitiesOrCccdCannotBecomeAUsableProfile() {
        for (bad in listOf(layout(descriptor = false), layout(readProperties = 0), layout(writeProperties = 0),
            layout().copy(characteristics = layout().characteristics.take(1)),
            layout().copy(characteristics = layout().characteristics + layout().characteristics.first()))) {
            assertNull(BleUartProfiles.select(listOf(bad), "OBDLink CX"))
        }
        val first = layout().copy(characteristics = layout().characteristics.take(1))
        val second = layout().copy(uuid = UUID.randomUUID(), characteristics = layout().characteristics.drop(1))
        assertNull(BleUartProfiles.select(listOf(first, second), null))
    }

    @Test fun indicationAndUnacknowledgedWriteAreChosenFromActualProperties() {
        val selected = BleUartProfiles.select(listOf(layout(BleUartProfiles.INDICATE, BleUartProfiles.WRITE_NO_RESPONSE)), null)!!
        assertEquals(BleUartProfiles.Subscription.INDICATION, selected.subscription)
        assertEquals(BleUartProfiles.WriteMode.UNACKNOWLEDGED, selected.writeMode)
        val both = BleUartProfiles.select(listOf(layout(BleUartProfiles.NOTIFY or BleUartProfiles.INDICATE,
            BleUartProfiles.WRITE or BleUartProfiles.WRITE_NO_RESPONSE)), null)!!
        assertEquals(BleUartProfiles.Subscription.NOTIFICATION, both.subscription)
        assertEquals(BleUartProfiles.WriteMode.ACKNOWLEDGED, both.writeMode)
    }

    @Test fun existingNordicLayoutRemainsSupported() {
        val nus = BleUartProfiles.Service(uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), listOf(
            BleUartProfiles.Characteristic(uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), BleUartProfiles.NOTIFY, setOf(BleUartProfiles.cccd)),
            BleUartProfiles.Characteristic(uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), BleUartProfiles.WRITE)))
        assertEquals(nus.uuid, BleUartProfiles.select(listOf(nus), null)!!.service)
    }

    @Test fun modeSwitchNeedsTheWholeVendorLayoutAndItsNameGuard() {
        val mode = uuid("20b9794f-da1a-4d14-8014-a0fb9cefb2f7")
        val vendor = BleUartProfiles.Service(uuid("e47c8027-cca1-4e3b-981f-bdc47abeb5b5"), listOf(
            BleUartProfiles.Characteristic(uuid("cacc07ff-ffff-4c48-8fae-a9ef71b75e26"), BleUartProfiles.NOTIFY, setOf(BleUartProfiles.cccd)),
            BleUartProfiles.Characteristic(uuid("1cce1ea8-bd34-4813-a00a-c76e028fadcb"), BleUartProfiles.WRITE),
            BleUartProfiles.Characteristic(mode, BleUartProfiles.WRITE)))
        val selected = BleUartProfiles.select(listOf(vendor), "Kiwi aF")!!
        assertEquals(mode, selected.initialWrite!!.characteristic)
        assertEquals(listOf(1), selected.initialWrite.value)
        for (name in listOf(null, "Other adapter", "Kiwi Z0")) assertNull(BleUartProfiles.select(listOf(vendor), name))
        assertNull(BleUartProfiles.select(listOf(vendor.copy(characteristics = vendor.characteristics.take(2))), "Kiwi 01"))
    }
}
