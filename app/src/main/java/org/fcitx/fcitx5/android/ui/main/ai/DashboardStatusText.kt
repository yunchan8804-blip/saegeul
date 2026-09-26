/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore

/**
 * Pure classification for the "right now" status card on the Typing DNA dashboard. Kept free of
 * [android.content.res.Resources] and other Android framework types so the state machine can be
 * exercised in a plain JVM unit test; the activity turns each result into localized text.
 */
object DashboardStatusText {

    enum class EngineState {
        /** This build doesn't ship the on-device automatic-suggestion engine at all. */
        UNSUPPORTED_RELEASE,
        /** The engine has never recorded a warmup or a failure. */
        NOT_ATTEMPTED,
        /** The most recent event was a successful warmup. */
        READY,
        /** The most recent event was a failure. */
        FAILED
    }

    data class EngineStatus(
        val state: EngineState,
        val backend: String? = null,
        val ageMs: Long = 0L,
        val failureCode: String? = null,
        val recoveryCount: Int = 0
    )

    /**
     * Classifies [snapshot] into a single row state for the engine row.
     *
     * A failed warmup records [AiRuntimeStatusStore.Snapshot.lastWarmupResult] (the failure code,
     * not "OK") and [AiRuntimeStatusStore.Snapshot.lastFailureAtMs] in the same call, so the two
     * timestamps are equal or a millisecond apart. Timestamp comparison alone can't tell that
     * apart from "warmup succeeded, unrelated old failure on record", so a non-OK warmup result
     * is checked first and wins regardless of the timestamp ordering.
     */
    fun engineStatus(
        snapshot: AiRuntimeStatusStore.Snapshot,
        isDebugBuild: Boolean,
        nowMs: Long
    ): EngineStatus {
        if (!isDebugBuild) return EngineStatus(EngineState.UNSUPPORTED_RELEASE)
        val warmupFailed = snapshot.lastWarmupResult != null && snapshot.lastWarmupResult != "OK"
        return when {
            snapshot.lastWarmupAtMs == 0L && snapshot.lastFailureAtMs == 0L ->
                EngineStatus(EngineState.NOT_ATTEMPTED)
            warmupFailed -> EngineStatus(
                state = EngineState.FAILED,
                ageMs = (nowMs - snapshot.lastWarmupAtMs).coerceAtLeast(0L),
                failureCode = snapshot.lastWarmupResult,
                recoveryCount = snapshot.recoveryCount
            )
            snapshot.lastFailureAtMs > snapshot.lastWarmupAtMs -> EngineStatus(
                state = EngineState.FAILED,
                ageMs = (nowMs - snapshot.lastFailureAtMs).coerceAtLeast(0L),
                failureCode = snapshot.lastFailureCode,
                recoveryCount = snapshot.recoveryCount
            )
            else -> EngineStatus(
                state = EngineState.READY,
                backend = snapshot.backend,
                ageMs = (nowMs - snapshot.lastWarmupAtMs).coerceAtLeast(0L),
                recoveryCount = snapshot.recoveryCount
            )
        }
    }

    /** Whether the notifications row should surface the "notifications are off" hint. */
    fun notificationBlocked(lastBlockedAtMs: Long, permissionGranted: Boolean): Boolean =
        lastBlockedAtMs != 0L && !permissionGranted
}
