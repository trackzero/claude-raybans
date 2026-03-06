package com.raybans.ha

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.raybans.ha.databinding.ActivityMainBinding
import com.raybans.ha.prefs.AppPreferences
import com.raybans.ha.service.GlassesBridgeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Simple setup UI.
 *
 * The user enters:
 *   - Home Assistant base URL (e.g. https://example.ui.nabu.casa)
 *   - Long-lived access token (created in HA Profile → Long-Lived Access Tokens)
 *   - Device ID (must match what was entered in the HA config flow, e.g. "raybans")
 *   - MJPEG port (default 8080; the Android phone's LAN IP + this port is the MJPEG URL)
 *
 * Tapping "Connect" saves prefs and starts [GlassesBridgeService].
 * Tapping "Disconnect" stops the service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        // Pre-fill form with saved values
        lifecycleScope.launch {
            binding.etHaUrl.setText(prefs.haUrl.first())
            binding.etHaToken.setText(prefs.haToken.first())
            binding.etDeviceId.setText(prefs.deviceId.first())
            binding.etMjpegPort.setText(prefs.mjpegPort.first().toString())
        }

        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClicked() }
    }

    private fun onConnectClicked() {
        val haUrl = binding.etHaUrl.text.toString().trim().trimEnd('/')
        val haToken = binding.etHaToken.text.toString().trim()
        val deviceId = binding.etDeviceId.text.toString().trim()
        val mjpegPort = binding.etMjpegPort.text.toString().toIntOrNull()
            ?: AppPreferences.DEFAULT_MJPEG_PORT

        if (haUrl.isEmpty() || haToken.isEmpty() || deviceId.isEmpty()) {
            Snackbar.make(binding.root, "Please fill in all required fields.", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            prefs.save(haUrl, haToken, deviceId, mjpegPort)
            startForegroundService(Intent(this@MainActivity, GlassesBridgeService::class.java))
            Snackbar.make(binding.root, "Bridge started.", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun onDisconnectClicked() {
        stopService(Intent(this, GlassesBridgeService::class.java))
        Snackbar.make(binding.root, "Bridge stopped.", Snackbar.LENGTH_SHORT).show()
    }
}
