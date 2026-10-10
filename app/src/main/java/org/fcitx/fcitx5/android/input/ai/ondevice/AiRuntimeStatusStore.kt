/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the automatic-suggestion engine's operational status for the dashboard and settings
 * screens to read. This store never records prompt or response text, only timings, codes, and
 * counters.
 */
class AiRuntimeStatusStore private constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    internal constructor(prefs: SharedPreferences, @Suppress("UNUSED_PARAMETER") forTesting: Unit) : this(prefs)

    data class Snapshot(
        val lastWarmupResult: String? = null,
        val lastWarmupAtMs: Long = 0L,
        val lastWarmupDurationMs: Long = 0L,
        val backend: String? = null,
        val lastGenerationAtMs: Long = 0L,
        val lastGenerationLatencyMs: Long = 0L,
        val recoveryCount: Int = 0,
        val lastRecoveryAtMs: Long = 0L,
        val lastFailureCode: String? = null,
        val lastFailureAtMs: Long = 0L,
        val lastNativeCrashMs: Long = 0L,
        val lastNativeCrashPurpose: String? = null,
        val nativeCrashCount: Int = 0,
        val nativeCrashCheckedMs: Long = 0L
    )

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        lastWarmupResult = prefs.getString(KEY_LAST_WARMUP_RESULT, null),
        lastWarmupAtMs = prefs.getLong(KEY_LAST_WARMUP_AT_MS, 0L),
        lastWarmupDurationMs = prefs.getLong(KEY_LAST_WARMUP_DURATION_MS, 0L),
        backend = prefs.getString(KEY_BACKEND, null),
        lastGenerationAtMs = prefs.getLong(KEY_LAST_GENERATION_AT_MS, 0L),
        lastGenerationLatencyMs = prefs.getLong(KEY_LAST_GENERATION_LATENCY_MS, 0L),
        recoveryCount = prefs.getInt(KEY_RECOVERY_COUNT, 0),
        lastRecoveryAtMs = prefs.getLong(KEY_LAST_RECOVERY_AT_MS, 0L),
        lastFailureCode = prefs.getString(KEY_LAST_FAILURE_CODE, null),
        lastFailureAtMs = prefs.getLong(KEY_LAST_FAILURE_AT_MS, 0L),
        lastNativeCrashMs = prefs.getLong(KEY_LAST_NATIVE_CRASH_MS, 0L),
        lastNativeCrashPurpose = prefs.getString(KEY_LAST_NATIVE_CRASH_PURPOSE, null),
        nativeCrashCount = prefs.getInt(KEY_NATIVE_CRASH_COUNT, 0),
        nativeCrashCheckedMs = prefs.getLong(KEY_NATIVE_CRASH_CHECKED_MS, 0L)
    )

    @Synchronized
    fun recordWarmup(result: String, durationMs: Long, backend: String, nowMs: Long) {
        prefs.edit()
            .putString(KEY_LAST_WARMUP_RESULT, result)
            .putLong(KEY_LAST_WARMUP_AT_MS, nowMs)
            .putLong(KEY_LAST_WARMUP_DURATION_MS, durationMs)
            .putString(KEY_BACKEND, backend)
            .apply()
    }

    @Synchronized
    fun recordGeneration(latencyMs: Long, nowMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_GENERATION_AT_MS, nowMs)
            .putLong(KEY_LAST_GENERATION_LATENCY_MS, latencyMs)
            .apply()
    }

    @Synchronized
    fun recordRecovery(fromCode: String, nowMs: Long) {
        val nextCount = prefs.getInt(KEY_RECOVERY_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_RECOVERY_COUNT, nextCount)
            .putLong(KEY_LAST_RECOVERY_AT_MS, nowMs)
            .putString(KEY_LAST_FAILURE_CODE, fromCode)
            .apply()
    }

    @Synchronized
    fun recordFailure(code: String, nowMs: Long) {
        prefs.edit()
            .putString(KEY_LAST_FAILURE_CODE, code)
            .putLong(KEY_LAST_FAILURE_AT_MS, nowMs)
            .apply()
    }

    /**
     * Stores native crashes found at startup in one edit: the count grows by [crashes]'s size, the
     * last crash is the one with the greatest timestamp, and [checkedMs] becomes the reference time
     * the next check starts after.
     */
    @Synchronized
    fun recordNativeCrashes(crashes: List<NativeCrashAttribution.Crash>, checkedMs: Long) {
        val editor = prefs.edit()
        val latest = crashes.maxByOrNull { it.timestampMs }
        if (latest != null) {
            editor
                .putLong(KEY_LAST_NATIVE_CRASH_MS, latest.timestampMs)
                .putString(KEY_LAST_NATIVE_CRASH_PURPOSE, latest.purpose)
                .putInt(KEY_NATIVE_CRASH_COUNT, prefs.getInt(KEY_NATIVE_CRASH_COUNT, 0) + crashes.size)
        }
        editor.putLong(KEY_NATIVE_CRASH_CHECKED_MS, checkedMs).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "ai_runtime_status"
        const val KEY_LAST_WARMUP_RESULT = "last_warmup_result"
        const val KEY_LAST_WARMUP_AT_MS = "last_warmup_at_ms"
        const val KEY_LAST_WARMUP_DURATION_MS = "last_warmup_duration_ms"
        const val KEY_BACKEND = "backend"
        const val KEY_LAST_GENERATION_AT_MS = "last_generation_at_ms"
        const val KEY_LAST_GENERATION_LATENCY_MS = "last_generation_latency_ms"
        const val KEY_RECOVERY_COUNT = "recovery_count"
        const val KEY_LAST_RECOVERY_AT_MS = "last_recovery_at_ms"
        const val KEY_LAST_FAILURE_CODE = "last_failure_code"
        const val KEY_LAST_FAILURE_AT_MS = "last_failure_at_ms"
        const val KEY_LAST_NATIVE_CRASH_MS = "last_native_crash_ms"
        const val KEY_LAST_NATIVE_CRASH_PURPOSE = "last_native_crash_purpose"
        const val KEY_NATIVE_CRASH_COUNT = "native_crash_count"
        const val KEY_NATIVE_CRASH_CHECKED_MS = "native_crash_checked_ms"
    }
}
