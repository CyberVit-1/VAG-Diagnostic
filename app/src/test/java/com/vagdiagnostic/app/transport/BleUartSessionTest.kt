package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scripted GATT boundaries, not evidence from a physical Bluetooth adapter. */
class BleUartSessionTest {
    private fun uuid(value: String) = UUID.fromString(value)
    private val receive = uuid("0000fff1-0000-1000-8000-00805f9b34fb")
    private val transmit = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
    private fun layout() = BleUartProfiles.Service(uuid("0000fff0-0000-1000-8000-00805f9b34fb"), listOf(
        BleUartProfiles.Characteristic(receive, BleUartProfiles.NOTIFY, setOf(BleUartProfiles.cccd)),
        BleUartProfiles.Characteristic(transmit, BleUartProfiles.WRITE)))
    private data class Write(val uuid: UUID, val data: ByteArray, val mode: BleUartProfiles.WriteMode)
    private class Driver(val layouts: List<BleUartProfiles.Service>) : BleUartSession.Driver {
        lateinit var events: BleUartSession.Events
        lateinit var selected: BleUartProfiles.Selection
        val writes = mutableListOf<Write>()
        var connections = 0
        var discoveries = 0
        var subscriptions = 0
        var closes = 0
        var mtuRequests = 0
        var autoSubscribe = true
        var subscribeSuccess = true
        var startSubscribe = true
        var mtu = 23
        var startMtu = true
        var autoMtu = true
        var mtuSuccess = true
        var autoWrite = true
        var startWrite = true
        override fun connect(events: BleUartSession.Events): Boolean {
            connections++; this.events = events; events.connected(); return true
        }
        override fun discoverServices(): Boolean { discoveries++; events.servicesDiscovered(true); return true }
        override fun services() = layouts
        override fun subscribe(selection: BleUartProfiles.Selection): Boolean {
            subscriptions++; selected = selection
            if (startSubscribe && autoSubscribe) events.subscribed(selection.receive, BleUartProfiles.cccd, subscribeSuccess)
            return startSubscribe
        }
        override fun requestMtu(mtu: Int): Boolean {
            mtuRequests++
            if (startMtu && autoMtu) events.mtuChanged(this.mtu, mtuSuccess)
            return startMtu
        }
        override fun write(characteristic: UUID, value: ByteArray, mode: BleUartProfiles.WriteMode): Boolean {
            writes += Write(characteristic, value.copyOf(), mode)
            if (startWrite && autoWrite) events.written(characteristic, true)
            return startWrite
        }
        override fun close() { closes++ }
    }
    private fun session(driver: Driver, name: String? = null) = BleUartSession(driver, name,
        BleUartSession.Timeouts(connectMs = 1000, discoveryMs = 1000, subscriptionMs = 1000,
            operationMs = 400, mtuMs = 400, readMs = 5))

    @Test fun openingWaitsForTheMatchingRemoteCccdAcknowledgment() = runBlocking {
        val driver = Driver(listOf(layout())).apply { autoSubscribe = false }
        val session = session(driver)
        val open = async { session.open() }
        yield()
        assertFalse(open.isCompleted)
        driver.events.subscribed(transmit, BleUartProfiles.cccd, true)
        driver.events.subscribed(receive, UUID.randomUUID(), true)
        yield()
        assertFalse(open.isCompleted)
        assertEquals(0, driver.mtuRequests)
        driver.events.subscribed(receive, BleUartProfiles.cccd, true)
        open.await()
        driver.events.connected()
        driver.events.servicesDiscovered(true)
        assertEquals(1, driver.discoveries)
        assertEquals(1, driver.subscriptions)
        session.close()
    }

    @Test fun negotiatedMtuChunksWritesAndNotificationFragmentsRemainOneByteStream() = runBlocking {
        val driver = Driver(listOf(layout())).apply { mtu = 64 }
        val session = session(driver)
        session.open()
        val payload = ByteArray(130) { it.toByte() }
        session.write(payload)
        assertEquals(listOf(61, 61, 8), driver.writes.map { it.data.size })
        assertArrayEquals(payload, driver.writes.flatMap { it.data.toList() }.toByteArray())
        driver.events.received(transmit, byteArrayOf(99))
        driver.events.received(receive, byteArrayOf(0x7E, 0xFF.toByte()))
        driver.events.received(receive, byteArrayOf(0x3E))
        assertEquals(listOf(126, 255, 62), List(3) { session.readByte() })
        assertEquals(-2, session.readByte())
        session.close()
        val maxDriver = Driver(listOf(layout())).apply { mtu = 517 }
        val maxSession = session(maxDriver)
        maxSession.open(); maxSession.write(ByteArray(1025))
        assertEquals(listOf(512, 512, 1), maxDriver.writes.map { it.data.size })
        maxSession.close()
    }

    @Test fun refusedOrFailedOptionalMtuUsesTheDefaultTwentyBytePayload() = runBlocking {
        for (refused in listOf(true, false)) {
            val driver = Driver(listOf(layout())).apply { startMtu = !refused; mtuSuccess = false }
            val session = session(driver)
            session.open(); session.write(ByteArray(41))
            assertEquals(listOf(20, 20, 1), driver.writes.map { it.data.size })
            session.close()
        }
    }

