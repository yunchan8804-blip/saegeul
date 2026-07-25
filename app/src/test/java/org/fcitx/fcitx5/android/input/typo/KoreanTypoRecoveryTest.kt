/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanTypoRecoveryTest {
    @Test
    fun convertsEnglishKeystrokesToComposedHangul() {
        assertEquals("안녕하세요", KoreanTypoRecovery.englishToHangul("dkssudgktpdy"))
        assertEquals("한글", KoreanTypoRecovery.englishToHangul("gksrmf"))
        assertEquals("과", KoreanTypoRecovery.englishToHangul("rhk"))
        assertEquals("값", KoreanTypoRecovery.englishToHangul("rkqt"))
    }

    @Test
    fun movesSimpleAndCompoundFinalsBeforeFollowingVowel() {
        assertEquals("가가", KoreanTypoRecovery.englishToHangul("rkrk"))
        assertEquals("각사", KoreanTypoRecovery.englishToHangul("rkrtk"))
    }

    @Test
    fun convertsHangulAndCompatibilityJamoBackToQwerty() {
        assertEquals("dkssudgktpdy", KoreanTypoRecovery.hangulToEnglish("안녕하세요"))
        assertEquals("hello", KoreanTypoRecovery.hangulToEnglish("ㅗ디ㅣㅐ"))
        assertEquals("rkqt", KoreanTypoRecovery.hangulToEnglish("값"))
    }

    @Test
    fun preservesTrailingPunctuationAsPartOfReplacementSpan() {
        val chunk = KoreanTypoRecovery.lastChunk("앞 문장 dkssud!")!!
        assertEquals("dkssud", chunk.text)
        assertEquals("!", chunk.trailingPunctuation)
        assertEquals("안녕!", KoreanTypoRecovery.proposals(chunk).single().replacement)
    }

    @Test
    fun stopsAtWhitespaceAndRejectsEmptyTail() {
        assertEquals("gksrmf", KoreanTypoRecovery.lastChunk("hello gksrmf")?.text)
        assertNull(KoreanTypoRecovery.lastChunk("hello "))
    }
}
