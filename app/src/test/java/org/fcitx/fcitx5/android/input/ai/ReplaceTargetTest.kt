/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.ReplaceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceTargetTest {

    @Test
    fun `a stroke being typed is the fragment a correction erases`() {
        val raw = ContextualPredictionInput.rawFullContext(currentStroke = "회으", contextBeforeCursor = "abc ")
        val target = ReplaceTarget.of(raw, replaceLength = 2)!!

        assertEquals("회으", target.fragment)
        assertTrue(target.accepts("회으"))
    }

    @Test
    fun `a committed last word with trailing spaces is the fragment`() {
        val target = ReplaceTarget.of("abc 회으  ", replaceLength = 4)!!

        assertEquals("회으  ", target.fragment)
        assertTrue(target.accepts("회으  "))
    }

    @Test
    fun `text typed after the prediction was made is refused`() {
        val raw = ContextualPredictionInput.rawFullContext(currentStroke = "회으", contextBeforeCursor = "")
        val target = ReplaceTarget.of(raw, replaceLength = 2)!!

        assertFalse(target.accepts("xy"))
        assertFalse(target.accepts("으"))
    }

    @Test
    fun `a prediction context shorter than the erase can never be verified`() {
        val target = ReplaceTarget.of("회", replaceLength = 2)!!

        assertNull(target.fragment)
        assertFalse(target.accepts("회"))
        assertFalse(target.accepts(""))
    }

    @Test
    fun `a candidate that erases nothing has no target`() {
        assertNull(ReplaceTarget.of("abc", replaceLength = 0))
    }
}
