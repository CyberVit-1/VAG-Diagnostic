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
import com.vagdiagnostic.app.core.ObdMode09Readiness
import com.vagdiagnostic.app.core.VagTp20Gateway
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
import com.vagdiagnostic.app.transport.ElmAddressedObdProbe
import com.vagdiagnostic.app.transport.ElmVagScan
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.URLEncoder

// A stale TCP link can still report isConnected/vehicleReady after Wi-Fi or the
// adapter has closed the socket. Recover only read-only commands and only once.
private fun isTransportDisconnected(error: Throwable): Boolean =
    error is java.io.IOException ||
        (error is IllegalStateException &&
            error.message?.contains("Соединение закрыто до окончания ответа") == true)

private val friendlyNames = mapOf("7E8" to "Основной OBD-II ответчик", "7E9" to "Дополнительный OBD-II ответчик")
data class DashboardState(
    val adapterConnected: Boolean = false,
    val carConnected: Boolean = false,
    val protocol: String = "Не определён",
    val adapterName: String = "—",
    val vehicleVin: String? = null,
    val error: String = "",
    val scanWarning: String = "",
    val isoTpReadiness: String = "",
    val isBusy: Boolean = false,
    val isScanning: Boolean = false,
    val current: String = "",
    val completedModes: Int = 0,
    val units: List<ControlUnit> = emptyList(),
    val lastScanInterrupted: Boolean = false,
    val bleFound: List<Pair<String, String>> = emptyList()
) {
    // Generic OBD responders may represent the same physical ECU as OEM rows.
    // Once OEM rows exist, show OEM counters rather than adding both namespaces.
    val hasOemResults: Boolean get() = units.any { it.source != "OBD" }
    private val countedUnits: List<ControlUnit> get() = units.filter { !hasOemResults || it.source != "OBD" }
    val discovered: Int get() = countedUnits.count { it.source != "CANDIDATE" }
    // An ECU is counted as queried only after a validated DTC-mode response.
    val polled: Int get() = countedUnits.count { it.answeredModes.isNotEmpty() }
    val successful: Int get() = countedUnits.count { it.state == ReadState.COMPLETE }
    val partial: Int get() = countedUnits.count { it.state == ReadState.PARTIAL }
    val faulty: Int get() = countedUnits.count { it.dtcs.isNotEmpty() }
    val faultCount: Int get() = countedUnits.sumOf { it.dtcs.size }
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
    private var connectedSettings: ConnectionSettings? = null
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
        return ElmEngine(driver, log = { _, _, _, _ -> }, timestampedLog = { cmd, raw, ms, result, timestamp ->
            db.journal().insert(JournalEntity(timestamp = timestamp, connection = driver.label,
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
                connectedSettings = cfg
                _state.value = _state.value.copy(adapterConnected = client.adapterReady, carConnected = client.vehicleReady,
                    protocol = client.protocol, adapterName = cfg.type, isBusy = false, error = client.carError)
            } catch (error: Throwable) {
                client.close(); engine = null; connectedSettings = null
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(adapterConnected = client.adapterReady, carConnected = false,
                    protocol = "Не определён", isBusy = false,
                    error = "Подключение: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }
    fun disconnect() {
        activeJob?.cancel()
        engine?.close(); engine = null; connectedSettings = null
        _state.value = _state.value.copy(adapterConnected = false, carConnected = false,
            isBusy = false, isScanning = false, protocol = "Не определён", current = "")
    }
    fun scan() {
        if (activeJob?.isActive == true) return
        val initialClient = engine ?: run { _state.value = _state.value.copy(error = "Подключите адаптер"); return }
        if (!initialClient.vehicleReady) { _state.value = _state.value.copy(error = "Связь с автомобилем не подтверждена"); return }
        activeJob = viewModelScope.launch {
            // Snapshot settings: do not switch transports/host halfway through a scan.
            // Reconnect with the settings that opened this socket, not unsaved edits.
            val cfg = connectedSettings ?: _settings.value
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
                },
                onEvent = { event, message ->
                    db.journal().insert(JournalEntity(
                        timestamp = System.currentTimeMillis(), connection = cfg.type,
                        command = event, raw = message, elapsedMs = 0, outcome = "SESSION"
                    ))
                },
                transportTag = when (cfg.type) {
                    "Wi-Fi" -> "WIFI"
                    "Bluetooth Classic" -> "BT_CLASSIC"
                    "BLE / NUS" -> "BLE_NUS"
                    else -> "ELM"
                }
            )
            suspend fun readOnlyCommand(command: String, timeoutMs: Long): String =
                session.read(command, timeoutMs)
            scanStartedAt = System.currentTimeMillis()
            var interrupted = false
            var incompleteModes = false
            val scanWarnings = mutableListOf<String>()
            var rowId = 0L
            suspend fun checkpoint(current: List<ControlUnit>, modes: Int, incomplete: Boolean) {
                val record = ScanEntity(id = rowId, started = scanStartedAt, finished = System.currentTimeMillis(),
                    interrupted = incomplete, completedModes = modes, json = ScanCodec.encode(current))
                if (rowId == 0L) rowId = db.scans().insert(record)
                else db.scans().update(record)
            }
            _state.value = _state.value.copy(isScanning = true, isBusy = true, units = emptyList(),
                vehicleVin = null,
                current = "Обнаружение ответчиков OBD-II", error = "", scanWarning = "", isoTpReadiness = "",
                completedModes = 0, lastScanInterrupted = false)
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
                for ((index, kind) in DtcKind.entries.filter { it != DtcKind.OEM }.withIndex()) {
                    _state.value = _state.value.copy(current = "OBD-II: ${kind.title.lowercase()} память DTC")
                    val answer = readOnlyCommand(kind.command, 8500)
                    val frames = ElmFrames.parse(kind.command, answer)
                    val assessment = ObdDecoder.assessMode(
                        frames = frames, kind = kind,
                        isCan = session.current.isCanVehicle,
                        expectedEcus = units.keys.toSet()
                    )
                    if (!assessment.complete) {
                        incompleteModes = true
                        val warning = "${kind.command}: " + assessment.problems.take(4).joinToString("; ")
                        scanWarnings += warning
                        _state.value = _state.value.copy(scanWarning = scanWarnings.joinToString("\n"))
                    }
                    // Keep the parser decision with the same raw journal, so a
                    // real test reveals why an ECU/mode was accepted or rejected.
                    val summary = buildString {
                        append("OBD mode ${kind.command}; verified ECU replies: ")
                        append(assessment.confirmed.size)
                        append("; DTCs: ")
                        append(assessment.confirmed.values.sumOf { it.size })
                        if (assessment.problems.isNotEmpty()) {
                            append("; warnings: ")
                            append(assessment.problems.joinToString(" | "))
                        }
                    }
                    runCatching {
                        db.journal().insert(JournalEntity(
                            timestamp = System.currentTimeMillis(),
                            connection = cfg.type,
                            command = "DTC_MODE_${kind.command}",
                            raw = summary,
                            elapsedMs = 0,
                            outcome = if (assessment.complete) "PARSED" else "INCOMPLETE"
                        ))
                    }
                    assessment.confirmed.forEach { (address, dtcs) ->
                        val old = units[address] ?: ControlUnit(
                            address, "OBD-II ответчик $address", ReadState.NOT_QUERIED
                        )
                        units[address] = old.copy(
                            dtcs = (old.dtcs + dtcs).distinctBy { it.code to it.kind },
                            answeredModes = old.answeredModes + kind
                        )
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
                    checkpoint(units.values.toList(), index + 1, index != 2 || incompleteModes)
                }
                // Non-proprietary Mode09 readiness check. A missing VIN PID or a
                // fragmented reply NEVER rewrites the earlier DTC results.
                if (session.current.isCanVehicle == true) {
                    _state.value = _state.value.copy(current = "OBD-II: проверка ISO-TP (Mode 09)")
                    try {
                        val supported = ObdMode09Readiness.supportedVinEcus(
                            ElmFrames.parse("0900", readOnlyCommand("0900", 9500)),
                            units.keys.toSet()
                        )
                        var vinResponse: com.vagdiagnostic.app.core.FrameParse? = null
                        val check = if (supported.responders.isNotEmpty()) {
                            val frames = ElmFrames.parse("0902", readOnlyCommand("0902", 13500))
                            vinResponse = frames
                            _state.value = _state.value.copy(vehicleVin = ObdMode09Readiness.vinForDisplay(frames, supported.responders))
                            ObdMode09Readiness.assessVin(frames, supported.responders)
                        } else null
                        val resultText = if (check == null) {
                            "Mode 09 PID02 не подтверждён; многофреймовый ответ не проверен"
                        } else if (check.confirmed) {
                            "Mode 09: полный ответ подтверждён у ${check.verified.size}/${check.supported.size} ЭБУ"
                        } else "Mode 09: полный ответ не подтверждён"
                        val details = supported.issues + check?.issues.orEmpty()
                        _state.value = _state.value.copy(isoTpReadiness = resultText)
                        db.journal().insert(JournalEntity(
                            timestamp = System.currentTimeMillis(), connection = cfg.type,
                            command = "OBD09_ISOTP_READINESS",
                            raw = resultText + "; " + details.joinToString(" | "),
                            elapsedMs = 0,
                            outcome = if (check?.confirmed == true) "CONFIRMED" else "INCONCLUSIVE"
                        ))
                        // Only the KNOWN legislated OBD physical pair 7E0->7E8.
                        // No VAG Gateway address is inferred. Probe all transports
                        // with the same code and a disposable, isolated ELM link.
                        val confirmedPhysicalEcu = check?.verified?.firstOrNull { id ->
                            Regex("^7E[8-F]$").matches(id) &&
                                id in session.current.observedCanResponderIds
                        }
                        // Only a structurally validated VIN contributes the
                        // two-character chassis marker. Full VIN is display-only;
                        // never copy it to summary events or saved scan results.
                        // Other recognized VAG chassis require a genuine list;
                        // they never inherit the legacy candidate queue.
                        val tp20Profile = confirmedPhysicalEcu?.let { ecu ->
                            vinResponse?.let { VagTp20Gateway.profileFromVerifiedMode09(it, ecu, supported.responders) }
                        }
                        if (confirmedPhysicalEcu != null &&
                            session.current.protocol in setOf("6", "A6")
                        ) {
                            _state.value = _state.value.copy(
                                current = "OBD-II: адресная ISO-TP проверка"
                            )
                            session.close()
                            connectedSettings = null
                            var disposable: ElmEngine? = null
                            try {
                                val diagnosticEngine = createEngine(cfg)
                                disposable = diagnosticEngine
                                engine = diagnosticEngine
                                diagnosticEngine.connect()
                                val addressed = ElmAddressedObdProbe.runOnDisposableEngine(
                                    diagnosticEngine, confirmedPhysicalEcu
                                )
                                _state.value = _state.value.copy(
                                    isoTpReadiness = resultText + "; " +
                                        if (addressed.verified) "адресный OBD ISO-TP подтверждён"
                                        else "адресный OBD ISO-TP не подтверждён"
                                )
                                db.journal().insert(JournalEntity(
                                    timestamp = System.currentTimeMillis(),
                                    connection = cfg.type,
                                    command = "ADDRESSED_OBD_ISOTP",
                                    raw = addressed.journalSummary(), elapsedMs = 0,
                                    outcome = if (addressed.verified) "CONFIRMED"
                                        else "INCONCLUSIVE"
                                ))
                                if (addressed.verified && tp20Profile == null) {
                                    val note = "VAG: подтверждённого платформенного профиля нет; OEM опрос пропущен"
                                    _state.value = _state.value.copy(isoTpReadiness = _state.value.isoTpReadiness + "; " + note)
                                    db.journal().insert(JournalEntity(timestamp = System.currentTimeMillis(), connection = cfg.type,
                                        command = "VAG_SCAN_SKIPPED", raw = note, elapsedMs = 0, outcome = "UNSUPPORTED"))
                                }
                                if (addressed.verified && tp20Profile != null) {
                                    _state.value = _state.value.copy(
                                        current = "VAG: подключение, список блоков и чтение DTC"
                                    )
                                    try {
                                        db.journal().insert(JournalEntity(timestamp = System.currentTimeMillis(), connection = cfg.type,
                                            command = "VAG_PLATFORM_PROFILE", raw = "${tp20Profile.brand}/${tp20Profile.chassis}; ${tp20Profile.source}; кандидат, применимость проверяется ответами Gateway",
                                            elapsedMs = 0, outcome = "SELECTED"))
                                        val gateway = ElmVagScan.scan(diagnosticEngine,
                                            onUnit = { unit ->
                                                units[unit.address] = unit
                                                _state.value = _state.value.copy(units = units.values.toList(), current = unit.name)
                                                checkpoint(units.values.toList(), 3, true)
                                            },
                                            event = { command, detail, outcome ->
                                                if (command == "VAG_GATEWAY_IDENTITY_BEGIN")
                                                    _state.value = _state.value.copy(current = "VAG: идентификация Gateway")
                                                db.journal().insert(JournalEntity(
                                                    timestamp = System.currentTimeMillis(), connection = cfg.type,
                                                    command = command, raw = detail, elapsedMs = 0, outcome = outcome))
                                            }, allowCandidateFallback = tp20Profile.allowCandidateFallback,
                                            readGatewayIdentity = true,
                                            recreateEngine = { createEngine(cfg).also { replacement ->
                                                disposable = replacement
                                                engine = replacement
                                            } })
                                        if (!gateway.complete) incompleteModes = true
                                        _state.value = _state.value.copy(
                                            isoTpReadiness = _state.value.isoTpReadiness + "; " + gateway.note)
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (problem: Exception) {
                                        incompleteModes = true
                                        val description = "VAG: " +
                                            (problem.message ?: problem::class.java.simpleName)
                                        _state.value = _state.value.copy(
                                            isoTpReadiness = _state.value.isoTpReadiness +
                                                "; OEM опрос прерван: " + (problem.message ?: "ошибка обмена")
                                        )
                                        db.journal().insert(JournalEntity(
                                            timestamp = System.currentTimeMillis(),
                                            connection = cfg.type,
                                            command = "VAG_SCAN_INCOMPLETE",
                                            raw = description, elapsedMs = 0,
                                            outcome = "INCONCLUSIVE"
                                        ))
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (problem: Exception) {
                                val note = "Адресная OBD-проверка: " +
                                    (problem.message ?: problem::class.java.simpleName)
                                _state.value = _state.value.copy(
                                    isoTpReadiness = resultText + "; " + note
                                )
                                db.journal().insert(JournalEntity(
                                    timestamp = System.currentTimeMillis(),
                                    connection = cfg.type, command = "ADDRESSED_OBD_ISOTP",
                                    raw = note, elapsedMs = 0, outcome = "INCONCLUSIVE"
                                ))
                            } finally {
                                disposable?.close()
                                if (engine === disposable) engine = null
                                // Never reconnect if the user pressed Stop.
                                if (currentCoroutineContext().isActive) {
                                    val restored = createEngine(cfg)
                                    engine = restored
                                    try {
                                        restored.connect()
                                        if (!restored.vehicleReady) throw java.io.IOException(restored.carError)
                                        connectedSettings = cfg
                                        _state.value = _state.value.copy(
                                            adapterConnected = restored.adapterReady,
                                            carConnected = restored.vehicleReady,
                                            protocol = restored.protocol, current = ""
                                        )
                                    } catch (problem: Exception) {
                                        restored.close()
                                        engine = null
                                        connectedSettings = null
                                        _state.value = _state.value.copy(
                                            adapterConnected = false, carConnected = false,
                                            protocol = "Не определён",
                                            error = "Штатное OBD-подключение после проверки не восстановлено: " +
                                                (problem.message ?: problem::class.java.simpleName)
                                        )
                                    }
                                }
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        // A capability check is not a DTC read failure; keep all
                        // preceding confirmed modes and log only the probe issue.
                        val note = "Mode 09: проверка не завершена — " +
                            (error.message ?: error::class.java.simpleName)
                        _state.value = _state.value.copy(isoTpReadiness = note)
                        db.journal().insert(JournalEntity(
                            timestamp = System.currentTimeMillis(), connection = cfg.type,
                            command = "OBD09_ISOTP_READINESS", raw = note,
                            elapsedMs = 0, outcome = "INCONCLUSIVE"
                        ))
                        if (error is java.io.IOException) {
                            session.close()
                            connectedSettings = null
                        }
                    }
                } else {
                    _state.value = _state.value.copy(
                        isoTpReadiness = "Mode 09 ISO-TP: CAN не подтверждён; проверка пропущена"
                    )
                }
            } catch (error: CancellationException) {
                interrupted = true; throw error
            } catch (error: Throwable) {
                interrupted = true
                val linkLost = error is java.io.IOException || engine == null ||
                    session.hasReactiveRecovery
                if (linkLost) { session.close(); connectedSettings = null }
                _state.value = _state.value.copy(error =
                    "Диагностика: ${error.message ?: error::class.java.simpleName}" +
                    if (linkLost) ". Связь потеряна; подключитесь снова." else "")
            } finally {
                _state.value = _state.value.copy(isScanning = false, isBusy = false,
                    current = "", units = units.values.toList(), lastScanInterrupted = interrupted || incompleteModes)
                // Non-cancellable persistence: partial scans survive Stop and disconnections.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        checkpoint(units.values.toList(), _state.value.completedModes, interrupted || incompleteModes)
                    }
                }
            }
        }
    }
    fun stopScan() {
        activeJob?.cancel()
        // Abort the current transport command: otherwise the next read might see a stale prompt.
        engine?.close(); engine = null; connectedSettings = null
        _state.value = _state.value.copy(adapterConnected = false, carConnected = false,
            protocol = "Не определён", error = "Сканирование остановлено, соединение закрыто для синхронизации")
    }
    fun useHistory(scan: ScanEntity) = runCatching {
        _state.value = _state.value.copy(units = ScanCodec.decode(scan.json), isScanning = false,
            completedModes = scan.completedModes, lastScanInterrupted = scan.interrupted,
            current = "", error = "", scanWarning = "", isoTpReadiness = "")
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
        val content = StringBuilder("VAG Diagnostic ${BuildConfig.VERSION_NAME} / Сырой журнал (VIN замаскирован)\n")
        val entries = db.journal().latest(10000).reversed()
        entries.forEach { e ->
            content.append(DiagnosticLogRedactor.redact("${e.timestamp} | ${e.connection} | ${e.command} | ${e.elapsedMs}ms | ${e.outcome}")).append("\n")
            content.append(DiagnosticLogRedactor.redact(e.raw, e.command)).append("\n")
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
