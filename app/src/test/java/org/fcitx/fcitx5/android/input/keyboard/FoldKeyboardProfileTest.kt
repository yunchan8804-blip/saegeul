/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldKeyboardProfileTest {
    private val preferences = ThumbSplitPreferences(
        compactEnabled = false,
        expandedEnabled = true,
        compactPortraitGapDp = 48,
        compactLandscapeGapDp = 72,
        expandedPortraitGapDp = 96,
        expandedLandscapeGapDp = 128
    )

    @Test
    fun `fold cover and ordinary phone landscape stay compact`() {
        assertEquals(KeyboardScreenProfile.Compact, FoldKeyboardProfileResolver.classify(360, 780))
        assertEquals(KeyboardScreenProfile.Compact, FoldKeyboardProfileResolver.classify(840, 390))
    }

    @Test
    fun `unfolded and tablet viewports require both dimensions at least 600dp`() {
        assertEquals(KeyboardScreenProfile.Compact, FoldKeyboardProfileResolver.classify(599, 900))
        assertEquals(KeyboardScreenProfile.Expanded, FoldKeyboardProfileResolver.classify(600, 900))
        assertEquals(KeyboardScreenProfile.Expanded, FoldKeyboardProfileResolver.classify(900, 600))
    }

    @Test
    fun `unknown viewport fails safe to normal layout`() {
        val resolved = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(0, 900, Configuration.ORIENTATION_PORTRAIT),
            preferences
        )
        assertEquals(KeyboardScreenProfile.Unknown, resolved.screenProfile)
        assertFalse(resolved.enabled)
        assertEquals(0, resolved.centerGapDp)
    }

    @Test
    fun `cover and unfolded toggles are independent`() {
        val cover = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(390, 850, Configuration.ORIENTATION_PORTRAIT),
            preferences
        )
        val unfolded = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(717, 900, Configuration.ORIENTATION_PORTRAIT),
            preferences
        )
        assertFalse(cover.enabled)
        assertTrue(unfolded.enabled)
        assertEquals(96, unfolded.centerGapDp)
    }

    @Test
    fun `portrait and landscape gaps use separate saved values`() {
        val portrait = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(700, 900, Configuration.ORIENTATION_PORTRAIT),
            preferences
        )
        val landscape = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(900, 700, Configuration.ORIENTATION_LANDSCAPE),
            preferences
        )
        assertEquals(96, portrait.centerGapDp)
        assertEquals(128, landscape.centerGapDp)
    }

    @Test
    fun `compact profile keeps its own portrait and landscape gaps`() {
        val compactPreferences = preferences.copy(compactEnabled = true)
        val portrait = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(390, 850, Configuration.ORIENTATION_PORTRAIT),
            compactPreferences
        )
        val landscape = FoldKeyboardProfileResolver.resolve(
            KeyboardViewport(850, 390, Configuration.ORIENTATION_LANDSCAPE),
            compactPreferences
        )
        assertEquals(48, portrait.centerGapDp)
        assertEquals(72, landscape.centerGapDp)
    }

    @Test
    fun `center gap shrinks positive key widths without changing fill keys`() {
        val row = ThumbSplitLayoutCalculator.calculate(
            listOf(0.1f, 0.1f, 0f, 0.1f, 0.1f),
            parentWidthPx = 1000,
            requestedGapPx = 100
        )
        assertEquals(2, row.boundaryIndex)
        assertEquals(100, row.gapPx)
        assertEquals(0f, row.percentWidths[2])
        assertEquals(0.3f, row.percentWidths.sum(), 0.0001f)
    }

    @Test
    fun `center gap is capped for safe touch targets`() {
        val row = ThumbSplitLayoutCalculator.calculate(
            List(10) { 0.1f },
            parentWidthPx = 600,
            requestedGapPx = 500
        )
        assertEquals(200, row.gapPx)
        assertEquals(2f / 3f, row.percentWidths.sum(), 0.0001f)
    }
}
