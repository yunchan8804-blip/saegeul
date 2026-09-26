/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CandidateItemUi.shouldRenderPill] decides the sentence-row pill chip vs. flat-text styling
 * without touching any Android/Theme API, so it can be exercised directly here (this module has
 * no Robolectric dependency; see gradle test config).
 */
class CandidateItemUiPillTest {

    @Test
    fun `sentence row ai badge candidates render as a pill`() {
        assertTrue(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = "Gemma 생성"))
        assertTrue(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = "Gemma 이어쓰기"))
        // English locale translations of the same comments must keep triggering the pill.
        assertTrue(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = "Gemma Generated"))
        assertTrue(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = "Gemma Continuation"))
    }

    @Test
    fun `word row candidates never render as a pill`() {
        assertFalse(CandidateItemUi.shouldRenderPill(isSentenceRow = false, comment = "Gemma 생성"))
        assertFalse(CandidateItemUi.shouldRenderPill(isSentenceRow = false, comment = "Gemma Generated"))
    }

    @Test
    fun `sentence row candidates without a badge fall back to flat text`() {
        assertFalse(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = "일반 문장"))
        assertFalse(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = null))
        assertFalse(CandidateItemUi.shouldRenderPill(isSentenceRow = true, comment = ""))
    }
}
