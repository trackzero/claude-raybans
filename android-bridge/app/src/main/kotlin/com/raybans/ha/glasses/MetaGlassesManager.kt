package com.raybans.ha.glasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

private const val TAG = "MetaGlassesManager"

// ---------------------------------------------------------------------------
// Stub interfaces — replace with real mwdat SDK classes when available.
//
// The Meta Wearables Device Access Toolkit (mwdat) SDK is distributed via
// GitHub Packages (facebook/meta-wearables-dat-android) and requires Meta
// Developer Program access. Once access is granted:
//   1. Uncomment mwdat dependencies in app/build.gradle.kts
//   2. Replace stub body below with real mwdat API calls
//   3. Verify class/method names against the mwdat v0.4.0 changelog
// ---------------------------------------------------------------------------

private interface WearableDeviceListener {
    fun onConnected(device: StubWearableDevice)
    fun onDisconnected(device: StubWearableDevice)
    fun onError(device: StubWearableDevice, error: String)
}

private interface MicrophoneListener {
    fun onAudioFrame(pcmData: ByteArray)
}

private interface CameraListener {
    fun onFrame(jpegData: ByteArray)
}

private class StubWearableDevice {
    val microphone = StubMicrophone()
    val camera = StubCamera()
    fun disconnect() {}
}

private class StubMicrophone {
    fun addListener(l: MicrophoneListener) {}
    fun removeListener(l: MicrophoneListener) {}
    fun startCapture() {}
    fun stopCapture() {}
}

private class StubCamera {
    fun addListener(l: CameraListener) {}
    fun removeListener(l: CameraListener) {}
    fun startStream() {}
    fun stopStream() {}
}

// ---------------------------------------------------------------------------

/**
 * Wraps the mwdat-core [WearableDevice] connection lifecycle.
 *
 * Responsibilities:
 *  - Connect / disconnect to the paired Ray-Ban Meta glasses
 *  - Emit lifecycle callbacks consumed by [GlassesBridgeService]
 *  - Expose mic audio as a [Flow<ByteArray>] for [VoiceCapture]
 *  - Expose camera frames as a [Flow<ByteArray>] (JPEG) for [CameraStreamServer]
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
    private var device: StubWearableDevice? = null

    fun setListener(l: Listener) {
        listener = l
    }

    /**
     * Connect to the first paired Ray-Ban Meta device found.
     * Must be called from a coroutine or background thread.
     *
     * TODO: replace StubWearableDevice with real mwdat WearableDevice.connect() call.
     */
    fun connect() {
        Log.d(TAG, "Connecting to Ray-Ban Meta glasses… (stub — mwdat SDK not linked)")
        // Real implementation:
        //   device = WearableDevice.connect(context, object : WearableDeviceListener { … })
        // For now we log and do nothing; the HA sensor push still works via BatteryMonitor.
    }

    fun disconnect() {
        device?.disconnect()
        device = null
    }

    /**
     * Returns a [Flow] of raw PCM audio frames (16-bit LE, 16 kHz, mono)
     * captured from the glasses microphone.
     *
     * Returns [emptyFlow] until the real mwdat SDK is linked.
     */
    fun getMicAudioStream(): Flow<ByteArray> {
        val d = device ?: return emptyFlow()

        return callbackFlow {
            val micListener = object : MicrophoneListener {
                override fun onAudioFrame(pcmData: ByteArray) {
                    trySend(pcmData)
                }
            }
            d.microphone.addListener(micListener)
            d.microphone.startCapture()

            awaitClose {
                d.microphone.stopCapture()
                d.microphone.removeListener(micListener)
            }
        }
    }

    /**
     * Returns a [Flow] of JPEG-encoded frames from the glasses camera.
     *
     * Returns [emptyFlow] until the real mwdat SDK is linked.
     */
    fun getCameraFrameStream(): Flow<ByteArray> {
        val d = device ?: return emptyFlow()

        return callbackFlow {
            val camListener = object : CameraListener {
                override fun onFrame(jpegData: ByteArray) {
                    trySend(jpegData)
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
}
