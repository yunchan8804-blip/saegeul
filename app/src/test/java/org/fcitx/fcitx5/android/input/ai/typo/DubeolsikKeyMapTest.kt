/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for DubeolsikKeyMap: syllable-to-key-sequence decomposition
 * (including compound vowels and double final consonants) and keyboard
 * adjacency-based substitution cost.
 */
class DubeolsikKeyMapTest {

    @Test
    fun decomposesPlainSyllablesToKeySequence() {
        assertEquals("rkatkgkqslek", DubeolsikKeyMap.keySequence("감사합니다"))
    }

    @Test
    fun decomposesMixedSyllablesAndStandaloneJamo() {
        assertEquals("tkaykgkaslek", DubeolsikKeyMap.keySequence("사묘ㅏ함니다"))
    }

    @Test
    fun splitsCompoundVowelIntoTwoKeys() {
        assertEquals("rhk", DubeolsikKeyMap.keySequence("과"))
    }

    @Test
    fun splitsDoubleFinalConsonantIntoTwoKeys() {
        assertEquals("rkqt", DubeolsikKeyMap.keySequence("값"))
    }

    @Test
    fun mapsTensedConsonantToShiftKey() {
        assertEquals("Rk", DubeolsikKeyMap.keySequence("까"))
    }

    @Test
    fun countsOneKeystrokePerJamoOfPlainSyllables() {
        assertEquals(6, DubeolsikKeyMap.keystrokeCount("한글"))
        assertEquals(4, DubeolsikKeyMap.keystrokeCount("나라"))
    }

    @Test
    fun countsCompoundVowelAsTwoKeystrokes() {
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("과"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("의"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("왜"))
    }

    @Test
    fun countsDoubleFinalConsonantAsTwoKeystrokes() {
        assertEquals(4, DubeolsikKeyMap.keystrokeCount("닭"))
        assertEquals(4, DubeolsikKeyMap.keystrokeCount("없"))
        assertEquals(4, DubeolsikKeyMap.keystrokeCount("앉"))
    }

    @Test
    fun countsShiftForTensedConsonantsAndShiftedVowels() {
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("까"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("또"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("빠"))
        assertEquals(4, DubeolsikKeyMap.keystrokeCount("있"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("짜"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("얘"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("예"))
        assertEquals(5, DubeolsikKeyMap.keystrokeCount("꺾"))
    }

    @Test
    fun countsStandaloneJamoLikeSyllableJamo() {
        assertEquals(2, DubeolsikKeyMap.keystrokeCount("ㅋㅋ"))
        assertEquals(2, DubeolsikKeyMap.keystrokeCount("ㄲ"))
        assertEquals(2, DubeolsikKeyMap.keystrokeCount("ㅘ"))
        assertEquals(2, DubeolsikKeyMap.keystrokeCount("ㄳ"))
    }

    @Test
    fun countsOneKeystrokePerNonHangulCharacter() {
        assertEquals(5, DubeolsikKeyMap.keystrokeCount("Hello"))
        assertEquals(3, DubeolsikKeyMap.keystrokeCount("a.b"))
        assertEquals(1, DubeolsikKeyMap.keystrokeCount(" "))
        assertEquals(1, DubeolsikKeyMap.keystrokeCount("😀"))
    }

    @Test
    fun countsMixedTextAndEmptyText() {
        assertEquals(13, DubeolsikKeyMap.keystrokeCount("안녕하세요 "))
        assertEquals(0, DubeolsikKeyMap.keystrokeCount(""))
    }

    @Test
    fun substitutionCostForAdjacentSameRowKeys() {
        assertEquals(0.4f, DubeolsikKeyMap.substitutionCost('r', 't'), 0.0001f)
    }

    @Test
    fun substitutionCostForAdjacentDifferentRowKeys() {
        assertEquals(0.4f, DubeolsikKeyMap.substitutionCost('q', 'a'), 0.0001f)
    }

    @Test
    fun substitutionCostForFarKeys() {
        assertEquals(1.0f, DubeolsikKeyMap.substitutionCost('r', 'p'), 0.0001f)
    }

    @Test
    fun substitutionCostForSameKeyShiftDifference() {
        assertEquals(0.35f, DubeolsikKeyMap.substitutionCost('r', 'R'), 0.0001f)
    }
}
