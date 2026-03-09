package com.raybans.ha.glasses

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

private const val TAG = "BatteryMonitor"

/**
 * Monitors Ray-Ban Meta glasses battery level AND Bluetooth connection state
 * via Android system broadcasts.
 *
 * Why not mwdat for connection state? Because DeviceSession.STOPPED fires
 * almost immediately due to SDK session issues, even when the glasses are
 * perfectly connected via Bluetooth. We use ACL connect/disconnect instead.
 *
 * Why not mwdat for battery? Because v0.4.0 exposes no battery API.
 *
 * Android 13+ fix: dynamic receivers for system broadcasts require
 * RECEIVER_EXPORTED or the broadcast is silently dropped.
 */
class BatteryMonitor(
    private val context: Context,
    private val onBatteryLevel: (Int) -> Unit,
    val onConnected: (() -> Unit)? = null,
    val onDisconnected: (() -> Unit)? = null,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    Log.d(TAG, "Battery update from ${device?.name}: $level%")
                    if (level in 0..100) onBatteryLevel(level)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "BT ACL connected: ${device?.name}")
                    if (isGlasses(device)) onConnected?.invoke()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "BT ACL disconnected: ${device?.name}")
                    if (isGlasses(device)) onDisconnected?.invoke()
                }
            }
        }
    }

    /** Heuristic: treat any Bluetooth audio device with "Ray-Ban" or "Meta" in the name as glasses. */
    private fun isGlasses(device: BluetoothDevice?): Boolean {
        val name = try { device?.name ?: "" } catch (_: SecurityException) { "" }
        return name.contains("Ray-Ban", ignoreCase = true)
            || name.contains("RayBan", ignoreCase = true)
            || name.contains("Wayfarer", ignoreCase = true)
            || name.contains("Meta", ignoreCase = true)
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "BatteryMonitor registered (including BT ACL events)")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) { /* already unregistered */ }
        Log.d(TAG, "BatteryMonitor unregistered")
    }

    companion object {
        private const val ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
