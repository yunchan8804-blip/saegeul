/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.Companion.COLLECTION_FEEDBACK_DISPLAY_MS
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.Companion.hasHiddenNativeCandidates
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.Companion.isCollectionFeedbackFresh
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.Companion.nativeExpandedOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalCandidateExpandedOffsetTest {

    private fun chip(text: String, comment: String = "") = CandidateWord(label = "", text = text, comment = comment)

    private fun offsetOf(visible: List<CandidateWord>, native: List<CandidateWord>): Int =
        nativeExpandedOffset(visible) { chip -> native.indexOfFirst { it === chip } }

    @Test
    fun `automatic chips in front of native chips do not shift the offset`() {
        val auto1 = chip("자동1", "Gemma")
        val auto2 = chip("자동2", "Gemma")
        val n0 = chip("가")
        val n1 = chip("나")
        val n2 = chip("다")

        assertEquals(3, offsetOf(listOf(auto1, auto2, n0, n1, n2), listOf(n0, n1, n2)))
    }

    @Test
    fun `a typo correction chip between native chips is not counted`() {
        val n0 = chip("회으")
        val correction = chip("회의", "✏️")
        val n1 = chip("회")

        assertEquals(2, offsetOf(listOf(n0, correction, n1), listOf(n0, n1)))
    }

    @Test
    fun `a contextual chip equal in value to a native chip is told apart by identity`() {
        val native = chip("회의")
        val contextual = chip("회의")

        assertEquals(1, offsetOf(listOf(contextual, native), listOf(native)))
    }

    @Test
    fun `a native candidate hidden by an automatic chip does not break the offset`() {
        val n0 = chip("가")
        val n1 = chip("나")
        val auto = chip("나", "Gemma")
        val n2 = chip("다")

        assertEquals(3, offsetOf(listOf(n0, auto, n2), listOf(n0, n1, n2)))
    }

    @Test
    fun `a hidden trailing native candidate starts the expanded window`() {
        val n0 = chip("가")
        val n1 = chip("나")
        val auto = chip("나", "Gemma")

        assertEquals(1, offsetOf(listOf(n0, auto), listOf(n0, n1)))
    }

    @Test
    fun `only automatic chips visible starts the expanded window at the first native candidate`() {
        val auto = chip("나", "Gemma")

        assertEquals(0, offsetOf(listOf(auto), listOf(chip("나"), chip("다"))))
    }

    @Test
    fun `no visible chips leaves the offset at zero`() {
        assertEquals(0, offsetOf(emptyList(), listOf(chip("가"))))
    }

    @Test
    fun `native candidates beyond the visible ones keep the expand button`() {
        assertTrue(hasHiddenNativeCandidates(nativeTotal = 10, nativeOffset = 3))
    }

    @Test
    fun `native candidates left past the window offset have a next page`() {
        assertTrue(hasHiddenNativeCandidates(nativeTotal = 5, nativeOffset = 3))
    }

    @Test
    fun `every native candidate visible hides the expand button even with extra chips`() {
        assertFalse(hasHiddenNativeCandidates(nativeTotal = 3, nativeOffset = 3))
        assertFalse(hasHiddenNativeCandidates(nativeTotal = 0, nativeOffset = 0))
    }

    @Test
    fun `an unreported native total keeps the expand button`() {
        assertTrue(hasHiddenNativeCandidates(nativeTotal = -1, nativeOffset = 0))
        assertTrue(hasHiddenNativeCandidates(nativeTotal = -1, nativeOffset = 5))
    }

    @Test
    fun `a collection feedback event within the display time is fresh`() {
        assertTrue(isCollectionFeedbackFresh(atMs = 10_000L, nowMs = 10_000L))
        assertTrue(isCollectionFeedbackFresh(atMs = 10_000L, nowMs = 10_000L + COLLECTION_FEEDBACK_DISPLAY_MS - 1))
    }

    @Test
    fun `a collection feedback event older than the display time is stale`() {
        assertFalse(isCollectionFeedbackFresh(atMs = 10_000L, nowMs = 10_000L + COLLECTION_FEEDBACK_DISPLAY_MS))
        assertFalse(isCollectionFeedbackFresh(atMs = 10_000L, nowMs = 10_000L + 3 * 60 * 60 * 1000L))
    }
}
