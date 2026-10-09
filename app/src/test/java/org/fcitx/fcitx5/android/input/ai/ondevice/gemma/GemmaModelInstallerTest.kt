/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaModelInstallerTest {

    private val calls = mutableListOf<String>()

    private fun applyDefaults(state: GemmaAccumulationState) = runBlocking {
        GemmaModelInstaller.applyInstallDefaults(
            loadAccumulationState = { state },
            enableAccumulation = { calls += "accumulation" },
            enableGraphEnrichment = { calls += "graph" }
        )
    }

    @Test
    fun `a finished install turns automatic learning on for a user who never chose`() {
        applyDefaults(GemmaAccumulationState(enabled = false, automaticLearningUserOptOut = false))

        assertEquals(listOf("accumulation", "graph"), calls)
    }

    @Test
    fun `a finished install leaves automatic learning off for a user who turned it off`() {
        applyDefaults(GemmaAccumulationState(enabled = false, automaticLearningUserOptOut = true))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a finished install does not touch automatic learning that is already on`() {
        applyDefaults(GemmaAccumulationState(enabled = true, automaticLearningUserOptOut = false))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `deleting the model asks the shared engine to close`() {
        val reasons = mutableListOf<String>()

        GemmaModelInstaller.releaseModelUsers { reasons += it }

        assertEquals(listOf("MODEL_DELETED"), reasons)
        assertEquals(GemmaModelInstaller.ENGINE_CLOSE_REASON_MODEL_DELETED, reasons.single())
    }
}
