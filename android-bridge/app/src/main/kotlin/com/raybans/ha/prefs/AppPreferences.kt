package com.raybans.ha.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_HA_URL = stringPreferencesKey("ha_url")
        private val KEY_HA_TOKEN = stringPreferencesKey("ha_token")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_MJPEG_PORT = intPreferencesKey("mjpeg_port")
        private val KEY_CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        private val KEY_MCP_URL = stringPreferencesKey("mcp_url")

        const val DEFAULT_MJPEG_PORT = 8080
    }

    val haUrl: Flow<String> = context.dataStore.data.map { it[KEY_HA_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { it[KEY_HA_TOKEN] ?: "" }
    val deviceId: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_ID] ?: "raybans" }
    val mjpegPort: Flow<Int> = context.dataStore.data.map { it[KEY_MJPEG_PORT] ?: DEFAULT_MJPEG_PORT }
    val claudeApiKey: Flow<String> = context.dataStore.data.map { it[KEY_CLAUDE_API_KEY] ?: "" }
    val mcpUrl: Flow<String> = context.dataStore.data.map { it[KEY_MCP_URL] ?: "" }

    suspend fun save(
        haUrl: String,
        haToken: String,
        deviceId: String,
        mjpegPort: Int,
        claudeApiKey: String,
        mcpUrl: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_URL] = haUrl
            prefs[KEY_HA_TOKEN] = haToken
            prefs[KEY_DEVICE_ID] = deviceId
            prefs[KEY_MJPEG_PORT] = mjpegPort
            prefs[KEY_CLAUDE_API_KEY] = claudeApiKey
            prefs[KEY_MCP_URL] = mcpUrl
        }
    }
}
