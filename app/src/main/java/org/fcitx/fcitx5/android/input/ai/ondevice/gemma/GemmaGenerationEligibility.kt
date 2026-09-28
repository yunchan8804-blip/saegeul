/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.os.PowerManager
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceResourceSnapshot

data class GemmaGenerationSnapshot(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val powerSaveMode: Boolean,
    val thermalStatus: Int?,
    val lowMemory: Boolean,
    /**
     * 입력 뷰가 지금 화면에 있는지. `isKeyboardActive`(숨김 유예 포함)가 아니라 이 신호가
     * 배경 재료 생성의 중지 경계다.
     */
    val inputViewVisible: Boolean
)

enum class GemmaGenerationMode(
    val maxContexts: Int,
    val startBudgetMillis: Long,
    val minimumBatteryPercent: Int
) {
    AUTOMATIC(
        maxContexts = 4,
        startBudgetMillis = 120_000L,
        minimumBatteryPercent = 30
    ),
    MANUAL(
        maxContexts = 16,
        startBudgetMillis = 480_000L,
        minimumBatteryPercent = 20
    )
}

enum class GemmaGenerationWaitReason(val message: String) {
    BATTERY_LEVEL_UNKNOWN("배터리 잔량을 확인할 수 없어 생성을 미룹니다."),
    BATTERY_BELOW_MINIMUM("배터리가 30% 이상일 때 생성합니다."),
    MANUAL_BATTERY_BELOW_MINIMUM("수동 강화는 배터리가 20% 이상일 때 실행합니다."),
    POWER_SAVE_MODE("절전 모드에서는 생성을 미룹니다."),
    THERMAL_LIMITED("기기 온도가 높아 생성을 미룹니다."),
    LOW_MEMORY("메모리 부족 상태라 생성을 미룹니다."),
    KEYBOARD_ACTIVE("키보드 사용 중에는 생성하지 않습니다.")
}

object GemmaGenerationEligibility {
    fun snapshot(context: Context): GemmaGenerationSnapshot {
        val resources = OnDeviceResourceSnapshot.read(context)
        return GemmaGenerationSnapshot(
            batteryPercent = resources.batteryPercent,
            isCharging = resources.batteryCharging,
            powerSaveMode = resources.powerSaveMode,
            thermalStatus = resources.thermalStatus,
            lowMemory = resources.lowMemory,
            inputViewVisible = OnDeviceGenerationControl.isInputViewVisible
        )
    }

    fun evaluate(
        snapshot: GemmaGenerationSnapshot,
        mode: GemmaGenerationMode = GemmaGenerationMode.AUTOMATIC
    ): GemmaGenerationWaitReason? = when {
        snapshot.batteryPercent == null || snapshot.batteryPercent !in 0..100 -> {
            GemmaGenerationWaitReason.BATTERY_LEVEL_UNKNOWN
        }
        snapshot.batteryPercent < mode.minimumBatteryPercent -> when (mode) {
            GemmaGenerationMode.AUTOMATIC -> GemmaGenerationWaitReason.BATTERY_BELOW_MINIMUM
            GemmaGenerationMode.MANUAL -> GemmaGenerationWaitReason.MANUAL_BATTERY_BELOW_MINIMUM
        }
        snapshot.thermalStatus != null && snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
            GemmaGenerationWaitReason.THERMAL_LIMITED
        }
        snapshot.lowMemory -> GemmaGenerationWaitReason.LOW_MEMORY
        snapshot.inputViewVisible -> GemmaGenerationWaitReason.KEYBOARD_ACTIVE
        else -> null
    }

    fun canGenerate(
        snapshot: GemmaGenerationSnapshot,
        mode: GemmaGenerationMode = GemmaGenerationMode.AUTOMATIC
    ): Boolean = evaluate(snapshot, mode) == null

    fun contextLimitForThermalStatus(
        thermalStatus: Int?,
        mode: GemmaGenerationMode = GemmaGenerationMode.AUTOMATIC
    ): Int = when {
        thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 0
        thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 1
        else -> mode.maxContexts
    }

    fun reduceContextLimit(
        currentLimit: Int,
        thermalStatus: Int?,
        mode: GemmaGenerationMode = GemmaGenerationMode.AUTOMATIC
    ): Int = minOf(
        currentLimit,
        contextLimitForThermalStatus(thermalStatus, mode)
    )

    const val MINIMUM_BATTERY_PERCENT = 30
}
