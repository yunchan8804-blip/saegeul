/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.metrics

/**
 * Keeps the short-lived identity of candidates rendered for one contextual-prediction generation.
 * Candidate text is retained only while the input session is alive and is never persisted.
 */
class PredictionMetricsSession {

    data class Candidate(
        val generation: Long,
        val text: String,
        val source: String
    )

    private data class CandidateKey(
        val generation: Long,
        val text: String,
        val source: String
    )

    private var activeGeneration: Long? = null
    private val shown = mutableSetOf<CandidateKey>()
    private val accepted = mutableSetOf<CandidateKey>()

    /** Makes [generation] the only generation whose candidates can be counted. */
    fun activate(generation: Long) {
        if (activeGeneration == generation) return
        activeGeneration = generation
        shown.clear()
        accepted.clear()
    }

    /** Drops all ephemeral candidate identities, including when privacy disallows collection. */
    fun reset() {
        activeGeneration = null
        shown.clear()
        accepted.clear()
    }

    /** Returns true only for the first actual display of this candidate in the active generation. */
    fun recordShown(candidate: Candidate): Boolean {
        val key = candidate.keyOrNull() ?: return false
        return shown.add(key)
    }

    /**
     * Returns true only when a candidate was actually displayed and is selected once after a
     * successful editor commit.
     */
    fun recordAccepted(candidate: Candidate, committed: Boolean): Boolean {
        if (!committed) return false
        val key = candidate.keyOrNull() ?: return false
        return key in shown && accepted.add(key)
    }

    private fun Candidate.keyOrNull(): CandidateKey? {
        if (activeGeneration != generation) return null
        return CandidateKey(generation, text, source)
    }
}
