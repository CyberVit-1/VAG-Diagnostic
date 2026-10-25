package com.vagdiagnostic.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vagdiagnostic.app.MainViewModel
import com.vagdiagnostic.app.DashboardState
import com.vagdiagnostic.app.core.ElmProtocolDescription
import com.vagdiagnostic.app.core.ControlUnit
import com.vagdiagnostic.app.core.DtcKind
import com.vagdiagnostic.app.core.ReadState
import com.vagdiagnostic.app.data.ConnectionSettings
import com.vagdiagnostic.app.data.ScanCodec
import com.vagdiagnostic.app.transport.pairedClassic
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val pageLabels = listOf("Главная", "Подключение", "Диагностика", "Блоки", "История", "Настройки")
private val navItems = listOf(0, 1, 2, 4, 5)

@Composable
fun VagDiagnosticApp(vm: MainViewModel, requestPermissions: () -> Unit, search: (String) -> String?, exportLogs: () -> Unit) {
    var darkTheme by rememberSaveable { mutableStateOf(true) }
    val colors = if (darkTheme) darkColorScheme(primary = Color(0xFF93C5FD), secondary = Color(0xFFA5B4FC),
        background = Color(0xFF0B1324), surface = Color(0xFF15213A), surfaceVariant = Color(0xFF22314F))
        else lightColorScheme(primary = Color(0xFF245DA8), secondary = Color(0xFF334C84),
            background = Color(0xFFF4F7FD), surface = Color.White, surfaceVariant = Color(0xFFE4EBF7))
    MaterialTheme(colorScheme = colors, shapes = Shapes(large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(30.dp))) {
        val status by vm.state.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val history by vm.history.collectAsState(initial = emptyList())
        val toast = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var page by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            snackbarHost = { SnackbarHost(toast) },
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VAG DIAGNOSTIC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(pageLabels[page], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = RoundedCornerShape(50), color = if (status.carConnected) Color(0xFF164D3B) else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(if (status.carConnected) "Автомобиль на связи" else "Не подключён", Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelSmall, color = if (status.carConnected) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    navItems.forEach { dest ->
                        val (icon, label) = when (dest) {
                            0 -> Icons.Default.Home to "Главная"
                            1 -> Icons.Default.Wifi to "Связь"
                            2 -> Icons.Default.DirectionsCar to "Диагностика"
                            4 -> Icons.Default.History to "История"
                            else -> Icons.Default.Settings to "Настройки"
                        }
                        NavigationBarItem(selected = page == dest, onClick = { page = dest }, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) })
                    }
                }
            }
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                if (status.error.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(status.error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (page) {
                    0 -> Home(status, navigate = { page = it }, start = { vm.scan(); page = 2 })
                    1 -> Connection(status, settings, set = vm::saveSettings, connect = vm::connect,
                        disconnect = vm::disconnect, permission = requestPermissions,
                        bleDiscovery = vm::discoverBle)
                    2 -> Diagnostics(status, start = vm::scan, stop = vm::stopScan, search = { code ->
                        search(code)?.let { scope.launch { toast.showSnackbar(it) } }
                    })
                    3 -> UnitList(status, search = { code ->
                        search(code)?.let { scope.launch { toast.showSnackbar(it) } }
                    })
                    4 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (history.isEmpty()) item { Text("История пока пуста. Запустите диагностику.") }
                        items(history, key = { it.id }) { scan ->
                            ElevatedCard(onClick = { vm.useHistory(scan); page = 3 }) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(DateFormat.getDateTimeInstance().format(Date(scan.started)), fontWeight = FontWeight.Bold)
                                    val decoded = remember(scan.json) { runCatching { ScanCodec.decode(scan.json) }.getOrDefault(emptyList()) }
                                    Text("OBD-ответчиков: ${decoded.size} • DTC: ${decoded.sumOf { it.dtcs.size }}")
                                    Text(if (scan.interrupted) "Прервано / неполные данные" else "Опрос завершён", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    else -> SettingsScreen(darkTheme, onTheme = { darkTheme = it }, export = exportLogs)
                }
            }
        }
    }
}

