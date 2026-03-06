package com.raybans.ha.glasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "TtsPlayer"

/**
 * Routes audio to the Ray-Ban Meta glasses via Bluetooth A2DP.
 *
 * Two modes:
 *
 * 1. **Raw PCM playback** ([playPcmAudio]): used when HA Assist returns
 *    a TTS audio stream as PCM bytes (16-bit LE, 22050 Hz, mono).
 *    Audio is written directly to [AudioTrack] on STREAM_MUSIC so that Android's
 *    Bluetooth routing sends it to the A2DP sink (the glasses).
 *
 * 2. **Android TTS fallback** ([speak]): used when a `raybans_meta_notify`
 *    event arrives with a plain text payload and no pre-rendered audio.
 *    Calls the on-device TTS engine and routes to the same A2DP stream.
 *
 * The glasses appear as an A2DP audio device; Android routes STREAM_MUSIC
 * to the active Bluetooth sink automatically when the glasses are connected.
 */
class TtsPlayer(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // 22050 Hz mono 16-bit PCM — matches typical HA TTS output
    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
                Log.d(TAG, "Android TTS initialized")
            } else {
                Log.e(TAG, "Android TTS initialization failed: $status")
            }
        }
    }

    /**
     * Speak [text] using Android's on-device TTS engine.
     * Audio is sent to STREAM_MUSIC → Bluetooth A2DP → glasses speakers.
     */
    fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        Log.d(TAG, "Speaking via Android TTS: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "raybans_tts")
    }

    /**
     * Play raw PCM audio bytes received from the HA Assist pipeline TTS stage.
     * Runs synchronously — call on a background thread.
     */
    fun playPcmAudio(pcmBytes: ByteArray) {
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufSize, pcmBytes.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmBytes, 0, pcmBytes.size)
        track.play()

        // Block until playback finishes, then release
        track.setNotificationMarkerPosition(pcmBytes.size / 2 /* samples */)
        Thread.sleep((pcmBytes.size.toLong() * 1000L) / (sampleRate * 2))
        track.stop()
        track.release()
        Log.d(TAG, "PCM playback complete (${pcmBytes.size} bytes)")
    }

    fun release() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
