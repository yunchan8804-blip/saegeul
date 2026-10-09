/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class OnDeviceContextCompletionDrainTest {

    @Test
    fun `a global lease held by another purpose does not stall the drain of a run that never got a lease`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        val warmHolder = requireNotNull(
            OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.AUTO_CONTEXT) {}
        )
        try {
            assertTrue(OnDeviceGenerationControl.isGenerating)
            val startedAt = System.nanoTime()

            val stopped = runBlocking {
                awaitOwnGenerationStopped(
                    generation = Job().apply { complete() },
                    isOwnGenerationRunning = { false },
                    budgetMs = 2_000L,
                    pollIntervalMs = 10L
                )
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(stopped)
            assertTrue("elapsedMs=$elapsedMs", elapsedMs < 1_000L)
            assertTrue(OnDeviceGenerationControl.isGenerating)
        } finally {
            OnDeviceGenerationControl.end(warmHolder)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `the drain waits for the run's own generation and returns once it stops`() {
        val ownRunning = AtomicBoolean(true)
        val generation = CompletableDeferred<Unit>()

        val stopped = runBlocking {
            launch {
                delay(100L)
                generation.complete(Unit)
                delay(100L)
                ownRunning.set(false)
            }
            awaitOwnGenerationStopped(
                generation = generation,
                isOwnGenerationRunning = ownRunning::get,
                budgetMs = 5_000L,
                pollIntervalMs = 10L
            )
        }

        assertTrue(stopped)
        assertFalse(ownRunning.get())
    }

    @Test
    fun `the drain reports a timeout when the run's own generation never stops`() {
        val stopped = runBlocking {
            awaitOwnGenerationStopped(
                generation = Job().apply { complete() },
                isOwnGenerationRunning = { true },
                budgetMs = 150L,
                pollIntervalMs = 10L
            )
        }

        assertFalse(stopped)
    }
}
