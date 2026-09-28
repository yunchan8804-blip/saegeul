/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.os.PowerManager
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceResourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaGenerationEligibilityTest {
    @Test
    fun `30 percent without charging is eligible`() {
        val snapshot = snapshot(batteryPercent = 30, isCharging = false)

        assertTrue(GemmaGenerationEligibility.canGenerate(snapshot))
        assertNull(GemmaGenerationEligibility.evaluate(snapshot))
    }

    @Test
    fun `29 percent is blocked while charging`() {
        assertWaitReason(
            GemmaGenerationWaitReason.BATTERY_BELOW_MINIMUM,
            snapshot(batteryPercent = 29, isCharging = true)
        )
    }

    @Test
    fun `manual generation is eligible at 20 percent and blocked at 19 percent`() {
        val eligible = snapshot(batteryPercent = 20)
        val blocked = snapshot(batteryPercent = 19)

        assertTrue(GemmaGenerationEligibility.canGenerate(eligible, GemmaGenerationMode.MANUAL))
        assertNull(GemmaGenerationEligibility.evaluate(eligible, GemmaGenerationMode.MANUAL))
        assertFalse(GemmaGenerationEligibility.canGenerate(blocked, GemmaGenerationMode.MANUAL))
        assertEquals(
            GemmaGenerationWaitReason.MANUAL_BATTERY_BELOW_MINIMUM,
            GemmaGenerationEligibility.evaluate(blocked, GemmaGenerationMode.MANUAL)
        )
    }

    @Test
    fun `unknown battery level is blocked`() {
        assertWaitReason(
            GemmaGenerationWaitReason.BATTERY_LEVEL_UNKNOWN,
            snapshot(batteryPercent = null)
        )
    }

    @Test
    fun `invalid battery readings are unknown and do not overflow`() {
        assertNull(OnDeviceResourceSnapshot.batteryPercent(-1, 100))
        assertNull(OnDeviceResourceSnapshot.batteryPercent(1, 0))
        assertNull(OnDeviceResourceSnapshot.batteryPercent(101, 100))
        assertEquals(100, OnDeviceResourceSnapshot.batteryPercent(Int.MAX_VALUE, Int.MAX_VALUE))
        assertWaitReason(
            GemmaGenerationWaitReason.BATTERY_LEVEL_UNKNOWN,
            snapshot(batteryPercent = 101)
        )
    }

    @Test
    fun `power save mode does not block learning`() {
        assertNull(GemmaGenerationEligibility.evaluate(snapshot(powerSaveMode = true)))
    }

    @Test
    fun `moderate thermal status is eligible`() {
        val snapshot = snapshot(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE)

        assertTrue(GemmaGenerationEligibility.canGenerate(snapshot))
        assertNull(GemmaGenerationEligibility.evaluate(snapshot))
    }

    @Test
    fun `severe thermal status is blocked`() {
        assertWaitReason(
            GemmaGenerationWaitReason.THERMAL_LIMITED,
            snapshot(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        )
    }

    @Test
    fun `thermal context limit is four one or zero by status`() {
        assertEquals(4, GemmaGenerationEligibility.contextLimitForThermalStatus(null))
        assertEquals(
            4,
            GemmaGenerationEligibility.contextLimitForThermalStatus(PowerManager.THERMAL_STATUS_LIGHT)
        )
        assertEquals(
            1,
            GemmaGenerationEligibility.contextLimitForThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
        )
        assertEquals(
            0,
            GemmaGenerationEligibility.contextLimitForThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        )
    }

    @Test
    fun `manual thermal context limit is sixteen one or zero by status`() {
        assertEquals(
            16,
            GemmaGenerationEligibility.contextLimitForThermalStatus(
                null,
                GemmaGenerationMode.MANUAL
            )
        )
        assertEquals(
            1,
            GemmaGenerationEligibility.contextLimitForThermalStatus(
                PowerManager.THERMAL_STATUS_MODERATE,
                GemmaGenerationMode.MANUAL
            )
        )
        assertEquals(
            0,
            GemmaGenerationEligibility.contextLimitForThermalStatus(
                PowerManager.THERMAL_STATUS_SEVERE,
                GemmaGenerationMode.MANUAL
            )
        )
    }

    @Test
    fun `manual and automatic modes use distinct limits and budgets`() {
        assertEquals(4, GemmaGenerationMode.AUTOMATIC.maxContexts)
        assertEquals(120_000L, GemmaGenerationMode.AUTOMATIC.startBudgetMillis)
        assertEquals(16, GemmaGenerationMode.MANUAL.maxContexts)
        assertEquals(480_000L, GemmaGenerationMode.MANUAL.startBudgetMillis)
    }

    @Test
    fun `thermal context limit only decreases during a run`() {
        var runContextLimit = 4

        runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
            runContextLimit,
            PowerManager.THERMAL_STATUS_MODERATE
        )
        assertEquals(1, runContextLimit)

        runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
            runContextLimit,
            null
        )
        assertEquals(1, runContextLimit)

        runContextLimit = GemmaGenerationEligibility.reduceContextLimit(
            runContextLimit,
            PowerManager.THERMAL_STATUS_SEVERE
        )
        assertEquals(0, runContextLimit)
    }

    @Test
    fun `low memory is blocked`() {
        assertWaitReason(
            GemmaGenerationWaitReason.LOW_MEMORY,
            snapshot(lowMemory = true)
        )
    }

    @Test
    fun `input view visibility is blocked`() {
        assertWaitReason(
            GemmaGenerationWaitReason.KEYBOARD_ACTIVE,
            snapshot(inputViewVisible = true)
        )
    }

    @Test
    fun `hidden input view is eligible`() {
        val eligible = snapshot(inputViewVisible = false)

        assertTrue(GemmaGenerationEligibility.canGenerate(eligible))
        assertNull(GemmaGenerationEligibility.evaluate(eligible))
    }

    @Test
    fun `manual mode keeps every hard safety guard`() {
        listOf(
            snapshot(batteryPercent = null) to GemmaGenerationWaitReason.BATTERY_LEVEL_UNKNOWN,
            snapshot(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE) to
                GemmaGenerationWaitReason.THERMAL_LIMITED,
            snapshot(lowMemory = true) to GemmaGenerationWaitReason.LOW_MEMORY,
            snapshot(inputViewVisible = true) to GemmaGenerationWaitReason.KEYBOARD_ACTIVE
        ).forEach { (snapshot, expected) ->
            assertFalse(GemmaGenerationEligibility.canGenerate(snapshot, GemmaGenerationMode.MANUAL))
            assertEquals(expected, GemmaGenerationEligibility.evaluate(snapshot, GemmaGenerationMode.MANUAL))
        }
    }

    private fun assertWaitReason(
        expected: GemmaGenerationWaitReason,
        snapshot: GemmaGenerationSnapshot
    ) {
        assertFalse(GemmaGenerationEligibility.canGenerate(snapshot))
        assertEquals(expected, GemmaGenerationEligibility.evaluate(snapshot))
        assertTrue(expected.message.isNotBlank())
    }

    private fun snapshot(
        batteryPercent: Int? = 80,
        isCharging: Boolean = false,
        powerSaveMode: Boolean = false,
        thermalStatus: Int? = null,
        lowMemory: Boolean = false,
        inputViewVisible: Boolean = false
    ) = GemmaGenerationSnapshot(
        batteryPercent = batteryPercent,
        isCharging = isCharging,
        powerSaveMode = powerSaveMode,
        thermalStatus = thermalStatus,
        lowMemory = lowMemory,
        inputViewVisible = inputViewVisible
    )
}
