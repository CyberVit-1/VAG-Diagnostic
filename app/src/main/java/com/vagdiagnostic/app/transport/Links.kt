package com.vagdiagnostic.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Only the adapter TCP socket binds to Wi-Fi; app/browser keeps cellular routing. */
class WifiTcpLink(private val context: Context, private val host: String, private val port: Int) : ElmLink {
    override val label = "Wi-Fi $host:$port"
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Некорректный порт" }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: error("Сеть Wi-Fi адаптера не обнаружена")
        val newSocket = wifi.socketFactory.createSocket()
        socket = newSocket // A concurrent Stop must be able to abort connect().
        try {
            newSocket.tcpNoDelay = true
            newSocket.soTimeout = 550
            newSocket.connect(InetSocketAddress(host, port), 6500)
            input = newSocket.getInputStream()
            output = newSocket.getOutputStream()
            socket = newSocket
        } catch (error: Exception) { newSocket.close(); if (socket === newSocket) socket = null; throw error }
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (socket?.isConnected != true || socket?.isClosed != false)
            throw java.net.SocketException("TCP-соединение закрыто")
        requireNotNull(output).write(bytes)
        requireNotNull(output).flush()
    }
    override suspend fun readByte(): Int = withContext(Dispatchers.IO) {
        try { requireNotNull(input).read() }
        catch (_: java.net.SocketTimeoutException) { -2 } // no byte *yet*, not EOF
    }
    override fun close() { runCatching { socket?.close() }; socket = null; input = null; output = null }
}

/** RFCOMM/SPP to the explicitly selected device; Android handles pairing. */
@SuppressLint("MissingPermission")
class BluetoothSppLink(private val context: Context, private val mac: String) : ElmLink {
    override val label = "Bluetooth $mac"
    private val sppId: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val lifecycle = Any()
    private var generation = 0L
    @Volatile private var session: SppSession? = null
    override suspend fun open() {
        val token = synchronized(lifecycle) {
            check(session == null) { "SPP-подключение уже открыто" }
            ++generation
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: error("Bluetooth отсутствует")
        check(adapter.isEnabled) { "Включите Bluetooth" }
        val device = adapter.getRemoteDevice(mac)
        val fresh = SppSession(object : SppSession.Driver {
            override fun cancelDiscovery() { adapter.cancelDiscovery() }
            override fun create(mode: SppSession.Mode): SppSession.Connection {
                val socket = if (mode == SppSession.Mode.INSECURE)
                    device.createInsecureRfcommSocketToServiceRecord(sppId)
                    else device.createRfcommSocketToServiceRecord(sppId)
                return AndroidSppConnection(socket)
            }
        })
        synchronized(lifecycle) {
            if (generation != token) { fresh.close(); throw java.io.IOException("SPP-подключение отменено") }
            session = fresh
        }
        try { fresh.open() }
        catch (problem: Exception) {
            synchronized(lifecycle) { if (session === fresh) session = null }
            fresh.close()
            throw problem
        }
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val stream = requireNotNull(session).output()
        stream.write(bytes); stream.flush()
    }
    override suspend fun readByte(): Int {
        val stream = requireNotNull(session).input()
        // Android BluetoothSocket does not expose a read timeout. Poll for cancel-friendly reads.
        if (withContext(Dispatchers.IO) { stream.available() } == 0) { delay(18); return -2 }
        return withContext(Dispatchers.IO) { stream.read() }
    }
    override fun close() {
        val old = synchronized(lifecycle) { ++generation; session.also { session = null } }
        old?.close()
    }
}

@SuppressLint("MissingPermission")
private class AndroidSppConnection(private val socket: android.bluetooth.BluetoothSocket) : SppSession.Connection {
    override suspend fun connect(): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        // Android connect() blocks. Cancellation closes this exact socket, including
        // after a timeout; an old completion cannot publish into a new session.
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) { runCatching { socket.close() }; return@Runnable }
            try {
                socket.connect()
                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                else runCatching { socket.close() }
            } catch (problem: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(problem))
                else runCatching { socket.close() }
            }
        })
    }
    override fun input(): InputStream = socket.inputStream
    override fun output(): OutputStream = socket.outputStream
    override fun close() = socket.close()
}

/** BLE UART with service-based selection. The class name remains compatible
 * with existing settings; each open gets fresh callback and byte-queue state. */
