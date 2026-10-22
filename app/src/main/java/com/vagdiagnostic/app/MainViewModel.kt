package com.vagdiagnostic.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vagdiagnostic.app.core.ControlUnit
import com.vagdiagnostic.app.core.DtcKind
import com.vagdiagnostic.app.core.DiagnosticLogRedactor
import com.vagdiagnostic.app.core.ElmFrames
import com.vagdiagnostic.app.core.ObdDecoder
import com.vagdiagnostic.app.core.ReadState
import com.vagdiagnostic.app.data.ConnectionSettings
import com.vagdiagnostic.app.data.DiagnosticDatabase
import com.vagdiagnostic.app.data.JournalEntity
import com.vagdiagnostic.app.data.ScanCodec
import com.vagdiagnostic.app.data.ScanEntity
import com.vagdiagnostic.app.data.Settings
import com.vagdiagnostic.app.transport.BleNusLink
import com.vagdiagnostic.app.transport.BluetoothSppLink
import com.vagdiagnostic.app.transport.ElmEngine
import com.vagdiagnostic.app.transport.ElmScanSession
import com.vagdiagnostic.app.transport.WifiTcpLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

// A stale TCP link can still report isConnected/vehicleReady after Wi-Fi or the
// adapter has closed the socket. Recover only read-only commands and only once.
private fun isTransportDisconnected(error: Throwable): Boolean =
    error is java.io.IOException ||
        (error is IllegalStateException &&
            error.message?.contains("Соединение закрыто до окончания ответа") == true)

