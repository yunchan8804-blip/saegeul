/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

/**
 * Pure gating rule for when the on-device graph-enrichment worker may start a new cycle (export a
 * fresh sentence snapshot and begin chunking it).
 *
 * Automatic ([manual] = false): the personal graph has never been built, or at least
 * [MIN_NEW_SENTENCES] sentences have accumulated since it was and at least [MIN_INTERVAL_MS] has
 * elapsed since it was built. Manual ([manual] = true, the dashboard's "enrich now" button):
 * always starts a cycle - the user explicitly asked for one, and it should not silently no-op
 * because the automatic thresholds have not been met yet. The caller still separately checks
 * whether there is anything to enrich at all (`no_data`).
 *
 * No disk I/O; the caller passes in the derived numbers ([newSentenceCount] reads the in-memory
 * vault for the count).
 */
object PersonalGraphEnrichmentCycle {
    const val MIN_NEW_SENTENCES = 10
    const val MIN_INTERVAL_MS = 60L * 60 * 1000

    /**
     * Sentences that still need enriching: every stored sentence while no graph exists yet,
     * otherwise those last seen after the graph was built ([graphBuiltMs]).
     */
    fun newSentenceCount(hasExistingGraph: Boolean, vault: PersonalSentenceVault, graphBuiltMs: Long): Int =
        if (hasExistingGraph) vault.countSince(graphBuiltMs) else vault.stats().sentences

    fun shouldStart(
        hasExistingGraph: Boolean,
        newSentenceCount: Int,
        msSinceLastBuild: Long,
        manual: Boolean
    ): Boolean =
        manual || (newSentenceCount >= MIN_NEW_SENTENCES && (!hasExistingGraph || msSinceLastBuild >= MIN_INTERVAL_MS))
}
