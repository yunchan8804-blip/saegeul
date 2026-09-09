/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.metrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionMetricsSessionTest {

    @Test
    fun `same candidate is shown once across two rows and rerenders`() {
        val session = PredictionMetricsSession()
        val candidate = PredictionMetricsSession.Candidate(10, "어떻게", "personal_ngram")

        session.activate(10)

        assertTrue(session.recordShown(candidate))
        assertFalse(session.recordShown(candidate))
        assertFalse(session.recordShown(candidate))
    }

    @Test
    fun `unseen candidate cannot be accepted`() {
        val session = PredictionMetricsSession()
        val candidate = PredictionMetricsSession.Candidate(10, "하면", "personal_ngram")

        session.activate(10)

        assertFalse(session.recordAccepted(candidate, committed = true))
    }

    @Test
    fun `failed commit leaves candidate unaccepted`() {
        val session = PredictionMetricsSession()
        val candidate = PredictionMetricsSession.Candidate(10, "잘못", "personal_ngram")

        session.activate(10)
        assertTrue(session.recordShown(candidate))

        assertFalse(session.recordAccepted(candidate, committed = false))
        assertTrue(session.recordAccepted(candidate, committed = true))
        assertFalse(session.recordAccepted(candidate, committed = true))
    }

    @Test
    fun `generation change rejects stale candidate and permits current candidate`() {
        val session = PredictionMetricsSession()
        val oldCandidate = PredictionMetricsSession.Candidate(10, "그렇게", "personal_ngram")
        val currentCandidate = PredictionMetricsSession.Candidate(11, "어떻게", "rag_personal")

        session.activate(10)
        assertTrue(session.recordShown(oldCandidate))
        session.activate(11)

        assertFalse(session.recordAccepted(oldCandidate, committed = true))
        assertTrue(session.recordShown(currentCandidate))
        assertTrue(session.recordAccepted(currentCandidate, committed = true))
    }

    @Test
    fun `source remains part of the candidate identity`() {
        val session = PredictionMetricsSession()
        val personal = PredictionMetricsSession.Candidate(10, "어떻게", "personalized_style")
        val retrieval = PredictionMetricsSession.Candidate(10, "어떻게", "rag_personal")

        session.activate(10)

        assertTrue(session.recordShown(personal))
        assertTrue(session.recordShown(retrieval))
        assertTrue(session.recordAccepted(retrieval, committed = true))
        assertTrue(session.recordAccepted(personal, committed = true))
    }

    @Test
    fun `privacy reset rejects prior candidates`() {
        val session = PredictionMetricsSession()
        val candidate = PredictionMetricsSession.Candidate(10, "어떻게", "personal_ngram")

        session.activate(10)
        assertTrue(session.recordShown(candidate))
        session.reset()

        assertFalse(session.recordAccepted(candidate, committed = true))
    }
}
