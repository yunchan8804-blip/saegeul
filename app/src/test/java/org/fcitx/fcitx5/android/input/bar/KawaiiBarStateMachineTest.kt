/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Candidate
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Idle
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Title
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.junit.Assert.assertEquals
import org.junit.Test

class KawaiiBarStateMachineTest {

    @Test
    fun testContextualPredictionRetention() {
        var currentState: KawaiiBarStateMachine.State = Idle
        val sm = KawaiiBarStateMachine.new { currentState = it }

        assertEquals(Idle, currentState)

        // 1. Contextual candidates arrived while preedit is empty
        sm.push(CandidatesUpdated, CandidateEmpty to false)
        assertEquals(Candidate, currentState)

        // 2. Preedit is updated to empty (e.g. after committing a word)
        // Candidates are still not empty, so state MUST remain in Candidate!
        sm.push(PreeditUpdated, PreeditEmpty to true)
        assertEquals(Candidate, currentState)

        // 3. When candidates finally become empty and preedit is empty -> transits to Idle
        sm.push(CandidatesUpdated, CandidateEmpty to true)
        assertEquals(Idle, currentState)
    }

    @Test
    fun testWindowAttachmentAndDetachment() {
        var currentState: KawaiiBarStateMachine.State = Idle
        val sm = KawaiiBarStateMachine.new { currentState = it }

        sm.push(CandidatesUpdated, CandidateEmpty to false)
        assertEquals(Candidate, currentState)

        // Attach extended window
        sm.push(ExtendedWindowAttached)
        assertEquals(Title, currentState)

        // Detach extended window with candidates still present
        sm.push(WindowDetached)
        assertEquals(Candidate, currentState)

        // Now clear candidates
        sm.push(CandidatesUpdated, CandidateEmpty to true)
        assertEquals(Idle, currentState)

        // Attach extended window from Idle
        sm.push(ExtendedWindowAttached)
        assertEquals(Title, currentState)

        // Detach extended window with no candidates
        sm.push(WindowDetached)
        assertEquals(Idle, currentState)
    }
}
