package com.raybans.ha.ha

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

private const val TAG = "HaApiClient"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp REST client that pushes entity state updates to HA.
 *
 * Android is the source of truth for glasses state; HA entities are
 * kept in sync by POSTing to `/api/states/{entity_id}`.
 *
 * All methods are synchronous — call from a background thread / coroutine
 * with Dispatchers.IO.
 *
 * Entity ID pattern: `{domain}.raybans_{suffix}_{deviceId}`
 *   sensor.raybans_battery_raybans
 *   binary_sensor.raybans_worn_raybans
 *   binary_sensor.raybans_connected_raybans
 */
class HaApiClient(
    private val haUrl: String,
    private val haToken: String,
    private val deviceId: String,
) {
    private val client = OkHttpClient()

    fun pushBattery(level: Int) {
        val entityId = "sensor.raybans_battery_$deviceId"
        val body = JSONObject().apply {
            put("state", level)
            put("attributes", JSONObject().apply {
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("friendly_name", "Ray-Ban Meta Battery")
            })
        }
        postState(entityId, body)
    }

    fun pushWorn(worn: Boolean) {
        val entityId = "binary_sensor.raybans_worn_$deviceId"
        val body = JSONObject().apply {
            put("state", if (worn) "on" else "off")
            put("attributes", JSONObject().apply {
                put("device_class", "occupancy")
                put("friendly_name", "Ray-Ban Meta Worn")
            })
        }
        postState(entityId, body)
    }

    fun pushConnected(connected: Boolean) {
        val entityId = "binary_sensor.raybans_connected_$deviceId"
        val body = JSONObject().apply {
            put("state", if (connected) "on" else "off")
            put("attributes", JSONObject().apply {
                put("device_class", "connectivity")
                put("friendly_name", "Ray-Ban Meta Connected")
            })
        }
        postState(entityId, body)
    }

    private fun postState(entityId: String, body: JSONObject) {
        val url = "${haUrl.trimEnd('/')}/api/states/$entityId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $haToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Pushed $entityId → ${body.optString("state")}")
                } else {
                    Log.w(TAG, "Failed to push $entityId: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error pushing $entityId: ${e.message}")
        }
    }
}
