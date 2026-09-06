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
