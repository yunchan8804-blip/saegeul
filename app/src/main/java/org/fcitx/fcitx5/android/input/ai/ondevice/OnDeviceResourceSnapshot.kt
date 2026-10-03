/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * One read of the device resources that gate on-device generation. Thresholds and the action
 * taken on a blocked resource stay with each caller.
 */
data class OnDeviceResourceSnapshot(
    val batteryPercent: Int?,
    /** The battery reports charging or full. */
    val batteryCharging: Boolean,
    /** A power source is plugged in, whatever the battery status reports. */
    val powerPlugged: Boolean,
    val powerSaveMode: Boolean,
    val lowMemory: Boolean,
    /** `PowerManager.THERMAL_STATUS_*`, or null on devices without the thermal API. */
    val thermalStatus: Int?
) {
    /**
     * Failure code that stops a foreground generation request, or null when resources allow it.
     * Checks run in a fixed order: battery, power save, memory, thermal.
     */
    fun foregroundGenerationBlockCode(minimumBatteryPercent: Int): String? {
        val charging = batteryCharging || powerPlugged
        return when {
            batteryPercent == null || (batteryPercent < minimumBatteryPercent && !charging) -> "RESOURCE_BATTERY"
            powerSaveMode && !charging -> "RESOURCE_POWER_SAVE"
            lowMemory -> "RESOURCE_LOW_MEMORY"
            // Light, moderate and severe throttling are normal while a GPU model runs (Samsung reports
            // severe under ordinary charge-plus-load warmth); only critical or worse stops on-device
            // generation. Devices without a thermal API are not blocked.
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> "RESOURCE_THERMAL"
            else -> null
        }
    }

    companion object {
        fun read(context: Context): OnDeviceResourceSnapshot {
            val appContext = context.applicationContext
            val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val batteryPlugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            return OnDeviceResourceSnapshot(
                batteryPercent = batteryPercent(level, scale),
                batteryCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
                powerPlugged = batteryPlugged != 0,
                powerSaveMode = powerManager.isPowerSaveMode,
                lowMemory = memoryInfo.lowMemory,
                thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager.currentThermalStatus
                } else {
                    null
                }
            )
        }

        fun batteryPercent(level: Int, scale: Int): Int? {
            if (level < 0 || scale <= 0 || level > scale) return null
            return (level.toLong() * 100L / scale).toInt()
        }
    }
}
