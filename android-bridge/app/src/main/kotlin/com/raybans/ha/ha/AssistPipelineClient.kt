package com.raybans.ha.ha

import android.util.Log
import com.raybans.ha.glasses.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.Base64

private const val TAG = "AssistPipelineClient"

/**
 * Streams mic audio to HA's Assist pipeline over the existing WebSocket connection.
 *
 * Protocol:
 *  1. Send `assist_pipeline/run` message → HA starts STT stage
 *  2. Send binary WebSocket frames containing raw PCM audio (16-bit LE, 16 kHz, mono)
 *  3. Send end-of-audio signal (`assist_pipeline/run_end`)
 *  4. HA processes STT → intent → TTS, returns TTS audio in result event
 *  5. Audio bytes passed to [TtsPlayer.playPcmAudio]
 *
 * The pipeline ID is optional; HA uses the default pipeline if omitted.
 *
 * See: https://developers.home-assistant.io/docs/api/websocket/#assist-pipeline
 */
class AssistPipelineClient(
    private val wsClient: HaWebSocketClient,
    private val ttsPlayer: TtsPlayer,
    private val scope: CoroutineScope,
) {
    private var activeMsgId: Int? = null
    private var audioJob: Job? = null

    /**
     * Start an Assist pipeline session, streaming [audioFrames] as PCM.
     *
     * @param audioFrames sequence of raw PCM frames from the glasses mic
     * @param sampleRate  sample rate of the PCM audio (default 16000 Hz)
     */
    fun startSession(audioFrames: Sequence<ByteArray>, sampleRate: Int = 16_000) {
        if (activeMsgId != null) {
            Log.w(TAG, "Session already active — ignoring")
            return
        }

        val runMsg = JSONObject().apply {
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            put("input", JSONObject().apply {
                put("sample_rate", sampleRate)
            })
        }

        val msgId = wsClient.sendMessage(runMsg)
        activeMsgId = msgId
        Log.d(TAG, "Assist pipeline started, msg_id=$msgId")

        // Stream audio frames asynchronously
        audioJob = scope.launch(Dispatchers.IO) {
            for (frame in audioFrames) {
                // HA expects binary WS frames with the stt_input event wrapping the audio
                val audioMsg = JSONObject().apply {
                    put("type", "assist_pipeline/stt_input")
                    put("id", msgId)
                    // PCM bytes base64-encoded in JSON (binary WS frames also accepted)
                    put("data", Base64.getEncoder().encodeToString(frame))
                }
                wsClient.sendMessage(audioMsg)
            }

            // Signal end of audio
            val endMsg = JSONObject().apply {
                put("type", "assist_pipeline/stt_end")
                put("id", msgId)
            }
            wsClient.sendMessage(endMsg)
            Log.d(TAG, "Audio stream ended")
        }
    }

    /**
     * Handle a WS result/event message from HA.
     * Call this from [HaWebSocketClient]'s message handler.
     */
    fun handleMessage(msg: JSONObject) {
        val id = msg.optInt("id")
        if (id != activeMsgId) return

        when (msg.optString("type")) {
            "result" -> {
                if (!msg.optBoolean("success")) {
                    Log.e(TAG, "Pipeline failed: ${msg.optJSONObject("error")}")
                    endSession()
                }
            }
            "event" -> {
                val event = msg.optJSONObject("event") ?: return
                when (event.optString("type")) {
                    "run-end" -> endSession()
                    "tts-end" -> {
                        // TTS audio URL or base64 in event data
                        val ttsOutput = event.optJSONObject("data")
                            ?.optString("tts_output") ?: return
                        Log.d(TAG, "TTS output received, length=${ttsOutput.length}")
                        try {
                            val pcm = Base64.getDecoder().decode(ttsOutput)
                            scope.launch(Dispatchers.IO) { ttsPlayer.playPcmAudio(pcm) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decode TTS audio", e)
                        }
                    }
                }
            }
        }
    }

    private fun endSession() {
        audioJob?.cancel()
        audioJob = null
        activeMsgId = null
        Log.d(TAG, "Assist session ended")
    }
}
