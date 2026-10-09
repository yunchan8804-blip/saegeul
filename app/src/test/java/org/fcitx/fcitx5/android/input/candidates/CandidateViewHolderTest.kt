/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder.Companion.shouldUpdateUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateViewHolderTest {

    private val word = CandidateWord(label = "1", text = "회의", comment = "")
    private val other = CandidateWord(label = "2", text = "회", comment = "")

    @Test
    fun `the same candidate in the same slot keeps its drawn ui`() {
        assertFalse(shouldUpdateUi(word, oldFeatured = false, newCandidate = word, newFeatured = false))
        assertFalse(shouldUpdateUi(word, oldFeatured = true, newCandidate = word, newFeatured = true))
    }

    @Test
    fun `a different candidate is redrawn`() {
        assertTrue(shouldUpdateUi(word, oldFeatured = false, newCandidate = other, newFeatured = false))
    }

    @Test
    fun `the same candidate moving into the first slot is redrawn bold`() {
        assertTrue(shouldUpdateUi(word, oldFeatured = false, newCandidate = word, newFeatured = true))
    }

    @Test
    fun `the same candidate leaving the first slot is redrawn regular`() {
        assertTrue(shouldUpdateUi(word, oldFeatured = true, newCandidate = word, newFeatured = false))
    }
}
