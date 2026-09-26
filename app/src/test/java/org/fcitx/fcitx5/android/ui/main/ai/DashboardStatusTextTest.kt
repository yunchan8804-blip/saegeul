/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusTextTest {

    @Test
    fun `release build is always unsupported regardless of snapshot`() {
        val snapshot = AiRuntimeStatusStore.Snapshot(lastWarmupAtMs = 1_000L)

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = false, nowMs = 2_000L)

        assertEquals(DashboardStatusText.EngineState.UNSUPPORTED_RELEASE, status.state)
    }

    @Test
    fun `debug build with no warmup and no failure is not attempted`() {
        val snapshot = AiRuntimeStatusStore.Snapshot()

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = true, nowMs = 5_000L)

        assertEquals(DashboardStatusText.EngineState.NOT_ATTEMPTED, status.state)
    }

    @Test
    fun `most recent warmup after a stale failure is ready`() {
        val snapshot = AiRuntimeStatusStore.Snapshot(
            lastWarmupAtMs = 5_000L,
            backend = "cpu",
            lastFailureAtMs = 1_000L,
            lastFailureCode = "BUSY",
            recoveryCount = 2
        )

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = true, nowMs = 6_000L)

        assertEquals(DashboardStatusText.EngineState.READY, status.state)
        assertEquals("cpu", status.backend)
        assertEquals(1_000L, status.ageMs)
        assertEquals(2, status.recoveryCount)
    }

    @Test
    fun `most recent failure after an older warmup is failed`() {
        val snapshot = AiRuntimeStatusStore.Snapshot(
            lastWarmupAtMs = 1_000L,
            backend = "gpu",
            lastFailureAtMs = 5_000L,
            lastFailureCode = "ENGINE_UNRECOVERABLE",
            recoveryCount = 1
        )

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = true, nowMs = 6_000L)

        assertEquals(DashboardStatusText.EngineState.FAILED, status.state)
        assertEquals("ENGINE_UNRECOVERABLE", status.failureCode)
        assertEquals(1_000L, status.ageMs)
        assertEquals(1, status.recoveryCount)
    }

    @Test
    fun `a warmup that recorded a failure code is failed even with equal timestamps`() {
        // recordWarmup(result = <failure code>) and recordFailure(code) fire back-to-back for a
        // failed warmup, so lastWarmupAtMs and lastFailureAtMs end up equal (or a millisecond
        // apart). Timestamp ordering alone can't distinguish this from "warmup succeeded, stale
        // unrelated failure on record" -- the non-OK lastWarmupResult must win.
        val snapshot = AiRuntimeStatusStore.Snapshot(
            lastWarmupAtMs = 3_000L,
            lastWarmupResult = "BUSY",
            backend = "cpu",
            lastFailureAtMs = 3_000L,
            lastFailureCode = "BUSY"
        )

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = true, nowMs = 3_500L)

        assertEquals(DashboardStatusText.EngineState.FAILED, status.state)
        assertEquals("BUSY", status.failureCode)
        assertEquals(500L, status.ageMs)
    }

    @Test
    fun `a successful warmup with an old unrelated failure at the same timestamp is ready`() {
        val snapshot = AiRuntimeStatusStore.Snapshot(
            lastWarmupAtMs = 3_000L,
            lastWarmupResult = "OK",
            backend = "cpu",
            lastFailureAtMs = 3_000L,
            lastFailureCode = "BUSY"
        )

        val status = DashboardStatusText.engineStatus(snapshot, isDebugBuild = true, nowMs = 3_500L)

        assertEquals(DashboardStatusText.EngineState.READY, status.state)
        assertEquals("cpu", status.backend)
    }

    @Test
    fun `notification blocked hint requires a recorded block and no permission`() {
        assertTrue(DashboardStatusText.notificationBlocked(lastBlockedAtMs = 1_000L, permissionGranted = false))
        assertFalse(DashboardStatusText.notificationBlocked(lastBlockedAtMs = 0L, permissionGranted = false))
        assertFalse(DashboardStatusText.notificationBlocked(lastBlockedAtMs = 1_000L, permissionGranted = true))
    }
}
