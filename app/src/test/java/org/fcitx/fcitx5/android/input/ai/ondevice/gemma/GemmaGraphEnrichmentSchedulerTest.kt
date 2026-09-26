/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [decideManualGraphEnrichmentAction]. Two on-device traces motivated this:
 * a manual run stuck in exponential backoff (`ENQUEUED`) being treated as "already pending" and
 * silently ignoring the button, and `WorkManager` briefly reporting a run the process was still
 * actually executing as `ENQUEUED` right after the OS called `onStopJob` on it - which made
 * `REPLACE` kill a live run and restart from scratch.
 */
class GemmaGraphEnrichmentSchedulerTest {

    @Test
    fun noExistingWorkEnqueuesANewOne() {
        assertEquals(
            ManualGraphEnrichmentAction.ENQUEUE_NEW,
            decideManualGraphEnrichmentAction(emptyList(), workerActuallyRunning = false)
        )
    }

    @Test
    fun aRunningManualWorkIsLeftAlone() {
        assertEquals(
            ManualGraphEnrichmentAction.ALREADY_RUNNING,
            decideManualGraphEnrichmentAction(listOf(WorkInfo.State.RUNNING), workerActuallyRunning = false)
        )
    }

    @Test
    fun anEnqueuedManualWorkIncludingOneWaitingOutABackoffIsReplaced() {
        assertEquals(
            ManualGraphEnrichmentAction.REPLACE,
            decideManualGraphEnrichmentAction(listOf(WorkInfo.State.ENQUEUED), workerActuallyRunning = false)
        )
    }

    @Test
    fun aBlockedManualWorkIsReplaced() {
        assertEquals(
            ManualGraphEnrichmentAction.REPLACE,
            decideManualGraphEnrichmentAction(listOf(WorkInfo.State.BLOCKED), workerActuallyRunning = false)
        )
    }

    @Test
    fun aFinishedManualWorkIsTreatedAsNoExistingWork() {
        listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED).forEach { state ->
            assertEquals(
                "state=$state",
                ManualGraphEnrichmentAction.ENQUEUE_NEW,
                decideManualGraphEnrichmentAction(listOf(state), workerActuallyRunning = false)
            )
        }
    }

    @Test
    fun runningTakesPriorityOverAnEnqueuedEntryInTheSameList() {
        assertEquals(
            ManualGraphEnrichmentAction.ALREADY_RUNNING,
            decideManualGraphEnrichmentAction(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING), workerActuallyRunning = false)
        )
    }

    @Test
    fun workerActuallyRunningForcesAlreadyRunningEvenWhenWorkManagerReportsEnqueued() {
        assertEquals(
            ManualGraphEnrichmentAction.ALREADY_RUNNING,
            decideManualGraphEnrichmentAction(listOf(WorkInfo.State.ENQUEUED), workerActuallyRunning = true)
        )
    }

    @Test
    fun workerActuallyRunningForcesAlreadyRunningEvenWithNoReportedWorkAtAll() {
        assertEquals(
            ManualGraphEnrichmentAction.ALREADY_RUNNING,
            decideManualGraphEnrichmentAction(emptyList(), workerActuallyRunning = true)
        )
    }
}
