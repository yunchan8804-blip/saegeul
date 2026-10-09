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

    private fun resolveNormalMode(textBeforeCursor: String, composing: String): ContextualPredictionInput.Resolved {
        val committed = ContextualPredictionInput.committedTextBeforeCursor(textBeforeCursor, composing)
        return ContextualPredictionInput.resolve(committed, composing)
    }

    @Test
    fun normalModeComposingInEmptyFieldIsNotDoubled() {
        val resolved = resolveNormalMode("ㄱ", "ㄱ")
        assertEquals("ㄱ", resolved.stroke)
        assertEquals("", resolved.context)
    }

    @Test
    fun normalModeComposingAfterSpaceIsNotDoubled() {
        val resolved = resolveNormalMode("오늘 ㄱ", "ㄱ")
        assertEquals("ㄱ", resolved.stroke)
        assertEquals("오늘 ", resolved.context)
    }

    @Test
    fun normalModeComposingContinuesCommittedWordWithoutSpace() {
        val resolved = resolveNormalMode("안녕하", "하")
        assertEquals("안녕하", resolved.stroke)
        assertEquals("", resolved.context)
    }

    @Test
    fun normalModeComposingAfterSentenceBoundaryIsNotDoubled() {
        val resolved = resolveNormalMode("안녕. 그", "그")
        assertEquals("그", resolved.stroke)
        assertEquals("안녕. ", resolved.context)
    }

    @Test
    fun committedTextKeepsEverythingWhenEditorEndDoesNotMatchComposing() {
        assertEquals("오늘 회의", ContextualPredictionInput.committedTextBeforeCursor("오늘 회의", "참"))
    }

    @Test
    fun committedTextKeepsEverythingWhenEditorComposingIsEmpty() {
        assertEquals("오늘 회의 참", ContextualPredictionInput.committedTextBeforeCursor("오늘 회의 참", ""))
        assertEquals("", ContextualPredictionInput.committedTextBeforeCursor("", ""))
    }

    @Test
    fun bufferedModeResolveIsUnchangedByCommittedTextStep() {
        val resolved = resolveNormalMode("오늘 회의 참", "")
        assertEquals(ContextualPredictionInput.resolve("오늘 회의 참", ""), resolved)

        val stitched = ContextualPredictionInput.resolve(
            ContextualPredictionInput.committedTextBeforeCursor("오늘 회의 참", ""),
            "석"
        )
        assertEquals("참석", stitched.stroke)
        assertEquals("오늘 회의 ", stitched.context)
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
    fun hangulTypedRightAfterSentenceEndingMarkStartsANewSentence() {
        val afterPeriod = ContextualPredictionInput.resolve("고마워요.", "그")
        assertEquals("그", afterPeriod.stroke)
        assertEquals("고마워요.", afterPeriod.context)

        val afterQuestion = ContextualPredictionInput.resolve("오늘 끝났다?그", "")
        assertEquals("그", afterQuestion.stroke)
        assertEquals("오늘 끝났다?", afterQuestion.context)

        val afterExclamation = ContextualPredictionInput.resolve("좋아!", "그")
        assertEquals("그", afterExclamation.stroke)
        assertEquals("좋아!", afterExclamation.context)
    }

    @Test
    fun sentenceEndingMarkRunStaysInTheContext() {
        val ellipsis = ContextualPredictionInput.resolve("고마워요...그", "")
        assertEquals("그", ellipsis.stroke)
        assertEquals("고마워요...", ellipsis.context)

        val unicodeEllipsis = ContextualPredictionInput.resolve("끝…그리", "")
        assertEquals("그리", unicodeEllipsis.stroke)
        assertEquals("끝…", unicodeEllipsis.context)

        val mixed = ContextualPredictionInput.resolve("뭐?!그", "")
        assertEquals("그", mixed.stroke)
        assertEquals("뭐?!", mixed.context)
    }

    @Test
    fun compatibilityJamoAfterSentenceEndingMarkStartsANewSentence() {
        val resolved = ContextualPredictionInput.resolve("고마워요.", "ㄱ")
        assertEquals("ㄱ", resolved.stroke)
        assertEquals("고마워요.", resolved.context)

        val committedJamo = ContextualPredictionInput.resolve("고마워요.ㄱ", "")
        assertEquals("ㄱ", committedJamo.stroke)
        assertEquals("고마워요.", committedJamo.context)
    }

    @Test
    fun sentenceEndingMarkInsideNonHangulTokensIsLeftAlone() {
        val domain = ContextualPredictionInput.resolve("naver.com", "")
        assertEquals("naver.com", domain.stroke)
        assertEquals("", domain.context)

        val number = ContextualPredictionInput.resolve("메모 3.5", "")
        assertEquals("3.5", number.stroke)
        assertEquals("메모 ", number.context)

        val domainWithComposing = ContextualPredictionInput.resolve("naver.", "c")
        assertEquals("naver.c", domainWithComposing.stroke)
        assertEquals("", domainWithComposing.context)
    }

    @Test
    fun endedSentenceWithNothingTypedAfterTheMarkKeepsTheWordAsStroke() {
        val resolved = ContextualPredictionInput.resolve("고마워요.", "")
        assertEquals("고마워요.", resolved.stroke)
        assertEquals("", resolved.context)
    }

    @Test
    fun noBreakAndIdeographicSpacesSeparateTheStrokeFromTheContext() {
        val composingAfterNbsp = ContextualPredictionInput.resolve("오늘 회의 ", "참")
        assertEquals("참", composingAfterNbsp.stroke)
        assertEquals("오늘 회의 ", composingAfterNbsp.context)

        val composingAfterIdeographic = ContextualPredictionInput.resolve("오늘 회의　", "참")
        assertEquals("참", composingAfterIdeographic.stroke)
        assertEquals("오늘 회의　", composingAfterIdeographic.context)

        val idleAfterNbsp = ContextualPredictionInput.resolve("오늘 회의 ", "")
        assertEquals("", idleAfterNbsp.stroke)
        assertEquals("오늘 회의 ", idleAfterNbsp.context)

        val tokenAfterNbsp = ContextualPredictionInput.resolve("오늘 회의", "")
        assertEquals("회의", tokenAfterNbsp.stroke)
        assertEquals("오늘 ", tokenAfterNbsp.context)
    }

    @Test
    fun trailingSpaceIsAppendedUnlessTheSentenceOrTheTextAfterTheCursorAlreadyHasWhitespace() {
        assertEquals(true, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", null))
        assertEquals(true, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", ""))
        assertEquals(true, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", "가"))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", " "))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", "\n"))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", "\t"))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", " "))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다", "　"))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다 ", null))
        assertEquals(false, ContextualPredictionInput.shouldAppendSpace("참석하겠습니다\n", null))
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
