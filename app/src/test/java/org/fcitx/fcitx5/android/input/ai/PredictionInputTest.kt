/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionInputTest {

    @Test
    fun strokeAfterTabIsSeparatedFromThePreviousWord() {
        assertEquals("회의 참", PredictionInput("참", "회의\t").currentSentence)
    }

    @Test
    fun strokeAfterNoBreakAndIdeographicSpacesIsSeparatedFromThePreviousWord() {
        assertEquals("회의 참", PredictionInput("참", "회의 ").currentSentence)
        assertEquals("회의 참", PredictionInput("참", "회의　").currentSentence)
    }

    @Test
    fun strokeAfterSpaceAndNewlineKeepsBeingSeparated() {
        assertEquals("회의 참", PredictionInput("참", "회의 ").currentSentence)
        assertEquals("참", PredictionInput("참", "회의.\n").currentSentence)
    }

    @Test
    fun strokeGluedToTheWordStaysGlued() {
        assertEquals("회의참", PredictionInput("참", "회의").currentSentence)
        assertEquals("오늘 회의 참", PredictionInput("참", "오늘 회의 참").currentSentence)
    }

    @Test
    fun trailingSpaceCoversEveryWhitespaceKind() {
        for (space in listOf(" ", "\n", "\t", "\r", " ", "　")) {
            assertTrue("[$space]", PredictionInput("", "회의$space").hasTrailingSpace)
        }
        assertFalse(PredictionInput("", "회의").hasTrailingSpace)
        assertFalse(PredictionInput("", "").hasTrailingSpace)
    }

    @Test
    fun lastWordIsFoundAcrossEveryWhitespaceKind() {
        for (space in listOf(" ", "\n", "\t", "\r", " ", "　")) {
            val input = PredictionInput("", "오늘${space}회의$space")
            assertEquals("[$space]", "회의", input.lastWordInContext)
            assertEquals("[$space]", "회의", input.effectiveLastWord)
        }
    }

    @Test
    fun effectiveLastWordSkipsTheStrokeStillBeingTyped() {
        assertEquals("회의", PredictionInput("참", "오늘 회의 참").effectiveLastWord)
        assertEquals("회의", PredictionInput("참", "오늘 회의\t참").effectiveLastWord)
    }

    @Test
    fun typoTargetAfterTabOrNoBreakSpaceReplacesTheWordAndItsWhitespace() {
        val afterTab = PredictionInput("", "오늘 회으\t").typoTarget
        assertEquals("회으", afterTab?.typed)
        assertEquals(3, afterTab?.replaceLength)

        val afterNbsp = PredictionInput("", "오늘 회으 ").typoTarget
        assertEquals("회으", afterNbsp?.typed)
        assertEquals(3, afterNbsp?.replaceLength)
    }

    @Test
    fun contextIsKnownUnlessTheCallerSaysOtherwise() {
        assertTrue(PredictionInput("ㄱ", "").isContextKnown)
        assertFalse(PredictionInput("ㄱ", "", isContextKnown = false).isContextKnown)
    }
}
