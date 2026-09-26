/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.hardware

import android.app.ActivityManager
import android.content.Context

enum class HardwareTier {
    HIGH_END,
    MID_RANGE,
    LOW_END
}

data class HardwareProfile(
    val tier: HardwareTier,
    val totalRamBytes: Long,
    val cpuCores: Int,
    val recommendedModel: String
)

object HardwareTierProfiler {

    const val TEN_GB_BYTES = 10L * 1024 * 1024 * 1024
    const val SIX_GB_BYTES = 6L * 1024 * 1024 * 1024

    const val MODEL_HIGH_END = "Gemma 2B/4B"
    const val MODEL_MID_RANGE = "Gemma 1B / Qwen 0.5B"
    const val MODEL_LOW_END = "Pure Rule & Ego-Graph Strip"

    fun profile(context: Context): HardwareProfile {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        if (actManager != null) {
            actManager.getMemoryInfo(memInfo)
        }
        val totalRam = memInfo.totalMem
        val cores = Runtime.getRuntime().availableProcessors()
        return profile(totalRam, cores)
    }

    fun profile(totalRamBytes: Long, cpuCores: Int = Runtime.getRuntime().availableProcessors()): HardwareProfile {
        val tier = when {
            totalRamBytes >= TEN_GB_BYTES -> HardwareTier.HIGH_END
            totalRamBytes >= SIX_GB_BYTES -> HardwareTier.MID_RANGE
            else -> HardwareTier.LOW_END
        }

        val recommendedModel = when (tier) {
            HardwareTier.HIGH_END -> MODEL_HIGH_END
            HardwareTier.MID_RANGE -> MODEL_MID_RANGE
            HardwareTier.LOW_END -> MODEL_LOW_END
        }

        return HardwareProfile(
            tier = tier,
            totalRamBytes = totalRamBytes,
            cpuCores = cpuCores,
            recommendedModel = recommendedModel
        )
    }
}