@Composable private fun Home(status: DashboardState, navigate: (Int) -> Unit, start: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Диагностика без догадок", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Обнаружение OBD-II ответчиков и чтение DTC. Полный VAG Auto-Scan пока недоступен.")
                Button(onClick = start, enabled = status.carConnected && !status.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Начать диагностику")
                }
                if (!status.carConnected) Text("Сначала подключите адаптер и подтвердите связь с автомобилем.", style = MaterialTheme.typography.bodySmall)
            }
        }
        val tiles = listOf(
            Triple("Подключение", "Wi-Fi / Bluetooth Classic / BLE NUS", 1),
            Triple("Диагностика", "Чтение DTC 03 / 07 / 0A", 2),
            Triple("Блоки управления", "Обнаруженные OBD-II ответчики", 3),
            Triple("История диагностики", "Сохранённые результаты", 4),
            Triple("Настройки", "Темы и экспорт журналов", 5)
        )
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (name, sub, dest) ->
                    ElevatedCard(onClick = { navigate(dest) }, modifier = Modifier.weight(1f).height(142.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun Connection(status: DashboardState, s: ConnectionSettings,
                                    set: (ConnectionSettings) -> Unit, connect: () -> Unit,
                                    disconnect: () -> Unit, permission: () -> Unit, bleDiscovery: () -> Unit) {
    val context = LocalContext.current
    val paired = remember(status.adapterConnected) { pairedClassic(context) }
    var portText by rememberSaveable { mutableStateOf(s.port.toString()) }
    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } ?: false
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Состояние соединения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Адаптер: ${if (status.adapterConnected) "Подключён" else "Нет соединения"}")
                Text("Автомобиль: ${if (status.carConnected) "OBD-II связь подтверждена" else "Не подтверждена"}")
                Text("Протокол: ${ElmProtocolDescription.describe(status.protocol)}")
            }
        }
        Text("Тип адаптера", fontWeight = FontWeight.SemiBold)
        listOf("Wi-Fi", "Bluetooth Classic", "BLE / NUS").forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(s.copy(type = type)) }) {
                RadioButton(selected = s.type == type, onClick = { set(s.copy(type = type)) }); Text(type)
            }
        }
        if (s.type == "Wi-Fi") {
            OutlinedTextField(s.host, { set(s.copy(host = it)) }, label = { Text("IP-адрес адаптера") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(portText, { raw -> portText = raw; raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { set(s.copy(port = it)) } },
                label = { Text("TCP-порт") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = !portValid)
            Text("Подключите телефон к Wi-Fi адаптера. Интернет через эту сеть не требуется.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(s.mac, { set(s.copy(mac = it)) }, label = { Text("MAC-адрес устройства") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = permission, modifier = Modifier.fillMaxWidth()) { Text("Разрешения Bluetooth") }
            if (s.type == "Bluetooth Classic") {
                Text("Сопряжённые устройства", fontWeight = FontWeight.SemiBold)
                paired.forEach { (name, mac) ->
                    OutlinedCard(onClick = { set(s.copy(mac = mac)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$name — $mac", Modifier.padding(12.dp))
                    }
                }
            } else {
                OutlinedButton(onClick = bleDiscovery) { Text("Найти BLE-устройства (7 сек.)") }
                status.bleFound.forEach { (name, mac) ->
                    OutlinedCard(onClick = { set(s.copy(mac = mac)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$name — $mac", Modifier.padding(12.dp))
                    }
                }
                Text("Поддержан профиль Nordic UART (NUS). Другие GATT-сервисы пока не реализованы.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (status.adapterConnected || status.carConnected) {
            Button(onClick = disconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключить") }
        } else {
            Button(onClick = connect, enabled = !status.isBusy && (s.type != "Wi-Fi" || portValid), modifier = Modifier.fillMaxWidth()) {
                Text(if (status.isBusy) "Соединение..." else "Подключиться к адаптеру")
            }
        }
    }
}

@Composable private fun Diagnostics(status: DashboardState, start: () -> Unit, stop: () -> Unit, search: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Metrics(status)
        if (status.completedModes > 0 && status.units.isNotEmpty()) {
            val counts = DtcKind.entries.joinToString(" · ") { kind ->
                "${kind.command}: ${status.units.count { kind in it.answeredModes }}/${status.units.size}"
            }
            Text("Подтверждённые ответы по режимам — $counts",
                style = MaterialTheme.typography.bodySmall)
        }
        if (status.isoTpReadiness.isNotBlank()) {
            Text("Проверка ISO-TP: ${status.isoTpReadiness}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status.scanWarning.isNotBlank()) {
            Text("Не подтверждено: ${status.scanWarning}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (status.isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // indeterminate until vehicle ECU count is known
            Text("Сейчас: ${status.current}")
            OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Text("Остановить") }
        } else Button(onClick = start, enabled = status.carConnected && !status.isBusy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, null); Text(if (status.units.isEmpty()) "Начать сканирование" else "Повторить сканирование")
        }
        if (status.lastScanInterrupted) Text("Сканирование прервано или часть режимов не подтвердила ответ. Полученные данные сохранены.", style = MaterialTheme.typography.bodySmall)
        Text("Обнаруженные OBD-II ответчики (не полный список блоков VAG)", style = MaterialTheme.typography.titleSmall)
        if (status.units.isEmpty()) Text("Нет подтверждённых ответчиков. Результаты не подставляются автоматически.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(status.units, key = { it.address }) { unit -> EcuCard(unit, search) }
        }
    }
}

@Composable private fun Metrics(status: DashboardState) {
    ElevatedCard {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Metric("Найдено", status.discovered); Metric("Опрошено", status.polled); Metric("Все режимы", status.successful)
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Metric("Частично", status.partial); Metric("С DTC", status.faulty); Metric("Всего DTC", status.faultCount)
            }
            Text("«Все режимы» — ответы 03, 07 и 0A. «Частично» — подтверждена только часть режимов. Показаны лишь достоверные DTC.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun Metric(name: String, number: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$number", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun UnitList(status: DashboardState, search: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Это подтверждённые OBD-II ответчики, не полный список блоков VAG. Для безопасного чтения Gateway 19 ещё необходимы проверенный профиль CAN-адресации, формат списка установленных блоков и совместимый ISO-TP интерфейс. Пока запросы к шлюзу не отправляются.", style = MaterialTheme.typography.bodySmall) }
        if (status.units.isEmpty()) item { Text("Сначала проведите сканирование") }
        items(status.units, key = { it.address }) { EcuCard(it, search) }
    }
}
@Composable private fun EcuCard(unit: ControlUnit, search: (String) -> Unit) {
    var expanded by rememberSaveable(unit.address) { mutableStateOf(false) }
    val tint = when {
        unit.dtcs.isNotEmpty() -> Color(0xFFE88F91)
        unit.state == ReadState.COMPLETE -> Color(0xFF67C9A8)
        unit.state == ReadState.NOT_QUERIED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color(0xFFFFC56D)
    }
    val statusText = when {
        unit.dtcs.isNotEmpty() -> "Найдены неисправности: ${unit.dtcs.size}"
        unit.state == ReadState.COMPLETE -> "Ошибок нет (OBD-II)"
        unit.state == ReadState.NOT_QUERIED -> "Не опрошен"
        unit.state == ReadState.PARTIAL -> "Неполный опрос"
        else -> "Нет ответа по DTC"
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(250))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${unit.address} — ${unit.name}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(5.dp))
                    Text(statusText, color = tint, style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (expanded) "−" else "+", style = MaterialTheme.typography.headlineSmall)
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 17.dp, end = 17.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    Text(unit.note.ifEmpty { "Ожидание результатов" }, style = MaterialTheme.typography.bodySmall)
                    if (unit.dtcs.isEmpty()) Text("Нет подтверждённых записей DTC", style = MaterialTheme.typography.bodySmall)
                    unit.dtcs.forEach { fault ->
                        OutlinedCard(onClick = { search(fault.code) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${fault.code} — ${fault.description ?: "Описание не найдено"}", fontWeight = FontWeight.SemiBold)
                                Text("Статус: ${fault.kind.title}", style = MaterialTheme.typography.bodySmall)
                                Text("Источник: ${if (fault.description == null) "нет достоверной расшифровки" else "локальный справочник"}", style = MaterialTheme.typography.labelSmall)
                                Text("Поиск в Яндексе ↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun SettingsScreen(dark: Boolean, onTheme: (Boolean) -> Unit, export: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Тёмная тема", fontWeight = FontWeight.Bold); Text("Материальный дизайн Material 3", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = dark, onCheckedChange = onTheme)
            }
        }
        ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Диагностические журналы", fontWeight = FontWeight.Bold)
                Text("AT-команды, сырые ответы, время и ошибки хранятся в Room между запусками. VIN маскируется при экспорте.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = export, modifier = Modifier.fillMaxWidth()) { Text("Экспортировать журнал TXT") }
            }
        }
        Text("Версия 0.2.3 · Только чтение", style = MaterialTheme.typography.bodyMedium)
        Text("OBD/ISO-TP подтверждён на испытанном Wi-Fi ELM. Для проверенного VW 1K выполняется только проба начального канала Gateway 19 через TP 2.0. Полный TP 2.0/KWP2000, список блоков и Auto-Scan ещё не реализованы. Запись и сброс DTC запрещены. BLE: Nordic UART.",
            style = MaterialTheme.typography.bodySmall)
    }
}