/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardianTest {

    @Test
    fun testInitialState() {
        val guardian = ThermalGuardian()
        assertEquals(25.0f, guardian.currentTemperature, 0.001f)
        assertEquals(ThermalStatus.NORMAL, guardian.currentThermalStatus)
        assertTrue(guardian.isBackgroundTrainingAllowed())
        assertEquals(0L, guardian.getThrottleDelayMs())
        assertEquals(16, guardian.getRecommendedBatchSize())
    }

    @Test
    fun testBackgroundTrainingThresholdBoundary() {
        val guardian = ThermalGuardian()

        // Within safe limit: <= 36.5°C and status <= LIGHT
        guardian.updateTemperature(36.0f)
        guardian.updateThermalStatus(ThermalStatus.NORMAL)
        assertTrue(guardian.isBackgroundTrainingAllowed())

        guardian.updateTemperature(36.5f)
        guardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertTrue("36.5°C with LIGHT should be allowed", guardian.isBackgroundTrainingAllowed())

        // Exceeding temperature > 36.5°C
        guardian.updateTemperature(36.51f)
        guardian.updateThermalStatus(ThermalStatus.NORMAL)
        assertFalse("36.51°C should be blocked", guardian.isBackgroundTrainingAllowed())

        guardian.updateTemperature(37.0f)
        guardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertFalse("37.0°C should be blocked", guardian.isBackgroundTrainingAllowed())

        // Exceeding thermal status > LIGHT (even if temp is low)
        guardian.updateTemperature(30.0f)
        guardian.updateThermalStatus(ThermalStatus.MODERATE)
        assertFalse("MODERATE thermal status should block training", guardian.isBackgroundTrainingAllowed())

        guardian.updateThermalStatus(ThermalStatus.SEVERE)
        assertFalse("SEVERE thermal status should block training", guardian.isBackgroundTrainingAllowed())

        guardian.updateThermalStatus(ThermalStatus.CRITICAL)
        assertFalse("CRITICAL thermal status should block training", guardian.isBackgroundTrainingAllowed())
    }

    @Test
    fun testThrottleDelayValues() {
        val guardian = ThermalGuardian()

        guardian.updateThermalStatus(ThermalStatus.NORMAL)
        assertEquals(0L, guardian.getThrottleDelayMs())

        guardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertEquals(0L, guardian.getThrottleDelayMs())

        guardian.updateThermalStatus(ThermalStatus.MODERATE)
        assertEquals(10L, guardian.getThrottleDelayMs())

        guardian.updateThermalStatus(ThermalStatus.SEVERE)
        assertEquals(25L, guardian.getThrottleDelayMs())

        guardian.updateThermalStatus(ThermalStatus.CRITICAL)
        assertEquals(100L, guardian.getThrottleDelayMs())
    }

    @Test
    fun testRecommendedBatchSize() {
        val guardian = ThermalGuardian()

        guardian.updateThermalStatus(ThermalStatus.NORMAL)
        assertEquals(16, guardian.getRecommendedBatchSize())

        guardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertEquals(8, guardian.getRecommendedBatchSize())

        guardian.updateThermalStatus(ThermalStatus.MODERATE)
        assertEquals(4, guardian.getRecommendedBatchSize())

        guardian.updateThermalStatus(ThermalStatus.SEVERE)
        assertEquals(1, guardian.getRecommendedBatchSize())

        guardian.updateThermalStatus(ThermalStatus.CRITICAL)
        assertEquals(0, guardian.getRecommendedBatchSize())
    }
}
