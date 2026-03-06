package com.raybans.ha.glasses

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "VoiceCapture"

/**
 * Collects microphone audio from [MetaGlassesManager] and detects speech
 * using a simple energy-based Voice Activity Detector (VAD).
 *
 * When speech energy is detected above [VAD_THRESHOLD], [onSpeechDetected]
 * is called with the accumulated PCM buffer. The Assist pipeline client
 * then streams that buffer (and subsequent frames) to HA.
 *
 * Phase 2 upgrade path: replace energy VAD with Silero VAD or
 * openWakeWord for wake-word activation ("Hey Home Assistant").
 */
class VoiceCapture(
    private val manager: MetaGlassesManager,
    private val scope: CoroutineScope,
) {
    /** Called when a speech segment starts. Receives the triggering audio frame. */
    var onSpeechDetected: ((ByteArray) -> Unit)? = null

    private var captureJob: Job? = null

    /** True while VAD considers speech to be ongoing. */
    var isSpeaking = false
        private set

    fun start() {
        captureJob = scope.launch {
            manager.getMicAudioStream().collect { pcmFrame ->
                val energy = computeEnergy(pcmFrame)
                val speechNow = energy > VAD_THRESHOLD

                if (speechNow && !isSpeaking) {
                    Log.d(TAG, "Speech detected (energy=$energy)")
                    isSpeaking = true
                    onSpeechDetected?.invoke(pcmFrame)
                } else if (!speechNow && isSpeaking) {
                    Log.d(TAG, "Speech ended")
                    isSpeaking = false
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        isSpeaking = false
    }

    // --- Simple energy VAD ---

    private fun computeEnergy(pcm: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            // 16-bit LE sample
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += abs(sample.toShort().toInt())
            i += 2
        }
        return if (pcm.size > 0) (sum / (pcm.size / 2)).toInt() else 0
    }

    companion object {
        // Tunable: ~500 for quiet environments; raise if false-triggers on ambient noise
        private const val VAD_THRESHOLD = 500
    }
}
