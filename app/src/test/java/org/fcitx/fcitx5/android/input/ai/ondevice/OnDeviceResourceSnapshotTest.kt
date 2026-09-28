/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceResourceSnapshotTest {
    @Test
    fun `battery at the minimum is allowed and one below is blocked unless charging`() {
        assertNull(snapshot(batteryPercent = 20).foregroundGenerationBlockCode(20))
        assertEquals("RESOURCE_BATTERY", snapshot(batteryPercent = 19).foregroundGenerationBlockCode(20))
        assertNull(snapshot(batteryPercent = 19, batteryCharging = true).foregroundGenerationBlockCode(20))
        assertNull(snapshot(batteryPercent = 19, powerPlugged = true).foregroundGenerationBlockCode(20))
    }

    @Test
    fun `unknown battery level is blocked even while charging`() {
        assertEquals(
            "RESOURCE_BATTERY",
            snapshot(batteryPercent = null, batteryCharging = true, powerPlugged = true)
                .foregroundGenerationBlockCode(20)
        )
    }

    @Test
    fun `power save blocks only without a power source`() {
        assertEquals("RESOURCE_POWER_SAVE", snapshot(powerSaveMode = true).foregroundGenerationBlockCode(20))
        assertNull(snapshot(powerSaveMode = true, batteryCharging = true).foregroundGenerationBlockCode(20))
        assertNull(snapshot(powerSaveMode = true, powerPlugged = true).foregroundGenerationBlockCode(20))
    }

    @Test
    fun `low memory blocks even while charging`() {
        assertEquals(
            "RESOURCE_LOW_MEMORY",
            snapshot(lowMemory = true, batteryCharging = true).foregroundGenerationBlockCode(20)
        )
    }

    @Test
    fun `only severe or worse thermal status blocks`() {
        assertNull(snapshot(thermalStatus = null).foregroundGenerationBlockCode(20))
        assertNull(
            snapshot(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE).foregroundGenerationBlockCode(20)
        )
        assertEquals(
            "RESOURCE_THERMAL",
            snapshot(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE).foregroundGenerationBlockCode(20)
        )
        assertEquals(
            "RESOURCE_THERMAL",
            snapshot(thermalStatus = PowerManager.THERMAL_STATUS_SHUTDOWN).foregroundGenerationBlockCode(20)
        )
    }

    @Test
    fun `checks run in battery, power save, memory, thermal order`() {
        val everythingBlocked = snapshot(
            batteryPercent = 5,
            powerSaveMode = true,
            lowMemory = true,
            thermalStatus = PowerManager.THERMAL_STATUS_SEVERE
        )
        assertEquals("RESOURCE_BATTERY", everythingBlocked.foregroundGenerationBlockCode(20))
        assertEquals(
            "RESOURCE_POWER_SAVE",
            everythingBlocked.copy(batteryPercent = 80).foregroundGenerationBlockCode(20)
        )
        assertEquals(
            "RESOURCE_LOW_MEMORY",
            everythingBlocked.copy(batteryPercent = 80, powerSaveMode = false).foregroundGenerationBlockCode(20)
        )
        assertEquals(
            "RESOURCE_THERMAL",
            everythingBlocked.copy(batteryPercent = 80, powerSaveMode = false, lowMemory = false)
                .foregroundGenerationBlockCode(20)
        )
    }

    private fun snapshot(
        batteryPercent: Int? = 80,
        batteryCharging: Boolean = false,
        powerPlugged: Boolean = false,
        powerSaveMode: Boolean = false,
        lowMemory: Boolean = false,
        thermalStatus: Int? = null
    ) = OnDeviceResourceSnapshot(
        batteryPercent = batteryPercent,
        batteryCharging = batteryCharging,
        powerPlugged = powerPlugged,
        powerSaveMode = powerSaveMode,
        lowMemory = lowMemory,
        thermalStatus = thermalStatus
    )
}
