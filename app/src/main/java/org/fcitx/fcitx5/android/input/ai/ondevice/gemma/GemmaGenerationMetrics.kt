/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

enum class GemmaGenerationOutcome {
    SUCCESS,
    CANCELLED,
    FAILED
}

data class GemmaGenerationMetrics(
    val wallMs: Long,
    val verificationMs: Long,
    val initializationMs: Long,
    val conversationMs: Long,
    val generationMs: Long,
    val closeMs: Long,
    val startThermalStatus: Int?,
    val endThermalStatus: Int?,
    val peakSampledPssKb: Long?,
    val minAvailableMemoryBytes: Long?,
    val firstThermalLimitedMs: Long?,
    val outcome: GemmaGenerationOutcome,
    val firstTextMs: Long? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("wallMs", wallMs)
        put("verificationMs", verificationMs)
        put("initializationMs", initializationMs)
        put("conversationMs", conversationMs)
        put("generationMs", generationMs)
        put("closeMs", closeMs)
        put("startThermalStatus", startThermalStatus ?: JSONObject.NULL)
        put("endThermalStatus", endThermalStatus ?: JSONObject.NULL)
        put("peakSampledPssKb", peakSampledPssKb ?: JSONObject.NULL)
        put("minAvailableMemoryBytes", minAvailableMemoryBytes ?: JSONObject.NULL)
        put("firstThermalLimitedMs", firstThermalLimitedMs ?: JSONObject.NULL)
        put("outcome", outcome.name)
        put("firstTextMs", firstTextMs ?: JSONObject.NULL)
    }.toString()
}

internal class GemmaGenerationMetricsRecorder(
    context: Context,
    private val startedAt: Long
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java)
    } else {
        null
    }

    private var startThermalStatus: Int? = null
    private var peakSampledPssKb: Long? = null
    private var minAvailableMemoryBytes: Long? = null
    private var firstThermalLimitedMs: Long? = null

    fun recordStart() {
        startThermalStatus = sampleNow()
    }

    fun sampleNow(): Int? {
        val thermalStatus = thermalStatus()
        if (
            firstThermalLimitedMs == null &&
            thermalStatus != null &&
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        ) {
            firstThermalLimitedMs = SystemClock.elapsedRealtime() - startedAt
        }
        val pssKb = Debug.getPss().toLong()
        peakSampledPssKb = maxOf(peakSampledPssKb ?: pssKb, pssKb)
        val memory = ActivityManager.MemoryInfo().also(requireNotNull(activityManager)::getMemoryInfo)
        val availableMemoryBytes = memory.availMem
        minAvailableMemoryBytes = minOf(minAvailableMemoryBytes ?: availableMemoryBytes, availableMemoryBytes)
        return thermalStatus
    }

    suspend fun sampleEvery(intervalMs: Long) {
        while (currentCoroutineContext().isActive) {
            sampleNow()
            delay(intervalMs)
        }
    }

    fun build(
        wallMs: Long,
        verificationMs: Long,
        initializationMs: Long,
        conversationMs: Long,
        generationMs: Long,
        closeMs: Long,
        endThermalStatus: Int?,
        outcome: GemmaGenerationOutcome,
        firstTextMs: Long? = null
    ): GemmaGenerationMetrics = GemmaGenerationMetrics(
        wallMs = wallMs,
        verificationMs = verificationMs,
        initializationMs = initializationMs,
        conversationMs = conversationMs,
        generationMs = generationMs,
        closeMs = closeMs,
        startThermalStatus = startThermalStatus,
        endThermalStatus = endThermalStatus,
        peakSampledPssKb = peakSampledPssKb,
        minAvailableMemoryBytes = minAvailableMemoryBytes,
        firstThermalLimitedMs = firstThermalLimitedMs,
        outcome = outcome,
        firstTextMs = firstTextMs
    )

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        requireNotNull(powerManager).currentThermalStatus
    } else {
        null
    }
}
