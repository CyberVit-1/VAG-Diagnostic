package com.vagdiagnostic.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vagdiagnostic.app.ui.VagDiagnosticApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var documentText: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val payload = documentText
        if (uri != null && payload != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }
        documentText = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VagDiagnosticApp(
                vm = vm,
                requestPermissions = { requestBluetoothPermissions() },
                search = { code ->
                    if (!vm.internetAvailable()) "Поиск недоступен: нет подключения к сети"
                    else try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(vm.yandexUrl(code))))
                        null
                    } catch (_: ActivityNotFoundException) { "Не найден браузер" }
                },
                exportLogs = {
                    lifecycleScope.launch {
                        documentText = vm.exportRedactedLogs()
                        export.launch("vag-diagnostic-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
                    }
                }
            )
        }
    }
    private fun requestBluetoothPermissions() {
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}