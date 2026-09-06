/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEnrichAutoPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `disabled setting never runs`() {
        assertFalse(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = false,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 200,
                sourceSentenceCount = 0,
                builtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `network gate closed never runs`() {
        assertFalse(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = false,
                inFlight = false,
                vaultSentences = 200,
                sourceSentenceCount = 0,
                builtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `already running never runs again`() {
        assertFalse(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = true,
                vaultSentences = 200,
                sourceSentenceCount = 0,
                builtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `29 new sentences is not enough`() {
        assertFalse(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 129,
                sourceSentenceCount = 100,
                builtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `30 new sentences with no prior enrichment runs`() {
        assertTrue(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 130,
                sourceSentenceCount = 100,
                builtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `enough new sentences but within interval does not run`() {
        val builtMs = now - 60L * 60 * 1000
        assertFalse(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 150,
                sourceSentenceCount = 100,
                builtMs = builtMs,
                nowMs = now
            )
        )
    }

    @Test
    fun `enough new sentences past interval runs`() {
        val builtMs = now - 25L * 60 * 60 * 1000
        assertTrue(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 150,
                sourceSentenceCount = 100,
                builtMs = builtMs,
                nowMs = now
            )
        )
    }

    @Test
    fun `never enriched before with enough new sentences runs regardless of interval`() {
        assertTrue(
            GraphEnrichAutoPolicy.shouldRun(
                enabled = true,
                networkAllowed = true,
                inFlight = false,
                vaultSentences = 30,
                sourceSentenceCount = 0,
                builtMs = 0L,
                nowMs = now
            )
        )
    }
}
