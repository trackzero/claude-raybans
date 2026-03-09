package com.raybans.ha.glasses

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private const val TAG = "MetaGlassesManager"

/**
 * Wraps the mwdat SDK connection lifecycle for Ray-Ban Meta Gen 2 glasses.
 *
 * Lifecycle:
 *  1. Call [initialize] (suspend) — initializes the Wearables SDK singleton.
 *  2. Call [connect] — creates a [DeviceSession] with [AutoDeviceSelector] and starts it.
 *  3. [Listener] callbacks fire on connect/disconnect.
 *  4. After [Listener.onConnected], call [startStreamSession] for camera access.
 *  5. Call [disconnect] on service stop.
 *
 * Battery and worn state are not part of the mwdat v0.4.0 public API.
 * Battery is handled externally by [BatteryMonitor] via BT broadcasts.
 */
class MetaGlassesManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onWornStateChanged(worn: Boolean)
        fun onError(error: String)
    }

    private var listener: Listener? = null
    private var deviceSession: DeviceSession? = null
    private var streamSession: StreamSession? = null

    fun setListener(l: Listener) { listener = l }

    /**
     * Initialize the mwdat SDK. Suspend — call from a coroutine on IO dispatcher.
     */
    suspend fun initialize() {
        try {
            // Wearables is a Kotlin object (singleton); initialize() is a suspend fun.
            // The JVM-mangled name contains the return value class suffix but Kotlin sees it as initialize().
            Wearables.initialize(context)
            Log.i(TAG, "Wearables SDK initialized. State: ${Wearables.registrationState.value}")
        } catch (e: Exception) {
            Log.e(TAG, "SDK initialization failed", e)
            listener?.onError("SDK init failed: ${e.message}")
        }
    }

    /**
     * Start a [DeviceSession] using [AutoDeviceSelector] (picks the first paired Meta device).
     * Observes session state and fires [Listener] callbacks.
     */
    fun connect() {
        Log.d(TAG, "Starting DeviceSession")
        val session = DeviceSession(AutoDeviceSelector())
        deviceSession = session
        session.start()

        scope.launch(Dispatchers.IO) {
            session.state.collect { state ->
                Log.d(TAG, "DeviceSession state → $state")
                when (state) {
                    DeviceSessionState.STARTED -> listener?.onConnected()
                    DeviceSessionState.STOPPED -> listener?.onDisconnected()
                }
            }
        }
    }

    fun disconnect() {
        streamSession?.close()
        streamSession = null
        deviceSession?.stop()
        deviceSession = null
        Log.d(TAG, "DeviceSession stopped")
    }

    /**
     * Start a camera [StreamSession].
     * Must be called after the device reports [DeviceSessionState.STARTED].
     *
     * The returned session exposes:
     *  - [StreamSession.videoStream] — Flow<VideoFrame> (H.265 encoded frames)
     *  - [StreamSession.capturePhoto] — suspend fun for a single JPEG snapshot
     *  - [StreamSession.state] — StateFlow<StreamSessionState>
     */
    fun startStreamSession(): StreamSession? {
        return try {
            val config = StreamConfiguration() // default quality + frame rate
            val session = Wearables.startStreamSession(context, AutoDeviceSelector(), config)
            streamSession = session
            Log.i(TAG, "StreamSession started, state=${session.state.value}")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StreamSession", e)
            listener?.onError("StreamSession failed: ${e.message}")
            null
        }
    }

    /**
     * Returns a [Flow] of H.265 encoded video frames from the glasses camera.
     * Returns [emptyFlow] until [startStreamSession] has been called.
     *
     * For the MJPEG server, use [captureJpegSnapshot] to grab snapshots
     * rather than re-encoding H.265 frames on-device.
     */
    fun getVideoFrameStream(): Flow<VideoFrame> =
        streamSession?.videoStream ?: emptyFlow()

    /**
     * Capture a single JPEG photo via [StreamSession.capturePhoto].
     * Returns the JPEG bytes, or null on failure.
     *
     * Note: [com.meta.wearable.dat.camera.types.PhotoData] is an opaque interface in v0.4.0.
     * This method returns null until the SDK exposes a concrete toByteArray() call.
     * Update when Meta provides a concrete PhotoData implementation.
     */
    suspend fun captureJpegSnapshot(): ByteArray? {
        val session = streamSession ?: return null
        return try {
            val result = session.capturePhoto()
            Log.d(TAG, "capturePhoto result: $result")
            // TODO: extract JPEG bytes from PhotoData when SDK exposes the method
            null
        } catch (e: Exception) {
            Log.e(TAG, "capturePhoto failed", e)
            null
        }
    }

    /**
     * Mic audio is not exposed as a public Flow in mwdat v0.4.0.
     * Audio arrives via internal listener interfaces inside StreamSession.
     * Returns [emptyFlow] until a future SDK version makes it public.
     */
    fun getMicAudioStream(): Flow<ByteArray> = emptyFlow()
}
