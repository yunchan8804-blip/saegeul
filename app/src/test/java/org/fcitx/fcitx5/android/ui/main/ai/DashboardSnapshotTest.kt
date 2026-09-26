/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentFailure
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentPhase
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [effectiveEnrichmentPhase]: the dashboard's stale-RUNNING-becomes-INTERRUPTED,
 * terminal-phase-with-a-live-worker-becomes-RUNNING, and legacy-provider-failure-becomes-NEVER
 * corrections, and that a manually queued run is left alone by all of them.
 */
class DashboardSnapshotTest {

    @Test
    fun runningWithNoLiveWorkerBecomesInterrupted() {
        val status = GraphEnrichmentStatus(phase = GraphEnrichmentPhase.RUNNING)

        assertEquals(GraphEnrichmentPhase.INTERRUPTED, effectiveEnrichmentPhase(status, workerIsRunning = false))
    }

    @Test
    fun runningWithALiveWorkerStaysRunning() {
        val status = GraphEnrichmentStatus(phase = GraphEnrichmentPhase.RUNNING)

        assertEquals(GraphEnrichmentPhase.RUNNING, effectiveEnrichmentPhase(status, workerIsRunning = true))
    }

    @Test
    fun legacyProviderFailureBecomesNever() {
        val status = GraphEnrichmentStatus(phase = GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.PROVIDER_ERROR)

        assertEquals(GraphEnrichmentPhase.NEVER, effectiveEnrichmentPhase(status, workerIsRunning = false))
    }

    @Test
    fun onDeviceFailureStaysFailed() {
        val status = GraphEnrichmentStatus(phase = GraphEnrichmentPhase.FAILED, failure = GraphEnrichmentFailure.DEVICE_BUSY)

        assertEquals(GraphEnrichmentPhase.FAILED, effectiveEnrichmentPhase(status, workerIsRunning = false))
    }

    @Test
    fun queuedIsUntouchedByTheInterruptedCorrectionRegardlessOfLiveWorkerState() {
        val status = GraphEnrichmentStatus(phase = GraphEnrichmentPhase.QUEUED)

        assertEquals(GraphEnrichmentPhase.QUEUED, effectiveEnrichmentPhase(status, workerIsRunning = false))
        assertEquals(GraphEnrichmentPhase.QUEUED, effectiveEnrichmentPhase(status, workerIsRunning = true))
    }

    @Test
    fun aTerminalPhaseWithALiveWorkerIsShownAsRunning() {
        // On-device trace: a new cycle's chunk applied at 10:31 while status still read "phase=PARTIAL,
        // finished_ms=..." - never show a finished/failed state while a cycle is genuinely in progress.
        listOf(
            GraphEnrichmentPhase.SUCCEEDED, GraphEnrichmentPhase.PARTIAL,
            GraphEnrichmentPhase.NO_DATA, GraphEnrichmentPhase.FAILED
        ).forEach { phase ->
            val status = GraphEnrichmentStatus(phase = phase, failure = GraphEnrichmentFailure.DEVICE_BUSY)
            assertEquals("phase=$phase", GraphEnrichmentPhase.RUNNING, effectiveEnrichmentPhase(status, workerIsRunning = true))
        }
    }

    @Test
    fun aTerminalPhaseWithNoLiveWorkerPassesThroughUnchanged() {
        listOf(GraphEnrichmentPhase.SUCCEEDED, GraphEnrichmentPhase.PARTIAL, GraphEnrichmentPhase.NO_DATA).forEach { phase ->
            val status = GraphEnrichmentStatus(phase = phase)
            assertEquals("phase=$phase", phase, effectiveEnrichmentPhase(status, workerIsRunning = false))
        }
    }

    @Test
    fun everyOtherPhasePassesThroughUnchanged() {
        val correctedByLiveWorker = setOf(
            GraphEnrichmentPhase.RUNNING, GraphEnrichmentPhase.FAILED, GraphEnrichmentPhase.SUCCEEDED,
            GraphEnrichmentPhase.PARTIAL, GraphEnrichmentPhase.NO_DATA
        )
        val untouched = GraphEnrichmentPhase.entries - correctedByLiveWorker
        untouched.forEach { phase ->
            val status = GraphEnrichmentStatus(phase = phase)
            assertEquals(phase, effectiveEnrichmentPhase(status, workerIsRunning = false))
            assertEquals(phase, effectiveEnrichmentPhase(status, workerIsRunning = true))
        }
    }
}