@SuppressLint("MissingPermission")
class BleNusLink(private val context: Context, private val mac: String) : ElmLink {
    override val label = "BLE $mac"
    override val needsUartReadinessProbe = true
    private val lifecycle = Any()
    private var generation = 0L
    @Volatile private var session: BleUartSession? = null
    override suspend fun open() {
        val token = synchronized(lifecycle) {
            check(session == null) { "BLE-подключение уже открыто" }
            ++generation
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("BLE отсутствует")
        check(adapter.isEnabled) { "Включите Bluetooth" }
        val device = adapter.getRemoteDevice(mac)
        val fresh = BleUartSession(AndroidBleUartDriver(context, device), device.name)
        synchronized(lifecycle) {
            if (generation != token) { fresh.close(); throw java.io.IOException("BLE-подключение отменено") }
            session = fresh
        }
        try { fresh.open() }
        catch (problem: Exception) {
            synchronized(lifecycle) { if (session === fresh) session = null }
            fresh.close()
            throw problem
        }
    }
    override suspend fun write(bytes: ByteArray) = requireNotNull(session) { "BLE закрыт" }.write(bytes)
    override suspend fun readByte(): Int = requireNotNull(session) { "BLE закрыт" }.readByte()
    override fun close() {
        val old = synchronized(lifecycle) { ++generation; session.also { session = null } }
        old?.close()
    }
}

@SuppressLint("MissingPermission")
private class AndroidBleUartDriver(private val context: Context, private val device: BluetoothDevice) : BleUartSession.Driver {
    private val lifecycle = Any()
    private var closed = false
    @Volatile private var gatt: android.bluetooth.BluetoothGatt? = null
    @Volatile private var events: BleUartSession.Events? = null
    @Volatile private var service: UUID? = null
    private fun accepts(value: android.bluetooth.BluetoothGatt): Boolean = synchronized(lifecycle) {
        if (closed) false else {
            if (gatt == null) gatt = value
            gatt === value
        }
    }
    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
            if (!accepts(g)) return
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) events?.disconnected("BLE GATT status=$status")
            else if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) events?.connected()
            else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) events?.disconnected("BLE отключён")
        }
        override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
            if (accepts(g)) events?.servicesDiscovered(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
        override fun onDescriptorWrite(g: android.bluetooth.BluetoothGatt, desc: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            if (accepts(g) && desc.characteristic.service?.uuid == service)
                events?.subscribed(desc.characteristic.uuid, desc.uuid, status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
        override fun onMtuChanged(g: android.bluetooth.BluetoothGatt, mtu: Int, status: Int) {
            if (accepts(g)) events?.mtuChanged(mtu, status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicWrite(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
            if (accepts(g) && c.service?.uuid == service)
                events?.written(c.uuid, status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicChanged(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
            if (accepts(g) && c.service?.uuid == service) events?.received(c.uuid, value)
        }
        @Deprecated("pre API 33")
        override fun onCharacteristicChanged(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (accepts(g) && c.service?.uuid == service) c.value?.let { events?.received(c.uuid, it) }
        }
    }
    override fun connect(events: BleUartSession.Events): Boolean {
        synchronized(lifecycle) { if (closed) return false; this.events = events }
        val created = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return false
        return synchronized(lifecycle) {
            if (closed) { created.close(); false } else { gatt = created; true }
        }
    }
    override fun discoverServices(): Boolean = gatt?.discoverServices() == true
    override fun services(): List<BleUartProfiles.Service> = gatt?.services.orEmpty().map { s ->
        BleUartProfiles.Service(s.uuid, s.characteristics.map { c ->
            BleUartProfiles.Characteristic(c.uuid, c.properties, c.descriptors.map { it.uuid }.toSet())
        })
    }
    override fun subscribe(selection: BleUartProfiles.Selection): Boolean {
        val g = gatt ?: return false
        val c = g.getService(selection.service)?.getCharacteristic(selection.receive) ?: return false
        val descriptor = c.getDescriptor(BleUartProfiles.cccd) ?: return false
        service = selection.service
        if (!g.setCharacteristicNotification(c, true)) return false
        val value = if (selection.subscription == BleUartProfiles.Subscription.NOTIFICATION)
            android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return if (android.os.Build.VERSION.SDK_INT >= 33)
            g.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }
    override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) == true
    override fun write(characteristic: UUID, value: ByteArray, mode: BleUartProfiles.WriteMode): Boolean {
        val g = gatt ?: return false
        val selectedService = service ?: return false
        val c = g.getService(selectedService)?.getCharacteristic(characteristic) ?: return false
        val type = if (mode == BleUartProfiles.WriteMode.ACKNOWLEDGED)
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (android.os.Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, value, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }
    override fun close() {
        val old = synchronized(lifecycle) {
            closed = true
            gatt.also { gatt = null }
        }
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }
}

@SuppressLint("MissingPermission")
fun pairedClassic(context: Context): List<Pair<String, String>> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    adapter?.bondedDevices?.map { (it.name ?: "Bluetooth") to it.address }?.sortedBy { it.first } ?: emptyList()
}.getOrDefault(emptyList())
