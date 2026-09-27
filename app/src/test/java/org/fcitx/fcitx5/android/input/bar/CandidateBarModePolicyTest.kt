/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.bar.CandidateBarModePolicy.SingleRowContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5 (design.md, 사용자 확정 2026-09-26): landscape while not thumb-split forces the fixed 48dp
 * single-row candidate bar; every other combination keeps the portrait fixed-97dp contract.
 */
class CandidateBarModePolicyTest {

    @Test
    fun `landscape without split uses the single row`() {
        assertTrue(CandidateBarModePolicy.isHorizontalSingleRow(landscape = true, thumbSplitActive = false))
    }

    @Test
    fun `landscape while split stays on the vertical rule`() {
        assertFalse(CandidateBarModePolicy.isHorizontalSingleRow(landscape = true, thumbSplitActive = true))
    }

    @Test
    fun `portrait never uses the single row regardless of split`() {
        assertFalse(CandidateBarModePolicy.isHorizontalSingleRow(landscape = false, thumbSplitActive = false))
        assertFalse(CandidateBarModePolicy.isHorizontalSingleRow(landscape = false, thumbSplitActive = true))
    }

    @Test
    fun `single row shows the sentence chip alongside words when a sentence exists`() {
        assertEquals(
            SingleRowContent.SENTENCE_AND_WORDS,
            CandidateBarModePolicy.singleRowContent(hasSentence = true, hasWords = true)
        )
        assertEquals(
            SingleRowContent.SENTENCE_AND_WORDS,
            CandidateBarModePolicy.singleRowContent(hasSentence = true, hasWords = false)
        )
    }

    @Test
    fun `single row shows words only when there is no sentence`() {
        assertEquals(
            SingleRowContent.WORDS_ONLY,
            CandidateBarModePolicy.singleRowContent(hasSentence = false, hasWords = true)
        )
    }

    @Test
    fun `single row falls back to the placeholder chip when both are empty`() {
        assertEquals(
            SingleRowContent.PLACEHOLDER,
            CandidateBarModePolicy.singleRowContent(hasSentence = false, hasWords = false)
        )
    }

    @Test
    fun `landscape single row height wins over every other height flag`() {
        // All of the "other mode" flags set at once; singleRowLandscape must still short-circuit.
        assertEquals(
            48,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = true,
                candidateRowFixedHeight = true,
                twoRowWithAutomaticCandidates = true,
                hintOrStatusRowVisible = true,
                isCandidateTwoRow = true,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }

    @Test
    fun `portrait fixed two-row height is 97dp`() {
        assertEquals(
            97,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = false,
                candidateRowFixedHeight = true,
                twoRowWithAutomaticCandidates = false,
                hintOrStatusRowVisible = false,
                isCandidateTwoRow = false,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }

    @Test
    fun `two-row with automatic candidates also gets the 97dp height`() {
        assertEquals(
            97,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = false,
                candidateRowFixedHeight = false,
                twoRowWithAutomaticCandidates = true,
                hintOrStatusRowVisible = false,
                isCandidateTwoRow = false,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }

    @Test
    fun `hint or status row visible uses the 77dp height`() {
        assertEquals(
            77,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = false,
                candidateRowFixedHeight = false,
                twoRowWithAutomaticCandidates = false,
                hintOrStatusRowVisible = true,
                isCandidateTwoRow = false,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }

    @Test
    fun `legacy two-row without automatic candidates uses the compact two-row height`() {
        assertEquals(
            60,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = false,
                candidateRowFixedHeight = false,
                twoRowWithAutomaticCandidates = false,
                hintOrStatusRowVisible = false,
                isCandidateTwoRow = true,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }

    @Test
    fun `single row merges the compact idle toolbar into the candidate row`() {
        assertTrue(
            CandidateBarModePolicy.isSingleRowMerged(
                singleRowLandscape = true,
                candidateRowVisible = true,
                idleToolbarCompact = true
            )
        )
    }

    @Test
    fun `single row does not merge when the idle toolbar is expanded or showing other content`() {
        assertFalse(
            CandidateBarModePolicy.isSingleRowMerged(
                singleRowLandscape = true,
                candidateRowVisible = true,
                idleToolbarCompact = false
            )
        )
    }

    @Test
    fun `single row does not merge while idle with no candidates`() {
        assertFalse(
            CandidateBarModePolicy.isSingleRowMerged(
                singleRowLandscape = true,
                candidateRowVisible = false,
                idleToolbarCompact = true
            )
        )
    }

    @Test
    fun `portrait never merges regardless of candidate visibility or idle state`() {
        assertFalse(
            CandidateBarModePolicy.isSingleRowMerged(
                singleRowLandscape = false,
                candidateRowVisible = true,
                idleToolbarCompact = true
            )
        )
    }

    @Test
    fun `merged tool row height collapses to zero`() {
        assertEquals(0, CandidateBarModePolicy.toolRowHeightDp(singleRowMerged = true, toolbarHeightDp = 48))
    }

    @Test
    fun `unmerged tool row height passes through unchanged`() {
        assertEquals(48, CandidateBarModePolicy.toolRowHeightDp(singleRowMerged = false, toolbarHeightDp = 48))
        assertEquals(96, CandidateBarModePolicy.toolRowHeightDp(singleRowMerged = false, toolbarHeightDp = 96))
    }

    @Test
    fun `single row with nothing active falls back to the unit height`() {
        assertEquals(
            48,
            CandidateBarModePolicy.candidateRowHeightDp(
                singleRowLandscape = false,
                candidateRowFixedHeight = false,
                twoRowWithAutomaticCandidates = false,
                hintOrStatusRowVisible = false,
                isCandidateTwoRow = false,
                unitHeightDp = 48,
                twoRowHeightDp = 60,
                hintStatusHeightDp = 77
            )
        )
    }
}
