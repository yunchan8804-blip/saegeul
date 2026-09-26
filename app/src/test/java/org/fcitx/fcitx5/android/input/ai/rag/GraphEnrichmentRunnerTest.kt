/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEnrichmentRunnerTest {

    @Test
    fun runningFlagReflectsMarkRunningAndMarkStopped() {
        GraphEnrichmentRunner.markStopped()
        assertFalse(GraphEnrichmentRunner.isRunning())

        GraphEnrichmentRunner.markRunning()
        assertTrue(GraphEnrichmentRunner.isRunning())

        GraphEnrichmentRunner.markStopped()
        assertFalse(GraphEnrichmentRunner.isRunning())
    }
}
