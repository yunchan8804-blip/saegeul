/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitEmotionSearchTest {
    @Test
    fun privatePolicyBlocksBeforeReturningAnyCandidates() {
        val outcome = ExplicitEmotionSearch.search(allowed = false, explicitQuery = "감사")

        assertSame(ExplicitEmotionSearchOutcome.Blocked, outcome)
    }

    @Test
    fun onlyExplicitQueryProducesLocalCandidates() {
        val blank = ExplicitEmotionSearch.search(true, "") as ExplicitEmotionSearchOutcome.Results
        val explicit = ExplicitEmotionSearch.search(true, "감사") as ExplicitEmotionSearchOutcome.Results

        assertTrue(blank.items.isEmpty())
        assertTrue(explicit.items.isNotEmpty())
    }

    @Test
    fun commitIsRejectedByPolicyAndEditorIdentityWithoutCallingAction() {
        val gate = EmotionCommitGate()
        var calls = 0
        val action = { calls++; true }

        assertEquals(EmotionCommitResult.Blocked, gate.commit(false, true, action))
        assertEquals(EmotionCommitResult.StaleEditor, gate.commit(true, false, action))
        assertEquals(0, calls)
    }

    @Test
    fun successfulTapCommitsExactlyOnce() {
        val gate = EmotionCommitGate()
        var calls = 0
        val action = { calls++; true }

        assertEquals(EmotionCommitResult.Success, gate.commit(true, true, action))
        assertEquals(EmotionCommitResult.AlreadyCommitted, gate.commit(true, true, action))
        assertEquals(1, calls)
    }

    @Test
    fun failedCommitCanBeExplicitlyRetried() {
        val gate = EmotionCommitGate()
        var calls = 0

        assertEquals(EmotionCommitResult.Failed, gate.commit(true, true) { calls++; false })
        assertEquals(EmotionCommitResult.Success, gate.commit(true, true) { calls++; true })
        assertEquals(2, calls)
    }
}
