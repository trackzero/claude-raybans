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
 * Monitors Ray-Ban Meta glasses battery level via Bluetooth system broadcasts.
 *
 * Android fires [ACTION_BATTERY_LEVEL_CHANGED] with [EXTRA_BATTERY_LEVEL] whenever
 * the connected BT device reports a battery update. This is the fallback when
 * the mwdat SDK does not expose a battery API directly.
 *
 * Usage:
 *   val monitor = BatteryMonitor(context) { level -> pushBatteryToHa(level) }
 *   monitor.register()   // call in service onCreate
 *   monitor.unregister() // call in service onDestroy
 */
class BatteryMonitor(
    private val context: Context,
    private val onBatteryLevel: (Int) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_BATTERY_LEVEL_CHANGED) return

            val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            Log.d(TAG, "Battery update from ${device?.name}: $level%")
            if (level in 0..100) {
                onBatteryLevel(level)
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED)
        context.registerReceiver(receiver, filter)
        Log.d(TAG, "BatteryMonitor registered")
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