private val friendlyNames = mapOf("7E8" to "Двигатель / OBD-II ECU", "7E9" to "Трансмиссия / OBD-II ECU")
data class DashboardState(
    val adapterConnected: Boolean = false,
    val carConnected: Boolean = false,
    val protocol: String = "Не определён",
    val adapterName: String = "—",
    val error: String = "",
    val isBusy: Boolean = false,
    val isScanning: Boolean = false,
    val current: String = "",
    val completedModes: Int = 0,
    val units: List<ControlUnit> = emptyList(),
    val lastScanInterrupted: Boolean = false,
    val bleFound: List<Pair<String, String>> = emptyList()
) {
    val discovered: Int get() = units.size
    val polled: Int get() = if (completedModes == 3) units.size else 0
    val successful: Int get() = units.count { it.state == ReadState.COMPLETE }
    val faulty: Int get() = units.count { it.dtcs.isNotEmpty() }
    val faultCount: Int get() = units.sumOf { it.dtcs.size }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DiagnosticDatabase.get(app)
    private val preferences = Settings(app)
    private val context: Context get() = getApplication()
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(ConnectionSettings())
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()
    val history = db.scans().observe()
    private var engine: ElmEngine? = null
    private var activeJob: Job? = null
    private var scannerCallback: ScanCallback? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanStartedAt = 0L

    init { viewModelScope.launch { preferences.values.collectLatest { _settings.value = it } } }
    fun saveSettings(s: ConnectionSettings) {
        _settings.value = s
        viewModelScope.launch { preferences.update(s) }
    }
    private fun createEngine(cfg: ConnectionSettings): ElmEngine {
        val driver = when (cfg.type) {
            "Wi-Fi" -> WifiTcpLink(context, cfg.host, cfg.port)
            "Bluetooth Classic" -> BluetoothSppLink(context, cfg.mac)
            "BLE / NUS" -> BleNusLink(context, cfg.mac)
            else -> error("Неизвестный транспорт")
        }
        return ElmEngine(driver, log = { cmd, raw, ms, result ->
            db.journal().insert(JournalEntity(timestamp = System.currentTimeMillis(), connection = driver.label,
                command = cmd, raw = raw, elapsedMs = ms, outcome = result))
        })
    }

    fun connect() {
        if (activeJob?.isActive == true || engine != null) return
        activeJob = viewModelScope.launch {
            _state.value = DashboardState(isBusy = true, adapterName = _settings.value.type)
            val cfg = _settings.value
            val client = createEngine(cfg)
            engine = client
            try {
                client.connect()
                _state.value = _state.value.copy(adapterConnected = client.adapterReady, carConnected = client.vehicleReady,
                    protocol = client.protocol, adapterName = cfg.type, isBusy = false, error = client.carError)
            } catch (error: Throwable) {
                client.close(); engine = null
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(adapterConnected = client.adapterReady, carConnected = false,
                    protocol = "Не определён", isBusy = false,
                    error = "Подключение: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }
    fun disconnect() {
        activeJob?.cancel()
        engine?.close(); engine = null
        _state.value = _state.value.copy(adapterConnected = false, carConnected = false,
            isBusy = false, isScanning = false, protocol = "Не определён", current = "")
    }
    fun scan() {
        if (activeJob?.isActive == true) return
        val initialClient = engine ?: run { _state.value = _state.value.copy(error = "Подключите адаптер"); return }
        if (!initialClient.vehicleReady) { _state.value = _state.value.copy(error = "Связь с автомобилем не подтверждена"); return }
        activeJob = viewModelScope.launch {
            // Snapshot settings: do not switch transports/host halfway through a scan.
            val cfg = _settings.value
            val session = ElmScanSession(
                first = initialClient,
                wifi = cfg.type == "Wi-Fi",
                newEngine = { createEngine(cfg) },
                onEngineChanged = { replacement ->
                    engine = replacement
                    _state.value = if (replacement == null) _state.value.copy(
                        adapterConnected = false, carConnected = false, protocol = "Не определён"
                    ) else _state.value.copy(
                        adapterConnected = replacement.adapterReady,
                        carConnected = replacement.vehicleReady,
                        protocol = replacement.protocol
                    )
                }
            )
            suspend fun readOnlyCommand(command: String, timeoutMs: Long): String =
                session.read(command, timeoutMs)
            scanStartedAt = System.currentTimeMillis()
            var interrupted = false
            var rowId = 0L
            suspend fun checkpoint(current: List<ControlUnit>, modes: Int, incomplete: Boolean) {
                val record = ScanEntity(id = rowId, started = scanStartedAt, finished = System.currentTimeMillis(),
                    interrupted = incomplete, completedModes = modes, json = ScanCodec.encode(current))
                if (rowId == 0L) rowId = db.scans().insert(record)
                else db.scans().update(record)
            }
            _state.value = _state.value.copy(isScanning = true, isBusy = true, units = emptyList(),
                current = "Обнаружение ответчиков OBD-II", error = "", completedModes = 0, lastScanInterrupted = false)
            val units = linkedMapOf<String, ControlUnit>()
            try {
                checkpoint(emptyList(), 0, true)
                session.prepare()
                val raw = readOnlyCommand("0100", 9000)
                val replies = ElmFrames.parse("0100", raw)
                check(ObdDecoder.hasValidVehicleResponse(replies)) { "Ответ OBD-II не подтверждён" }
                replies.messagesFor(0x41).forEach { (id, _) ->
                    units[id] = ControlUnit(id, friendlyNames[id] ?: "OBD-II ответчик $id", ReadState.NOT_QUERIED)
                }
                _state.value = _state.value.copy(units = units.values.toList())
                checkpoint(units.values.toList(), 0, true)
                // Each Mode 03/07/0A is addressed as legislated OBD-II, NOT proprietary VAG scan.
                for ((index, kind) in DtcKind.entries.withIndex()) {
                    _state.value = _state.value.copy(current = "OBD-II: ${kind.title.lowercase()} память DTC")
                    val answer = readOnlyCommand(kind.command, 8500)
                    val frames = ElmFrames.parse(kind.command, answer)
                    val faults = runCatching {
                        check(frames.problems.isEmpty()) { frames.problems.joinToString() }
                        ObdDecoder.decodedMode(frames, kind, session.current.protocol.lastOrNull() in listOf('6', '7', '8', '9'))
                    }
                    if (faults.isFailure) {
                        _state.value = _state.value.copy(error = "${kind.command}: ${faults.exceptionOrNull()?.message}")
                    } else {
                        faults.getOrThrow().forEach { (address, dtcs) ->
                            val old = units[address] ?: ControlUnit(address, "OBD-II ответчик $address", ReadState.NOT_QUERIED)
                            units[address] = old.copy(dtcs = (old.dtcs + dtcs).distinctBy { it.code to it.kind },
                                answeredModes = old.answeredModes + kind)
                        }
                    }
                    units.replaceAll { _, value ->
                        value.copy(state = when (value.answeredModes.size) {
                            3 -> ReadState.COMPLETE
                            0 -> if (index == 2) ReadState.NO_RESPONSE else ReadState.NOT_QUERIED
                            else -> ReadState.PARTIAL
                        }, note = when {
                            value.answeredModes.size == 3 -> "Подтверждены режимы 03, 07 и 0A"
                            value.answeredModes.isNotEmpty() -> "Частичный ответ: не все режимы подтверждены"
                            else -> "Ответ по DTC не получен"
                        })
                    }
                    _state.value = _state.value.copy(units = units.values.toList(), completedModes = index + 1)
                    checkpoint(units.values.toList(), index + 1, index != 2)
                }
            } catch (error: CancellationException) {
                interrupted = true; throw error
            } catch (error: Throwable) {
                interrupted = true
                val linkLost = error is java.io.IOException || engine == null ||
                    (cfg.type == "Wi-Fi" && session.hasReactiveRecovery)
                if (linkLost) session.close()
                _state.value = _state.value.copy(error =
                    "Диагностика: ${error.message ?: error::class.java.simpleName}" +
                    if (linkLost) ". Связь потеряна; подключитесь снова." else "")
            } finally {
                _state.value = _state.value.copy(isScanning = false, isBusy = false,
                    current = "", units = units.values.toList(), lastScanInterrupted = interrupted)
                // Non-cancellable persistence: partial scans survive Stop and disconnections.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        checkpoint(units.values.toList(), _state.value.completedModes, interrupted)
                    }
                }
            }
        }
    }
    fun stopScan() {
        activeJob?.cancel()
        // Abort the current transport command: otherwise the next read might see a stale prompt.
        engine?.close(); engine = null
        _state.value = _state.value.copy(adapterConnected = false, carConnected = false,
            protocol = "Не определён", error = "Сканирование остановлено, соединение закрыто для синхронизации")
    }
    fun useHistory(scan: ScanEntity) = runCatching {
        _state.value = _state.value.copy(units = ScanCodec.decode(scan.json), isScanning = false,
            completedModes = scan.completedModes, lastScanInterrupted = scan.interrupted, current = "")
    }.onFailure { _state.value = _state.value.copy(error = "История повреждена: ${it.message}") }

    fun internetAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.let { cap ->
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }
    fun yandexUrl(code: String): String = "https://yandex.ru/search/?text=" +
        URLEncoder.encode("VAG fault code $code", "UTF-8")

    suspend fun exportRedactedLogs(): String {
        val content = StringBuilder("VAG Diagnostic 0.1.1 / Сырой журнал (VIN замаскирован)\n")
        val entries = db.journal().latest(10000).reversed()
        entries.forEach { e ->
            content.append("${e.timestamp} | ${e.connection} | ${e.command} | ${e.elapsedMs}ms | ${e.outcome}\n")
            content.append(DiagnosticLogRedactor.redact(e.raw)).append("\n")
        }
        if (entries.size == 10000) content.append("\nДостигнут предел экспорта 10000 записей\n")
        return content.toString()
    }
    @SuppressLint("MissingPermission")
    fun discoverBle() {
        if (scannerCallback != null) return
        runCatching {
            val bt = context.getSystemService(BluetoothManager::class.java).adapter ?: error("Нет Bluetooth")
            check(bt.isEnabled) { "Включите Bluetooth" }
            val found = linkedMapOf<String, String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    found[result.device.address] = result.device.name ?: result.scanRecord?.deviceName ?: "BLE устройство"
                    _state.value = _state.value.copy(bleFound = found.map { it.value to it.key })
                }
                override fun onScanFailed(errorCode: Int) {
                    _state.value = _state.value.copy(error = "BLE scan error=$errorCode")
                    stopBleDiscovery()
                }
            }
            scanner = bt.bluetoothLeScanner ?: error("Сканер BLE недоступен")
            scannerCallback = cb
            _state.value = _state.value.copy(bleFound = emptyList())
            scanner?.startScan(cb)
            viewModelScope.launch { delay(7000); stopBleDiscovery() }
        }.onFailure { _state.value = _state.value.copy(error = "BLE: ${it.message}") }
    }
    @SuppressLint("MissingPermission")
    private fun stopBleDiscovery() {
        scannerCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }
        scanner = null; scannerCallback = null
    }
    override fun onCleared() { stopBleDiscovery(); engine?.close(); super.onCleared() }
}