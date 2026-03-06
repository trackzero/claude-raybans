package com.raybans.ha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.raybans.ha.MainActivity
import com.raybans.ha.R
import com.raybans.ha.glasses.BatteryMonitor
import com.raybans.ha.glasses.CameraStreamServer
import com.raybans.ha.glasses.MetaGlassesManager
import com.raybans.ha.glasses.TtsPlayer
import com.raybans.ha.glasses.VoiceCapture
import com.raybans.ha.ha.AssistPipelineClient
import com.raybans.ha.ha.HaApiClient
import com.raybans.ha.ha.HaWebSocketClient
import com.raybans.ha.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "GlassesBridgeService"
private const val CHANNEL_ID = "raybans_bridge"
private const val NOTIF_ID = 1001

/**
 * Foreground service that orchestrates all glasses ↔ HA communication.
 *
 * Lifecycle:
 *   MainActivity.startForegroundService() → onCreate → onStartCommand
 *   MainActivity.stopService() → onDestroy (cleanup)
 *
 * Event wiring:
 *   MetaGlassesManager.onConnected      → HaApiClient.pushConnected(true)
 *   MetaGlassesManager.onDisconnected   → HaApiClient.pushConnected(false)
 *   BatteryMonitor.onBatteryLevel       → HaApiClient.pushBattery(%)
 *   MetaGlassesManager.onWornStateChanged → HaApiClient.pushWorn(bool)
 *   HaWebSocketClient.onNotifyEvent     → TtsPlayer.speak(text)
 *   VoiceCapture.onSpeechDetected       → AssistPipelineClient.startSession()
 */
class GlassesBridgeService : LifecycleService() {

    private lateinit var prefs: AppPreferences

    private lateinit var glassesManager: MetaGlassesManager
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var voiceCapture: VoiceCapture
    private lateinit var cameraServer: CameraStreamServer

    private lateinit var haWsClient: HaWebSocketClient
    private lateinit var haApiClient: HaApiClient
    private lateinit var assistClient: AssistPipelineClient

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        prefs = AppPreferences(this)

        lifecycleScope.launch {
            val haUrl = prefs.haUrl.first()
            val haToken = prefs.haToken.first()
            val deviceId = prefs.deviceId.first()
            val mjpegPort = prefs.mjpegPort.first()

            if (haUrl.isEmpty() || haToken.isEmpty()) {
                Log.e(TAG, "HA URL or token not configured — stopping service")
                stopSelf()
                return@launch
            }

            initComponents(haUrl, haToken, deviceId, mjpegPort)
        }
    }

    private fun initComponents(haUrl: String, haToken: String, deviceId: String, mjpegPort: Int) {
        Log.i(TAG, "Initializing bridge (device_id=$deviceId, ha_url=$haUrl)")

        // HA clients
        haApiClient = HaApiClient(haUrl, haToken, deviceId)
        ttsPlayer = TtsPlayer(this)

        haWsClient = HaWebSocketClient(haUrl, haToken) { text, eventDeviceId ->
            // Only handle events for our device ID
            if (eventDeviceId == deviceId || eventDeviceId.isEmpty()) {
                ttsPlayer.speak(text)
            }
        }

        assistClient = AssistPipelineClient(haWsClient, ttsPlayer, lifecycleScope)

        // Glasses components
        glassesManager = MetaGlassesManager(this).apply {
            setListener(object : MetaGlassesManager.Listener {
                override fun onConnected() {
                    lifecycleScope.launch(Dispatchers.IO) {
                        haApiClient.pushConnected(true)
                    }
                    voiceCapture.start()
                    cameraServer.startCollecting()
                }

                override fun onDisconnected() {
                    lifecycleScope.launch(Dispatchers.IO) {
                        haApiClient.pushConnected(false)
                    }
                    voiceCapture.stop()
                    cameraServer.stopCollecting()
                }

                override fun onWornStateChanged(worn: Boolean) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        haApiClient.pushWorn(worn)
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Glasses error: $error")
                }
            })
        }

        voiceCapture = VoiceCapture(glassesManager, lifecycleScope).apply {
            onSpeechDetected = { firstFrame ->
                // Build a sequence that starts with the trigger frame and continues
                // collecting from the mic until speech ends (isSpeaking == false)
                val frames = sequence {
                    yield(firstFrame)
                    while (isSpeaking) {
                        // Additional frames are collected inside AssistPipelineClient
                        // via the ongoing mic stream — we just signal the session start here
                    }
                }
                assistClient.startSession(frames)
            }
        }

        batteryMonitor = BatteryMonitor(this) { level ->
            lifecycleScope.launch(Dispatchers.IO) {
                haApiClient.pushBattery(level)
            }
        }

        cameraServer = CameraStreamServer(glassesManager, lifecycleScope, mjpegPort)

        // Start everything
        batteryMonitor.register()
        lifecycleScope.launch(Dispatchers.IO) { haWsClient.connect() }
        lifecycleScope.launch(Dispatchers.IO) { glassesManager.connect() }
        cameraServer.start()

        Log.i(TAG, "Bridge service fully initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Stopping bridge service")
        try {
            haWsClient.disconnect()
            glassesManager.disconnect()
            batteryMonitor.unregister()
            ttsPlayer.release()
            voiceCapture.stop()
            cameraServer.stop()
        } catch (e: UninitializedPropertyAccessException) {
            // Service stopped before initComponents completed
        }
    }

    // --- Notification ---

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ray-Ban HA Bridge")
            .setContentText("Connected to Home Assistant")
            .setSmallIcon(R.drawable.ic_glasses_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ray-Ban HA Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Foreground service for Ray-Ban Meta glasses bridge"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
