/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationInsertRuleTest {
    @Test
    fun sentenceAtTheStartOfAnEmptyFieldGetsNoSpace() {
        assertEquals("안녕하세요", DictationInsertRule.compose("", "안녕하세요"))
    }

    @Test
    fun sentenceAfterTextGetsOneSpace() {
        assertEquals(" 반갑습니다", DictationInsertRule.compose("안녕하세요", "반갑습니다"))
    }

    @Test
    fun noSpaceIsAddedAfterASpaceOrALineBreak() {
        assertEquals("반갑습니다", DictationInsertRule.compose("안녕하세요 ", "반갑습니다"))
        assertEquals("반갑습니다", DictationInsertRule.compose("안녕하세요\n", "반갑습니다"))
        assertEquals("반갑습니다", DictationInsertRule.compose("안녕하세요\t", "반갑습니다"))
    }

    @Test
    fun sentenceStartingWithPunctuationSticksToTheTextBefore() {
        listOf(".", ",", "!", "?", "…").forEach { mark ->
            assertEquals("${mark}그리고", DictationInsertRule.compose("안녕하세요", "${mark}그리고"))
        }
    }

    @Test
    fun spacesAroundTheSentenceAreTrimmed() {
        assertEquals("반갑습니다", DictationInsertRule.compose("", "  반갑습니다 \n"))
        assertEquals(" 반갑습니다", DictationInsertRule.compose("안녕", "  반갑습니다  "))
    }

    @Test
    fun aSentenceWithNothingInItIsNotInserted() {
        assertNull(DictationInsertRule.compose("안녕", ""))
        assertNull(DictationInsertRule.compose("안녕", "   \n"))
    }
}

class DictationErasePolicyTest {
    @Test
    fun erasesOnlyWhenTheTextBeforeTheCursorIsExactlyThePiece() {
        assertTrue(DictationErasePolicy.canErase(" 반갑습니다", " 반갑습니다"))
    }

    @Test
    fun theSpaceThatWasAddedInFrontIsPartOfThePiece() {
        assertFalse(DictationErasePolicy.canErase("반갑습니다", " 반갑습니다"))
    }

    @Test
    fun doesNotEraseAfterTheUserTypedOrMovedTheCursor() {
        assertFalse(DictationErasePolicy.canErase("반갑습니다요", "반갑습니다"))
        assertFalse(DictationErasePolicy.canErase("안녕하세요", "반갑습니다"))
        assertFalse(DictationErasePolicy.canErase("", "반갑습니다"))
    }

    @Test
    fun doesNotEraseWhenTheEditorCannotBeRead() {
        assertFalse(DictationErasePolicy.canErase(null, "반갑습니다"))
    }

    @Test
    fun nothingIsErasedForAnEmptyPiece() {
        assertFalse(DictationErasePolicy.canErase("", ""))
    }
}
