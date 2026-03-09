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
import com.raybans.ha.glasses.PhoneMicCapture
import com.raybans.ha.glasses.TtsPlayer
import com.raybans.ha.glasses.VoiceCapture
import com.raybans.ha.ha.AssistPipelineClient
import com.raybans.ha.ha.ClaudeVoiceAssist
import com.raybans.ha.ha.HaApiClient
import com.raybans.ha.ha.HaWebSocketClient
import com.raybans.ha.ha.McpClient
import com.raybans.ha.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "GlassesBridgeService"
private const val CHANNEL_ID = "raybans_bridge"
private const val NOTIF_ID = 1001
const val ACTION_ASK_HA = "com.raybans.ha.ACTION_ASK_HA"

class GlassesBridgeService : LifecycleService() {

    private lateinit var prefs: AppPreferences

    private lateinit var glassesManager: MetaGlassesManager
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var voiceCapture: VoiceCapture
    private lateinit var cameraServer: CameraStreamServer
    private lateinit var phoneMicCapture: PhoneMicCapture

    private lateinit var haWsClient: HaWebSocketClient
    private lateinit var haApiClient: HaApiClient
    private lateinit var assistClient: AssistPipelineClient
    private var claudeAssist: ClaudeVoiceAssist? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        prefs = AppPreferences(this)

        lifecycleScope.launch {
            val haUrl = prefs.haUrl.first()
            val haToken = prefs.haToken.first()
            val deviceId = prefs.deviceId.first()
            val mjpegPort = prefs.mjpegPort.first()
            val claudeApiKey = prefs.claudeApiKey.first()
            val mcpUrl = prefs.mcpUrl.first()

            if (haUrl.isEmpty() || haToken.isEmpty()) {
                Log.e(TAG, "HA URL or token not configured — stopping service")
                stopSelf()
                return@launch
            }

            initComponents(haUrl, haToken, deviceId, mjpegPort, claudeApiKey, mcpUrl)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_ASK_HA) {
            Log.i(TAG, "Ask HA triggered from notification")
            if (::phoneMicCapture.isInitialized) {
                phoneMicCapture.startListening()
            }
        }
        return START_STICKY
    }

    private fun initComponents(
        haUrl: String,
        haToken: String,
        deviceId: String,
        mjpegPort: Int,
        claudeApiKey: String,
        mcpUrl: String,
    ) {
        Log.i(TAG, "Initializing bridge (device_id=$deviceId)")

        haApiClient = HaApiClient(haUrl, haToken, deviceId)
        ttsPlayer = TtsPlayer(this)

        haWsClient = HaWebSocketClient(haUrl, haToken) { text, eventDeviceId ->
            if (eventDeviceId == deviceId || eventDeviceId.isEmpty()) {
                ttsPlayer.speak(text)
            }
        }

        assistClient = AssistPipelineClient(haWsClient, ttsPlayer, lifecycleScope)

        // Claude + MCP voice assist (optional — only if both keys provided)
        if (claudeApiKey.isNotEmpty() && mcpUrl.isNotEmpty()) {
            val mcpClient = McpClient(mcpUrl, haToken)
            claudeAssist = ClaudeVoiceAssist(claudeApiKey, mcpClient, ttsPlayer)
            Log.i(TAG, "Claude voice assist enabled")
        } else {
            Log.i(TAG, "Claude voice assist disabled (no API key or MCP URL)")
        }

        phoneMicCapture = PhoneMicCapture(this, lifecycleScope).apply {
            onResult = { text ->
                Log.i(TAG, "STT result: $text")
                val assist = claudeAssist
                if (assist != null) {
                    lifecycleScope.launch(Dispatchers.IO) { assist.processQuery(text) }
                } else {
                    ttsPlayer.speak("Claude is not configured. Please add your API key.")
                }
            }
            onError = { err ->
                Log.w(TAG, "STT error: $err")
                ttsPlayer.speak("I didn't catch that.")
            }
        }

        glassesManager = MetaGlassesManager(this, lifecycleScope).apply {
            setListener(object : MetaGlassesManager.Listener {
                override fun onConnected() {
                    lifecycleScope.launch(Dispatchers.IO) { haApiClient.pushConnected(true) }
                    startStreamSession()
                    voiceCapture.start()
                    cameraServer.startCollecting()
                }
                override fun onDisconnected() {
                    lifecycleScope.launch(Dispatchers.IO) { haApiClient.pushConnected(false) }
                    voiceCapture.stop()
                    cameraServer.stopCollecting()
                }
                override fun onWornStateChanged(worn: Boolean) {
                    lifecycleScope.launch(Dispatchers.IO) { haApiClient.pushWorn(worn) }
                }
                override fun onError(error: String) {
                    Log.e(TAG, "Glasses error: $error")
                }
            })
        }

        voiceCapture = VoiceCapture(glassesManager, lifecycleScope).apply {
            onSpeechDetected = { firstFrame ->
                val frames = sequence {
                    yield(firstFrame)
                    while (isSpeaking) { }
                }
                assistClient.startSession(frames)
            }
        }

        batteryMonitor = BatteryMonitor(this) { level ->
            lifecycleScope.launch(Dispatchers.IO) { haApiClient.pushBattery(level) }
        }

        cameraServer = CameraStreamServer(glassesManager, lifecycleScope, mjpegPort)

        batteryMonitor.register()
        lifecycleScope.launch(Dispatchers.IO) { haWsClient.connect() }
        lifecycleScope.launch(Dispatchers.IO) {
            glassesManager.initialize()
            glassesManager.connect()
        }
        cameraServer.start()

        // Update notification to show "Ask HA" button now that we're ready
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())

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
            phoneMicCapture.destroy()
        } catch (e: UninitializedPropertyAccessException) {
            // Service stopped before initComponents completed
        }
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val askIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GlassesBridgeService::class.java).apply { action = ACTION_ASK_HA },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ray-Ban HA Bridge")
            .setContentText("Connected to Home Assistant")
            .setSmallIcon(R.drawable.ic_glasses_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Ask HA", askIntent)
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
