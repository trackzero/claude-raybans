package com.raybans.ha.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.facebook.mwdat.WearableDevice
import com.facebook.mwdat.WearableDeviceListener
import com.facebook.mwdat.audio.AudioFrame
import com.facebook.mwdat.audio.MicrophoneListener
import com.facebook.mwdat.camera.CameraFrame
import com.facebook.mwdat.camera.CameraListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "MetaGlassesManager"

/**
 * Wraps the mwdat-core [WearableDevice] connection lifecycle.
 *
 * Responsibilities:
 *  - Connect / disconnect to the paired Ray-Ban Meta glasses
 *  - Emit lifecycle callbacks consumed by [GlassesBridgeService]
 *  - Expose mic audio as a [Flow<ByteArray>] for [VoiceCapture]
 *  - Expose camera frames as a [Flow<Bitmap>] for [CameraStreamServer]
 *
 * SDK limitation note (mwdat v0.4.0):
 *  - Battery level and worn-detection are not yet part of the public API.
 *    Those are handled by [BatteryMonitor] (BT broadcast) and an
 *    accelerometer heuristic respectively.
 */
class MetaGlassesManager(private val context: Context) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onWornStateChanged(worn: Boolean)
        fun onError(error: String)
    }

    private var listener: Listener? = null
    private var device: WearableDevice? = null

    // --- Public API ---

    fun setListener(l: Listener) {
        listener = l
    }

    /**
     * Connect to the first paired Ray-Ban Meta device found.
     * Must be called from a coroutine or background thread.
     */
    fun connect() {
        Log.d(TAG, "Connecting to Ray-Ban Meta glasses…")
        try {
            device = WearableDevice.connect(context, object : WearableDeviceListener {
                override fun onConnected(d: WearableDevice) {
                    Log.i(TAG, "Glasses connected")
                    listener?.onConnected()
                }

                override fun onDisconnected(d: WearableDevice) {
                    Log.i(TAG, "Glasses disconnected")
                    listener?.onDisconnected()
                }

                override fun onError(d: WearableDevice, error: String) {
                    Log.e(TAG, "Glasses error: $error")
                    listener?.onError(error)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to glasses", e)
            listener?.onError(e.message ?: "Unknown error")
        }
    }

    fun disconnect() {
        device?.disconnect()
        device = null
    }

    /**
     * Returns a [Flow] of raw PCM audio frames (16-bit LE, 16 kHz, mono)
     * captured from the glasses microphone.
     *
     * The flow completes when the collector is cancelled.
     */
    fun getMicAudioStream(): Flow<ByteArray> = callbackFlow {
        val d = device ?: run {
            close()
            return@callbackFlow
        }

        val micListener = object : MicrophoneListener {
            override fun onAudioFrame(frame: AudioFrame) {
                trySend(frame.pcmData)
            }
        }

        d.microphone.addListener(micListener)
        d.microphone.startCapture()

        awaitClose {
            d.microphone.stopCapture()
            d.microphone.removeListener(micListener)
        }
    }

    /**
     * Returns a [Flow] of JPEG-encoded camera frames from the glasses camera.
     *
     * Frames arrive at approximately 30 fps (720p). The flow completes when
     * the collector is cancelled.
     */
    fun getCameraFrameStream(): Flow<ByteArray> = callbackFlow {
        val d = device ?: run {
            close()
            return@callbackFlow
        }

        val camListener = object : CameraListener {
            override fun onFrame(frame: CameraFrame) {
                // CameraFrame.jpegData contains JPEG-encoded bytes
                trySend(frame.jpegData)
            }
        }

        d.camera.addListener(camListener)
        d.camera.startStream()

        awaitClose {
            d.camera.stopStream()
            d.camera.removeListener(camListener)
        }
    }
}
