/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.thermal

/**
 * Android thermal throttling status level.
 * Ordinals: 0 (NORMAL), 1 (LIGHT), 2 (MODERATE), 3 (SEVERE), 4 (CRITICAL)
 */
enum class ThermalStatus {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

/**
 * Adaptive Thermal Guardian (EAI-14).
 * Monitors device battery/SoC temperature and system thermal status to regulate
 * on-device AI training and inference throttling.
 *
 * Rules:
 * - Background training threshold: temp <= 36.5°C and status <= LIGHT.
 * - Throttle delays: NORMAL/LIGHT -> 0ms, MODERATE -> 10ms, SEVERE -> 25ms, CRITICAL -> 100ms.
 * - Batch sizes: NORMAL -> 16, LIGHT -> 8, MODERATE -> 4, SEVERE -> 1, CRITICAL -> 0.
 */
class ThermalGuardian(
    initialTemperatureCelsius: Float = 25.0f,
    initialStatus: ThermalStatus = ThermalStatus.NORMAL
) {

    companion object {
        const val MAX_BACKGROUND_TRAINING_TEMP_CELSIUS = 36.5f
    }

    private val lock = Any()

    @Volatile
    var currentTemperature: Float = initialTemperatureCelsius
        private set

    @Volatile
    var currentThermalStatus: ThermalStatus = initialStatus
        private set

    /**
     * Updates recorded battery or SoC temperature in Celsius.
     */
    fun updateTemperature(tempCelsius: Float) = synchronized(lock) {
        currentTemperature = tempCelsius
    }

    /**
     * Updates system thermal throttling status.
     */
    fun updateThermalStatus(status: ThermalStatus) = synchronized(lock) {
        currentThermalStatus = status
    }

    /**
     * Determines whether background on-device training/fine-tuning is permitted.
     * Allowed only when temperature <= 36.5°C and thermal status <= LIGHT.
     */
    fun isBackgroundTrainingAllowed(): Boolean = synchronized(lock) {
        currentTemperature <= MAX_BACKGROUND_TRAINING_TEMP_CELSIUS &&
            currentThermalStatus <= ThermalStatus.LIGHT
    }

    /**
     * Calculates pacing delay in milliseconds between inference or training steps.
     * - NORMAL / LIGHT: 0 ms
     * - MODERATE: 10 ms
     * - SEVERE: 25 ms
     * - CRITICAL: 100 ms
     */
    fun getThrottleDelayMs(): Long = synchronized(lock) {
        when (currentThermalStatus) {
            ThermalStatus.NORMAL,
            ThermalStatus.LIGHT -> 0L
            ThermalStatus.MODERATE -> 10L
            ThermalStatus.SEVERE -> 25L
            ThermalStatus.CRITICAL -> 100L
        }
    }

    /**
     * Computes the recommended on-device batch size given the current thermal headroom.
     * - NORMAL: 16
     * - LIGHT: 8
     * - MODERATE: 4
     * - SEVERE: 1
     * - CRITICAL: 0 (Suspend batch computation)
     */
    fun getRecommendedBatchSize(): Int = synchronized(lock) {
        when (currentThermalStatus) {
            ThermalStatus.NORMAL -> 16
            ThermalStatus.LIGHT -> 8
            ThermalStatus.MODERATE -> 4
            ThermalStatus.SEVERE -> 1
            ThermalStatus.CRITICAL -> 0
        }
    }
}
