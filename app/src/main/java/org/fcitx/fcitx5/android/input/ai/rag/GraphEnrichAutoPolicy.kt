/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

/**
 * Pure decision policy for automatic personal knowledge graph enrichment. Decides whether the
 * background enrichment pass should run right now, given the user's setting, network gate,
 * concurrency state, and how much new material has accumulated since the last enrichment.
 */
object GraphEnrichAutoPolicy {

    /** Minimum number of newly committed sentences (since the last enrichment) required to run. */
    const val MIN_NEW_SENTENCES = 30

    /** Minimum time since the last enrichment before running again, once one has ever run. */
    const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * Returns whether automatic graph enrichment should run now.
     *
     * @param enabled user's "지식 그래프 자동 강화" toggle.
     * @param networkAllowed whether network-dependent AI features are currently permitted.
     * @param inFlight whether an enrichment run is already in progress.
     * @param vaultSentences current number of sentences stored in [PersonalSentenceVault].
     * @param sourceSentenceCount number of sentences the graph was last built from.
     * @param builtMs timestamp of the last successful enrichment, or 0 if never run.
     * @param nowMs current time.
     */
    fun shouldRun(
        enabled: Boolean,
        networkAllowed: Boolean,
        inFlight: Boolean,
        vaultSentences: Int,
        sourceSentenceCount: Int,
        builtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!enabled || !networkAllowed || inFlight) return false
        if (vaultSentences - sourceSentenceCount < MIN_NEW_SENTENCES) return false
        if (builtMs > 0L && nowMs - builtMs < MIN_INTERVAL_MS) return false
        return true
    }
}
