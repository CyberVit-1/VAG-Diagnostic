package com.vagdiagnostic.app.transport

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A single GATT connection owns its queue and pending callbacks. Reconnects
 * create a fresh session; callbacks from an old session cannot satisfy it. */
class BleUartSession(private val driver: Driver, private val deviceName: String?,
    private val timeouts: Timeouts = Timeouts()) {
    data class Timeouts(val connectMs: Long = 5000, val discoveryMs: Long = 8000,
        val subscriptionMs: Long = 10000, val operationMs: Long = 15000,
        val mtuMs: Long = 5000, val readMs: Long = 120)
    interface Driver {
        fun connect(events: Events): Boolean
        fun discoverServices(): Boolean
        fun services(): List<BleUartProfiles.Service>
        /** Includes local notification enable and the remote CCCD write. */
        fun subscribe(selection: BleUartProfiles.Selection): Boolean
        fun requestMtu(mtu: Int): Boolean
        fun write(characteristic: UUID, value: ByteArray, mode: BleUartProfiles.WriteMode): Boolean
        fun close()
    }
    interface Events {
        fun connected()
        fun disconnected(reason: String)
        fun servicesDiscovered(success: Boolean)
        fun subscribed(characteristic: UUID, descriptor: UUID, success: Boolean)
        fun mtuChanged(mtu: Int, success: Boolean)
        fun written(characteristic: UUID, success: Boolean)
        fun received(characteristic: UUID, value: ByteArray)
    }
    private data class PendingWrite(val characteristic: UUID, val result: CompletableDeferred<Unit>)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val ready = AtomicBoolean()
    private val operations = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()
    private val subscribed = CompletableDeferred<Unit>()
    private val mtuWait = AtomicReference<CompletableDeferred<Int>?>()
    private val pendingWrite = AtomicReference<PendingWrite?>()
    private val incoming = Channel<Int>(65536)
    @Volatile private var selection: BleUartProfiles.Selection? = null
    @Volatile private var payloadBytes = 20 // ATT MTU23 minus its three-byte header.
    @Volatile private var closeReason: IOException? = null

    private fun fail(reason: IOException) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        ready.set(false)
        connected.completeExceptionally(reason)
        discovered.completeExceptionally(reason)
        subscribed.completeExceptionally(reason)
        mtuWait.getAndSet(null)?.completeExceptionally(reason)
        pendingWrite.getAndSet(null)?.result?.completeExceptionally(reason)
        incoming.close(reason)
        runCatching { driver.close() }
    }
    private fun ensureOpen() {
        if (closed.get()) throw closeReason ?: IOException("BLE-соединение закрыто")
    }
    private val events = object : Events {
        override fun connected() {
            if (!closed.get()) connected.complete(Unit)
        }
        override fun disconnected(reason: String) = fail(IOException(reason))
        override fun servicesDiscovered(success: Boolean) {
            if (closed.get()) return
            if (success) discovered.complete(Unit) else fail(IOException("Не удалось прочитать BLE-сервисы"))
        }
        override fun subscribed(characteristic: UUID, descriptor: UUID, success: Boolean) {
            if (closed.get() || characteristic != selection?.receive || descriptor != BleUartProfiles.cccd) return
            if (success) subscribed.complete(Unit) else fail(IOException("BLE UART: запись CCCD отклонена"))
        }
        override fun mtuChanged(mtu: Int, success: Boolean) {
            if (closed.get()) return
            mtuWait.get()?.complete(if (success && mtu in 23..517) mtu else 23)
        }
        override fun written(characteristic: UUID, success: Boolean) {
            if (closed.get()) return
            val pending = pendingWrite.get() ?: return
            if (pending.characteristic != characteristic) return
            if (success) pending.result.complete(Unit) else fail(IOException("BLE UART: передача отклонена"))
        }
        override fun received(characteristic: UUID, value: ByteArray) {
            if (closed.get() || characteristic != selection?.receive) return
            for (byte in value) if (incoming.trySend(byte.toInt() and 255).isFailure) {
                fail(IOException("Переполнен входной буфер BLE UART")); return
            }
        }
    }

    suspend fun open() {
        check(started.compareAndSet(false, true)) { "Для повторного подключения нужен новый BLE-сеанс" }
        ensureOpen()
        try {
            if (!driver.connect(events)) throw IOException("BLE-подключение не запущено")
            withTimeoutOrNull(timeouts.connectMs) { connected.await() }
                ?: throw IOException("Тайм-аут подключения BLE")
            ensureOpen()
            if (!driver.discoverServices()) throw IOException("Не запущено обнаружение BLE-сервисов")
            withTimeoutOrNull(timeouts.discoveryMs) { discovered.await() }
                ?: throw IOException("Тайм-аут обнаружения BLE-сервисов")
            ensureOpen()
            val selected = BleUartProfiles.select(driver.services(), deviceName)
                ?: throw IOException("Поддерживаемый BLE UART-профиль не обнаружен")
            selection = selected
            if (!driver.subscribe(selected)) throw IOException("Не запущена подписка BLE UART")
            withTimeoutOrNull(timeouts.subscriptionMs) { subscribed.await() }
                ?: throw IOException("Тайм-аут подписки BLE UART")
            ensureOpen()
            val mtu = CompletableDeferred<Int>()
            mtuWait.set(mtu)
            // An immediately refused optional MTU request leaves MTU23 usable.
            // An accepted request that never completes closes this session;
            // do not start overlapping GATT operations on an unknown boundary.
            if (driver.requestMtu(517)) {
                val negotiated = withTimeoutOrNull(timeouts.mtuMs) { mtu.await() }
                    ?: throw IOException("Тайм-аут согласования BLE MTU")
                payloadBytes = (negotiated - 3).coerceIn(20, 512)
            }
            mtuWait.compareAndSet(mtu, null)
            operations.withLock {
                selected.initialWrite?.let { initial ->
                    writeValue(initial.characteristic, initial.value.map { it.toByte() }.toByteArray(), initial.mode)
                }
            }
            ensureOpen()
            ready.set(true)
        } catch (problem: Exception) {
            fail(IOException("Открытие BLE UART прервано: ${problem.message}", problem))
            throw problem
        }
    }

    private suspend fun writeValue(characteristic: UUID, bytes: ByteArray, mode: BleUartProfiles.WriteMode) {
        ensureOpen()
        val pending = PendingWrite(characteristic, CompletableDeferred())
        check(pendingWrite.compareAndSet(null, pending)) { "Перекрывающиеся BLE-запросы" }
        try {
            if (!driver.write(characteristic, bytes, mode)) throw IOException("BLE UART: передача не запущена")
            withTimeoutOrNull(timeouts.operationMs) { pending.result.await() }
                ?: throw IOException("Тайм-аут передачи BLE UART")
            ensureOpen()
        } finally { pendingWrite.compareAndSet(pending, null) }
    }
    suspend fun write(bytes: ByteArray) = operations.withLock {
        ensureOpen()
        check(ready.get()) { "BLE UART ещё не готов" }
        val selected = requireNotNull(selection)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + payloadBytes, bytes.size)
                writeValue(selected.transmit, bytes.copyOfRange(offset, end), selected.writeMode)
                offset = end
            }
        } catch (problem: Exception) {
            fail(IOException("Передача BLE UART прервана: ${problem.message}", problem))
            throw problem
        }
    }
    suspend fun readByte(): Int {
        ensureOpen()
        val result = withTimeoutOrNull(timeouts.readMs) { incoming.receiveCatching() } ?: return -2
        ensureOpen()
        if (result.isClosed) throw result.exceptionOrNull() ?: IOException("BLE UART отключён")
        return result.getOrThrow()
    }
    fun close() = fail(IOException("BLE-соединение закрыто"))
}
