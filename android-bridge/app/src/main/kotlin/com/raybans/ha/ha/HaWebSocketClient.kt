package com.raybans.ha.ha

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

private const val TAG = "HaWebSocketClient"
private const val EVENT_NOTIFY = "raybans_meta_notify"

/**
 * Persistent OkHttp WebSocket connection to HA's WebSocket API.
 *
 * Lifecycle:
 *  1. [connect] — opens WS to `{haUrl}/api/websocket`
 *  2. HA sends `auth_required` → client sends auth message with [haToken]
 *  3. After `auth_ok`, subscribes to [EVENT_NOTIFY] events
 *  4. On `raybans_meta_notify` event, calls [onNotifyEvent] with the text payload
 *  5. [disconnect] — closes cleanly
 *  6. Auto-reconnects on failure with exponential backoff (1s … 60s)
 *
 * The same WS connection is re-used by [AssistPipelineClient] for streaming
 * audio messages to `assist_pipeline/run`.
 */
class HaWebSocketClient(
    private val haUrl: String,
    private val haToken: String,
    val onNotifyEvent: (text: String, deviceId: String) -> Unit,
) {
    private val msgId = AtomicInteger(1)
    private var ws: WebSocket? = null
    private var subscriptionId: Int? = null
    private var reconnectDelayMs = 1_000L
    private var shouldReconnect = true

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        shouldReconnect = true
        openSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        ws?.close(1000, "Service stopping")
        ws = null
    }

    /**
     * Send an arbitrary JSON message on the WebSocket.
     * Returns the message ID that was used (for correlation with responses).
     */
    fun sendMessage(payload: JSONObject): Int {
        val id = msgId.getAndIncrement()
        payload.put("id", id)
        ws?.send(payload.toString()) ?: Log.w(TAG, "WS not connected — message dropped")
        return id
    }

    private fun openSocket() {
        val wsUrl = haUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/api/websocket"

        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, listener)
        Log.d(TAG, "Opening WS to $wsUrl")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS connected")
            reconnectDelayMs = 1_000L
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(JSONObject(text))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse WS message: $text", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $code $reason")
            if (shouldReconnect) scheduleReconnect()
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "auth_required" -> authenticate()
            "auth_ok" -> subscribeToNotifyEvents()
            "auth_invalid" -> Log.e(TAG, "Auth failed — check your long-lived token")
            "event" -> handleEvent(msg)
            "result" -> Log.d(TAG, "Result for id=${msg.optInt("id")}: success=${msg.optBoolean("success")}")
        }
    }

    private fun authenticate() {
        val auth = JSONObject().apply {
            put("type", "auth")
            put("access_token", haToken)
        }
        ws?.send(auth.toString())
        Log.d(TAG, "Sent auth message")
    }

    private fun subscribeToNotifyEvents() {
        Log.i(TAG, "Authenticated; subscribing to $EVENT_NOTIFY events")
        val sub = JSONObject().apply {
            put("type", "subscribe_events")
            put("event_type", EVENT_NOTIFY)
        }
        subscriptionId = sendMessage(sub)
    }

    private fun handleEvent(msg: JSONObject) {
        val eventData = msg.optJSONObject("event")?.optJSONObject("data") ?: return
        val text = eventData.optString("text")
        val deviceId = eventData.optString("device_id")
        if (text.isNotEmpty()) {
            Log.d(TAG, "Notify event: text=$text device_id=$deviceId")
            onNotifyEvent(text, deviceId)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        Log.i(TAG, "Reconnecting in ${reconnectDelayMs}ms…")
        Thread.sleep(reconnectDelayMs)
        reconnectDelayMs = min(reconnectDelayMs * 2, 60_000L)
        openSocket()
    }
}
