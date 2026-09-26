/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [decideLeaseLossOutcome]: what a manual/automatic graph-enrichment run does when
 * [GemmaGraphGenerationSession.generate] is interrupted mid-chunk (see its
 * [GemmaGraphGenerationSession.LeaseLostException] doc). On-device trace this fixes: the keyboard
 * appearing during a manual run threw a raw `CancellationException` out of the worker, which
 * `WorkManager` treated as a hard, non-retryable cancellation with no recorded reason ("paused for
 * an unknown reason" on the dashboard) instead of a brief pause-and-resume.
 */
class GemmaGraphEnrichmentWorkerLeaseLossTest {

    @Test
    fun manualInterruptedOnlyByTheKeyboardRetriesTheSameChunk() {
        assertEquals(
            LeaseLossOutcome.RETRY_SAME_CHUNK,
            decideLeaseLossOutcome(GemmaGenerationWaitReason.KEYBOARD_ACTIVE, manual = true)
        )
    }

    @Test
    fun automaticInterruptedByTheKeyboardPausesInsteadOfRetrying() {
        // Automatic never actually reaches this in practice (it requires the screen off, which
        // precludes the keyboard being shown), but must still resolve to a safe, reason-carrying
        // pause rather than looping if it ever did.
        assertEquals(
            LeaseLossOutcome.PAUSE,
            decideLeaseLossOutcome(GemmaGenerationWaitReason.KEYBOARD_ACTIVE, manual = false)
        )
    }

    @Test
    fun anyNonKeyboardReasonPausesRegardlessOfManual() {
        GemmaGenerationWaitReason.entries.filter { it != GemmaGenerationWaitReason.KEYBOARD_ACTIVE }.forEach { reason ->
            assertEquals("reason=$reason manual=true", LeaseLossOutcome.PAUSE, decideLeaseLossOutcome(reason, manual = true))
            assertEquals("reason=$reason manual=false", LeaseLossOutcome.PAUSE, decideLeaseLossOutcome(reason, manual = false))
        }
    }

    @Test
    fun aTransientRaceWithNoReasonLeftRetriesImmediately() {
        assertEquals(LeaseLossOutcome.RETRY_SAME_CHUNK, decideLeaseLossOutcome(null, manual = true))
        assertEquals(LeaseLossOutcome.RETRY_SAME_CHUNK, decideLeaseLossOutcome(null, manual = false))
    }
}
