package com.vagdiagnostic.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** RFCOMM/SPP; the phone must already be paired with the device. */
@SuppressLint("MissingPermission")
class BluetoothSppLink(private val context: Context, private val mac: String) : ElmLink {
    override val label = "Bluetooth $mac"
    private val sppId: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: android.bluetooth.BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    override suspend fun open() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: error("Bluetooth отсутствует")
        check(adapter.isEnabled) { "Включите Bluetooth" }
        val device = adapter.getRemoteDevice(mac)
        check(adapter.bondedDevices.any { it.address == device.address }) { "Сначала выполните сопряжение в настройках Android" }
        adapter.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(sppId)
        socket = s // published before connect so a concurrent close() cancels it
        try { s.connect(); input = s.inputStream; output = s.outputStream }
        catch (error: Exception) { close(); throw error }
    }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        requireNotNull(output).write(bytes); requireNotNull(output).flush()
    }
    override suspend fun readByte(): Int {
        val stream = requireNotNull(input)
        // Android BluetoothSocket does not expose a read timeout. Poll for cancel-friendly reads.
        if (withContext(Dispatchers.IO) { stream.available() } == 0) { delay(18); return -2 }
        return withContext(Dispatchers.IO) { stream.read() }
    }
    override fun close() { runCatching { socket?.close() }; socket = null; input = null; output = null }
}

/** V0.1 only supports Nordic UART Service (NUS), not arbitrary BLE ELM GATT layouts. */
@SuppressLint("MissingPermission")
class BleNusLink(private val context: Context, private val mac: String) : ElmLink {
    override val label = "BLE/NUS $mac"
    private val serviceId = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val txId = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // phone writes
    private val rxId = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // phone receives
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val queue = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var gatt: android.bluetooth.BluetoothGatt? = null
    private var writer: android.bluetooth.BluetoothGattCharacteristic? = null
    private var opening: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var writeFinished: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                opening?.completeExceptionally(IllegalStateException("BLE GATT status=$status"))
                queue.close(IllegalStateException("BLE отключён")); return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) opening?.completeExceptionally(IllegalStateException("BLE services не обнаружены"))
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                opening?.completeExceptionally(IllegalStateException("BLE отключён"))
                queue.close(IllegalStateException("BLE отключён"))
            }
        }
        override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt, status: Int) {
            val service = g.getService(serviceId)
            val notify = service?.getCharacteristic(rxId)
            val write = service?.getCharacteristic(txId)
            val cccd = notify?.getDescriptor(cccdId)
            if (status != 0 || notify == null || write == null || cccd == null || !g.setCharacteristicNotification(notify, true)) {
                opening?.completeExceptionally(IllegalStateException("Адаптер не поддерживает BLE Nordic UART")); return
            }
            writer = write
            @Suppress("DEPRECATION")
            cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(cccd)) opening?.completeExceptionally(IllegalStateException("Не удалось включить BLE notifications"))
        }
        override fun onDescriptorWrite(g: android.bluetooth.BluetoothGatt, desc: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            if (status == 0) opening?.complete(Unit)
            else opening?.completeExceptionally(IllegalStateException("BLE CCCD status=$status"))
        }
        override fun onCharacteristicChanged(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == rxId) value.forEach { queue.trySend(it.toInt() and 255) }
        }
        @Deprecated("pre API 33")
        override fun onCharacteristicChanged(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (c.uuid == rxId) c.value?.forEach { queue.trySend(it.toInt() and 255) }
        }
        override fun onCharacteristicWrite(g: android.bluetooth.BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
            if (status == 0) writeFinished?.complete(Unit)
            else writeFinished?.completeExceptionally(IllegalStateException("BLE write status=$status"))
        }
    }
    override suspend fun open() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("BLE отсутствует")
        check(adapter.isEnabled) { "Включите Bluetooth" }
        val device = adapter.getRemoteDevice(mac)
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        opening = ready
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try { kotlinx.coroutines.withTimeout(12000) { ready.await() } }
        catch (error: Exception) { close(); throw error }
    }
    override suspend fun write(bytes: ByteArray) {
        val g = requireNotNull(gatt)
        val c = requireNotNull(writer)
        for (chunk in bytes.toList().chunked(20)) {
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            writeFinished = ready
            val data = chunk.toByteArray()
            val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            check(ok) { "BLE write не запущен" }
            kotlinx.coroutines.withTimeout(4000) { ready.await() }
        }
    }
    override suspend fun readByte(): Int = kotlinx.coroutines.withTimeoutOrNull(120) { queue.receive() } ?: -2
    override fun close() {
        runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null
        opening?.cancel(); writeFinished?.cancel(); queue.close()
    }
}

@SuppressLint("MissingPermission")
fun pairedClassic(context: Context): List<Pair<String, String>> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    adapter?.bondedDevices?.map { (it.name ?: "Bluetooth") to it.address }?.sortedBy { it.first } ?: emptyList()
}.getOrDefault(emptyList())
