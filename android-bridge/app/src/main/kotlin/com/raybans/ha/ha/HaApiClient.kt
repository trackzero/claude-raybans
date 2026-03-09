package com.raybans.ha.ha

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

private const val TAG = "HaApiClient"
private const val EVENT_SENSOR = "raybans_meta_sensor"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp REST client that pushes sensor state to HA.
 *
 * Fires HA events via `POST /api/events/raybans_meta_sensor`.
 * The HA custom component subscribes to this event and calls
 * async_write_ha_state() on the matching entity — avoiding any
 * dependency on auto-generated entity IDs.
 *
 * All methods are synchronous — call from Dispatchers.IO.
 */
class HaApiClient(
    private val haUrl: String,
    private val haToken: String,
    private val deviceId: String,
) {
    private val client = OkHttpClient()

    fun pushBattery(level: Int) {
        fireEvent(JSONObject().apply {
            put("device_id", deviceId)
            put("type", "battery")
            put("value", level)
        })
    }

    fun pushWorn(worn: Boolean) {
        fireEvent(JSONObject().apply {
            put("device_id", deviceId)
            put("type", "worn")
            put("value", if (worn) "on" else "off")
        })
    }

    fun pushConnected(connected: Boolean) {
        fireEvent(JSONObject().apply {
            put("device_id", deviceId)
            put("type", "connected")
            put("value", if (connected) "on" else "off")
        })
    }

    private fun fireEvent(body: JSONObject) {
        val url = "${haUrl.trimEnd('/')}/api/events/$EVENT_SENSOR"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $haToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Fired $EVENT_SENSOR: ${body.optString("type")}=${body.opt("value")}")
                } else {
                    Log.w(TAG, "Failed to fire event: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error firing event: ${e.message}")
        }
    }
}
