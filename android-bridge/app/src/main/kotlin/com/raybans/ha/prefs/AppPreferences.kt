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

/**
 * Typed DataStore wrapper for all persistent app settings.
 *
 * Keys:
 *   ha_url       – Base URL of the HA instance (e.g. https://example.ui.nabu.casa)
 *   ha_token     – Long-lived access token
 *   device_id    – Unique device label used in entity IDs (e.g. "raybans")
 *   mjpeg_port   – Local HTTP server port for the MJPEG stream (default 8080)
 */
class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_HA_URL = stringPreferencesKey("ha_url")
        private val KEY_HA_TOKEN = stringPreferencesKey("ha_token")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_MJPEG_PORT = intPreferencesKey("mjpeg_port")

        const val DEFAULT_MJPEG_PORT = 8080
    }

    val haUrl: Flow<String> = context.dataStore.data.map { it[KEY_HA_URL] ?: "" }
    val haToken: Flow<String> = context.dataStore.data.map { it[KEY_HA_TOKEN] ?: "" }
    val deviceId: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_ID] ?: "raybans" }
    val mjpegPort: Flow<Int> = context.dataStore.data.map { it[KEY_MJPEG_PORT] ?: DEFAULT_MJPEG_PORT }

    suspend fun save(haUrl: String, haToken: String, deviceId: String, mjpegPort: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_URL] = haUrl
            prefs[KEY_HA_TOKEN] = haToken
            prefs[KEY_DEVICE_ID] = deviceId
            prefs[KEY_MJPEG_PORT] = mjpegPort
        }
    }
}
