package com.vagdiagnostic.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "connections")
data class ConnectionSettings(val type: String = "Wi-Fi", val host: String = "192.168.0.10", val port: Int = 35000, val mac: String = "")
class Settings(private val context: Context) {
    private val typeKey = stringPreferencesKey("transport")
    private val hostKey = stringPreferencesKey("wifi_host")
    private val portKey = intPreferencesKey("wifi_port")
    private val macKey = stringPreferencesKey("bt_mac")
    val values: Flow<ConnectionSettings> = context.store.data.map { data ->
        ConnectionSettings(data[typeKey] ?: "Wi-Fi", data[hostKey] ?: "192.168.0.10", data[portKey] ?: 35000, data[macKey] ?: "")
    }
    suspend fun update(s: ConnectionSettings) = context.store.edit { data ->
        data[typeKey] = s.type; data[hostKey] = s.host; data[portKey] = s.port; data[macKey] = s.mac
    }
}