    @Test fun acceptedButUnfinishedMtuClosesTheConnectionBeforeAnyUartWrite() = runBlocking {
        val driver = Driver(listOf(layout())).apply { autoMtu = false }
        val session = session(driver)
        val error = runCatching { session.open() }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals(1, driver.closes)
        assertTrue(driver.writes.isEmpty())
        driver.events.mtuChanged(517, true)
        assertTrue(runCatching { session.write(byteArrayOf(1)) }.exceptionOrNull() is IOException)
    }

    @Test fun descriptorRefusalCannotReportAReadyConnection() = runBlocking {
        for (notStarted in listOf(true, false)) {
            val driver = Driver(listOf(layout())).apply { startSubscribe = !notStarted; subscribeSuccess = false }
            val session = session(driver)
            assertTrue(runCatching { session.open() }.exceptionOrNull() is IOException)
            assertEquals(0, driver.mtuRequests)
            assertEquals(1, driver.closes)
            assertTrue(driver.writes.isEmpty())
        }
    }

    @Test fun vendorModeIsAcknowledgedOnceBeforeTheFirstElmBytes() = runBlocking {
        val mode = uuid("20b9794f-da1a-4d14-8014-a0fb9cefb2f7")
        val tx = uuid("1cce1ea8-bd34-4813-a00a-c76e028fadcb")
        val vendor = BleUartProfiles.Service(uuid("e47c8027-cca1-4e3b-981f-bdc47abeb5b5"), listOf(
            BleUartProfiles.Characteristic(uuid("cacc07ff-ffff-4c48-8fae-a9ef71b75e26"), BleUartProfiles.NOTIFY, setOf(BleUartProfiles.cccd)),
            BleUartProfiles.Characteristic(tx, BleUartProfiles.WRITE), BleUartProfiles.Characteristic(mode, BleUartProfiles.WRITE)))
        val driver = Driver(listOf(vendor))
        val session = session(driver, "Kiwi 01")
        session.open(); session.write("ATZ\r".toByteArray()); session.write("ATE0\r".toByteArray())
        assertEquals(listOf(mode, tx, tx), driver.writes.map { it.uuid })
        assertArrayEquals(byteArrayOf(1), driver.writes.first().data)
        assertArrayEquals("ATZ\r".toByteArray(), driver.writes[1].data)
        session.close()
    }

    @Test fun eachChunkWaitsForItsOwnMatchingCallbackAndCloseStopsTheRemainder() = runBlocking {
        val driver = Driver(listOf(layout()))
        val session = session(driver)
        session.open(); driver.autoWrite = false
        var failure: Throwable? = null
        val write = launch { failure = runCatching { session.write(ByteArray(45)) }.exceptionOrNull() }
        yield()
        assertEquals(1, driver.writes.size)
        driver.events.written(receive, true)
        yield(); assertEquals(1, driver.writes.size)
        driver.events.written(transmit, true)
        withTimeout(1000) { while (driver.writes.size < 2) yield() }
        assertFalse(write.isCompleted)
        session.close(); write.join()
        assertTrue(failure.toString(), failure is IOException)
        assertEquals(2, driver.writes.size)
        assertEquals(1, driver.closes)
    }

    @Test fun oldDisconnectedCallbacksCannotCompleteTheReplacementWrite() = runBlocking {
        val oldDriver = Driver(listOf(layout()))
        val oldSession = session(oldDriver)
        oldSession.open(); oldDriver.events.disconnected("Synthetic disconnection")
        val freshDriver = Driver(listOf(layout()))
        val fresh = session(freshDriver)
        fresh.open(); freshDriver.autoWrite = false
        val write = async { fresh.write(byteArrayOf(1)) }
        yield()
        oldDriver.events.written(transmit, true)
        oldDriver.events.received(receive, byteArrayOf(77))
        yield(); assertFalse(write.isCompleted)
        freshDriver.events.written(transmit, true); write.await()
        assertEquals(-2, fresh.readByte())
        assertEquals(1, oldDriver.closes)
        fresh.close()
    }

    @Test fun missingWriteCallbackIsAnIoFailureNotUserCancellationOrAResend() = runBlocking {
        val driver = Driver(listOf(layout()))
        val session = session(driver)
        session.open(); driver.autoWrite = false
        val error = runCatching { session.write(ByteArray(25)) }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals(1, driver.writes.size)
        assertEquals(1, driver.closes)
    }

    @Test fun cancellingOpenOrWriteClosesTheGattOnce() = runBlocking {
        for (opening in listOf(true, false)) {
            val driver = Driver(listOf(layout()))
            val session = session(driver)
            if (opening) driver.autoSubscribe = false else { session.open(); driver.autoWrite = false }
            val job = launch { if (opening) session.open() else session.write(ByteArray(41)) }
            yield(); job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(1, driver.closes)
            assertEquals(if (opening) 0 else 1, driver.writes.size)
            session.close(); assertEquals(1, driver.closes)
        }
    }

    @Test fun inputOverflowAndUnknownProfilesFailWithoutInventingAReply() = runBlocking {
        val unknown = Driver(emptyList())
        assertTrue(runCatching { session(unknown).open() }.exceptionOrNull() is IOException)
        assertEquals(0, unknown.subscriptions)
        val driver = Driver(listOf(layout()))
        val session = session(driver)
        session.open(); driver.events.received(receive, ByteArray(65537))
        assertTrue(runCatching { session.readByte() }.exceptionOrNull() is IOException)
        assertEquals(1, driver.closes)
    }
}
