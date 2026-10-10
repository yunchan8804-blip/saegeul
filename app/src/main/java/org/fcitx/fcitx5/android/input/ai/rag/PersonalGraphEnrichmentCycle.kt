/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

/**
 * Pure gating rule for when the on-device graph-enrichment worker may start a new cycle (export a
 * fresh sentence snapshot and begin chunking it).
 *
 * An automatic cycle starts when the personal graph has never been built, or at least
 * [MIN_NEW_SENTENCES] sentences have accumulated since the enrichment cursor ([PersonalGraphStore.GraphStats.enrichedThroughMs]) and at
 * least [MIN_INTERVAL_MS] has elapsed since the graph was built. A backlog of
 * [MAX_INCREMENTAL_SENTENCES] or more unprocessed sentences skips the interval, since one cycle only
 * takes that many and the rest would otherwise wait an hour per batch. A manual run (the dashboard's
 * "enrich now" button) does not consult this rule; the caller still separately checks whether there
 * is anything to enrich at all (`no_data`).
 *
 * No disk I/O; the caller passes in the derived numbers ([newSentenceCount] reads the in-memory
 * vault for the count).
 */
object PersonalGraphEnrichmentCycle {
    const val MIN_NEW_SENTENCES = 10
    const val MIN_INTERVAL_MS = 60L * 60 * 1000

    /** The most sentences one incremental cycle exports; also the backlog size that bypasses [MIN_INTERVAL_MS]. */
    const val MAX_INCREMENTAL_SENTENCES = 120

    /**
     * Sentences that still need enriching: those last seen after the enrichment cursor
     * ([enrichedThroughMs]). With no graph the cursor is 0, so this is every stored sentence.
     */
    fun newSentenceCount(vault: PersonalSentenceVault, enrichedThroughMs: Long): Int =
        vault.countSince(enrichedThroughMs)

    /** Why an automatic cycle does not start now, or [GraphEnrichmentSkipReason.NONE] when it does. */
    fun automaticSkipReason(
        hasExistingGraph: Boolean,
        newSentenceCount: Int,
        msSinceLastBuild: Long
    ): GraphEnrichmentSkipReason = when {
        newSentenceCount < MIN_NEW_SENTENCES -> GraphEnrichmentSkipReason.NOT_ENOUGH_NEW_SENTENCES
        !hasExistingGraph -> GraphEnrichmentSkipReason.NONE
        msSinceLastBuild >= MIN_INTERVAL_MS || newSentenceCount >= MAX_INCREMENTAL_SENTENCES ->
            GraphEnrichmentSkipReason.NONE
        else -> GraphEnrichmentSkipReason.TOO_SOON
    }
}
