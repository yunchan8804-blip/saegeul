/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ContextualPredictionInput.resolve, which stitches a word already committed to
 * the editor back together with jamo still being composed (buffered Hangul compatibility mode).
 */
class ContextualPredictionInputTest {

    @Test
    fun composingContinuesAlreadyCommittedToken() {
        val resolved = ContextualPredictionInput.resolve("오늘 회의 참", "석")
        assertEquals("참석", resolved.stroke)
        assertEquals("오늘 회의 ", resolved.context)
    }

    @Test
    fun composingAfterTrailingSpaceIsStandaloneStroke() {
        val resolved = ContextualPredictionInput.resolve("오늘 회의 ", "참")
        assertEquals("참", resolved.stroke)
        assertEquals("오늘 회의 ", resolved.context)
    }

    @Test
    fun noComposingExtractsLastTokenAsStroke() {
        val resolved = ContextualPredictionInput.resolve("오늘 회의 참", "")
        assertEquals("참", resolved.stroke)
        assertEquals("오늘 회의 ", resolved.context)
    }

    @Test
    fun noComposingWithTrailingSpaceHasBlankStroke() {
        val resolved = ContextualPredictionInput.resolve("오늘 회의 ", "")
        assertEquals("", resolved.stroke)
        assertEquals("오늘 회의 ", resolved.context)
    }

    @Test
    fun rawFullContextUsesCurrentStroke() {
        assertEquals(
            "오늘 회의 참석",
            ContextualPredictionInput.rawFullContext("참석", "오늘 회의 ")
        )
    }

    @Test
    fun rawFullContextPreservesWhitespaceBeforeStroke() {
        assertEquals(
            "오늘 회의 참석",
            ContextualPredictionInput.rawFullContext("참석", "오늘 회의")
        )
    }

    @Test
    fun rawFullContextDoesNotDuplicateIncludedStroke() {
        assertEquals(
            "오늘 회의 참석",
            ContextualPredictionInput.rawFullContext("참석", "오늘 회의 참석")
        )
    }

    @Test
    fun rawFullContextUsesStrokeForBlankContext() {
        assertEquals("참석", ContextualPredictionInput.rawFullContext("참석", ""))
    }

    @Test
    fun activePreeditUsesClientPreeditWhenItIsAvailable() {
        assertEquals("ㅅ", ContextualPredictionInput.activePreedit("", "ㅅ", "ㄱ"))
    }

    @Test
    fun activePreeditUsesEnginePreeditForEachSingleChoseongWhenClientPreeditIsBlank() {
        listOf("ㅅ", "ㄱ", "ㅇ").forEach { choseong ->
            assertEquals(choseong, ContextualPredictionInput.activePreedit("", "", choseong))
        }
    }

    @Test
    fun activePreeditDoesNotDuplicateMatchingClientAndEnginePreedit() {
        assertEquals("ㅅ", ContextualPredictionInput.activePreedit("", "ㅅ", "ㅅ"))
    }

    @Test
    fun activePreeditRetainsBufferedHangulPrefixBeforeTheCurrentPreedit() {
        assertEquals("가ㅅ", ContextualPredictionInput.activePreedit("가", "", "ㅅ"))
        assertEquals("가ㅅ", ContextualPredictionInput.activePreedit("가", "ㅅ", "ㄱ"))
    }
}
