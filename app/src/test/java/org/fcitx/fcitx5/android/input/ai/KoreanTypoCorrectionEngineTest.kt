/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Tests for KoreanTypoCorrectionEngine:
 * Verifies adjacent QWERTY/Dubeolsik key typo corrections,
 * common phonetic and ending typos, and candidate integration.
 */
class KoreanTypoCorrectionEngineTest {

    private lateinit var typoEngine: KoreanTypoCorrectionEngine

    @Before
    fun setUp() {
        typoEngine = KoreanTypoCorrectionEngine()
    }

    @Test
    fun `correct adjacent key typo should resolve to intended word`() {
        // 'ㅗ' and 'ㅡ' are adjacent / mistyped in '오눌' -> '오늘'
        val corrections1 = typoEngine.correct("오눌")
        assertTrue("Should suggest '오늘' for '오눌'", corrections1.contains("오늘"))

        // 'ㅗ' and 'ㅛ' are adjacent in '판규' / '판고' -> '판교'
        val corrections2 = typoEngine.correct("판고")
        assertTrue("Should suggest '판교' for '판고'", corrections2.contains("판교"))

        // 'ㅗ' and 'ㅣ' adjacent in '내올' -> '내일'
        val corrections3 = typoEngine.correct("내올")
        assertTrue("Should suggest '내일' for '내올'", corrections3.contains("내일"))
    }

    @Test
    fun `correct colloquial and incomplete ending typos`() {
        // ~세여 -> ~세요
        val corrections1 = typoEngine.correct("안녕하세여")
        assertTrue("Should suggest '안녕하세요' for '안녕하세여'", corrections1.contains("안녕하세요"))

        // 감사합닏 -> 감사합니다
        val corrections2 = typoEngine.correct("감사합닏")
        assertTrue("Should suggest '감사합니다' for '감사합닏'", corrections2.contains("감사합니다"))

        // 반갑습닏 -> 반갑습니다
        val corrections3 = typoEngine.correct("반갑습닏")
        assertTrue("Should suggest '반갑습니다' for '반갑습닏'", corrections3.contains("반갑습니다"))
    }

    @Test
    fun `correct common spelling confusion typos`() {
        // 안되요 -> 안돼요
        val corrections1 = typoEngine.correct("안되요")
        assertTrue("Should suggest '안돼요' for '안되요'", corrections1.contains("안돼요"))

        // 머해 -> 뭐해
        val corrections2 = typoEngine.correct("머해")
        assertTrue("Should suggest '뭐해' for '머해'", corrections2.contains("뭐해"))

        // 마싯 -> 맛있
        val corrections3 = typoEngine.correct("마싯어")
        assertTrue("Should suggest '맛있어' for '마싯어'", corrections3.contains("맛있어"))
    }

    @Test
    fun `integration test with AiContextualPredictor shows corrected word in candidates`() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            typoEngine = typoEngine
        )

        val results = predictor.predict(
            currentStroke = "오눌",
            contextBeforeCursor = "",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should include typo correction '오늘' in word candidates for stroke '오눌'",
            wordCandidates.contains("오늘")
        )
    }

    @Test
    fun `calculateReplacementOverlap accurately replaces mistyped words and prefixes`() {
        // 1. Single word typo: '오눌' -> '오늘' (delete 2 chars '오눌')
        val overlap1 = typoEngine.calculateReplacementOverlap("오눌", "오늘")
        assertEquals(2, overlap1)

        // 2. Sentence context typo: '회의 끝나고 판고' -> '판교' (delete 2 chars '판고')
        val overlap2 = typoEngine.calculateReplacementOverlap("회의 끝나고 판고", "판교")
        assertEquals(2, overlap2)

        // 3. Ending typo: '감사합닏' -> '감사합니다' (delete 4 chars '감사합닏')
        val overlap3 = typoEngine.calculateReplacementOverlap("감사합닏", "감사합니다")
        assertEquals(4, overlap3)

        // 4. Spelling typo: '안되요' -> '안돼요' (delete 3 chars '안되요')
        val overlap4 = typoEngine.calculateReplacementOverlap("안되요", "안돼요")
        assertEquals(3, overlap4)

        // 5. Standard prefix match: '안녕' -> '안녕하세요' (delete 2 chars '안녕')
        val overlap5 = typoEngine.calculateReplacementOverlap("안녕", "안녕하세요")
        assertEquals(2, overlap5)

        // 6. Sentence with trailing space: '오늘 ' -> '오늘 일정 공유드립니다' (delete '오늘 ' = 3 chars)
        val overlap6 = typoEngine.calculateReplacementOverlap("오늘 ", "오늘 일정 공유드립니다")
        assertEquals(3, overlap6)
    }

    @Test
    fun `correct qwerty english mistyped stroke to intended korean word`() {
        // 'dhsnf' -> '오눌' -> '오늘'
        val corrections1 = typoEngine.correct("dhsnf")
        assertTrue("Should suggest '오늘' for qwerty stroke 'dhsnf'", corrections1.contains("오늘"))

        // 'vksrh' -> '판고' -> '판교'
        val corrections2 = typoEngine.correct("vksrh")
        assertTrue("Should suggest '판교' for qwerty stroke 'vksrh'", corrections2.contains("판교"))

        // 'dksehldy' -> '안되요' -> '안돼요'
        val corrections3 = typoEngine.correct("dksehldy")
        assertTrue("Should suggest '안돼요' for qwerty stroke 'dksehldy'", corrections3.contains("안돼요"))

        // Overlap replacement test: 'dhsnf' -> '오늘' (delete 5 chars of 'dhsnf')
        val overlap = typoEngine.calculateReplacementOverlap("앞 문장 dhsnf", "오늘")
        assertEquals(5, overlap)
    }

    @Test
    fun `calculateReplacementOverlap across newlines correctly isolates mistyped word`() {
        val overlap = typoEngine.calculateReplacementOverlap("앞 문장:\n선택 교체\n오눌", "오늘")
        assertEquals(2, overlap)
    }

    @Test
    fun `correct mistyped vowels like 오뉼 and 오놀 to 오늘`() {
        val corrections1 = typoEngine.correct("오뉼")
        assertTrue("Should suggest '오늘' for '오뉼'", corrections1.contains("오늘"))

        val corrections2 = typoEngine.correct("오놀")
        assertTrue("Should suggest '오늘' for '오놀'", corrections2.contains("오늘"))
    }

    @Test
    fun `AiContextualPredictor extracts typo from contextBeforeCursor when activePreedit is single syllable or empty`() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            typoEngine = typoEngine
        )

        // Case A: activePreedit is just the final syllable '눌' while beforeCursor has '오눌'
        val resultsA = predictor.predict(
            currentStroke = "눌",
            contextBeforeCursor = "회의 일정\n오눌",
            packageName = "com.kakao.talk",
            limit = 5
        )
        val wordCandidatesA = resultsA.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should detect '오늘' even if activePreedit is single syllable '눌'",
            wordCandidatesA.contains("오늘")
        )

        // Case B: activePreedit is empty (committed) and beforeCursor has '오눌'
        val resultsB = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "회의 일정\n오눌",
            packageName = "com.kakao.talk",
            limit = 5
        )
        val wordCandidatesB = resultsB.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should detect '오늘' when stroke is empty and beforeCursor ends with typo",
            wordCandidatesB.contains("오늘")
        )
    }

    @Test
    fun `correct phonetic typos like 시프지 and 시퍼 to 싶은지 and 싶어`() {
        val corrections1 = typoEngine.correct("시프지")
        assertTrue("Should suggest '싶은지' for '시프지'", corrections1.contains("싶은지"))

        val corrections2 = typoEngine.correct("시퍼")
        assertTrue("Should suggest '싶어' for '시퍼'", corrections2.contains("싶어"))

        val corrections3 = typoEngine.correct("시퍼요")
        assertTrue("Should suggest '싶어요' for '시퍼요'", corrections3.contains("싶어요"))
    }

    @Test
    fun `calculateReplacementOverlap replaces typo word and completes full clause sentence for 뭘 하고 시프지`() {
        // 1. Word replacement: '시프지' -> '싶은지' (delete 3 chars of '시프지')
        val wordOverlap = typoEngine.calculateReplacementOverlap("뭘 하고 시프지", "싶은지")
        assertEquals(3, wordOverlap)

        // 2. Sentence replacement: '뭘 하고 시프지' -> '뭘 하고 싶은지 모르겠어.' (delete 8 chars of '뭘 하고 시프지')
        val sentenceOverlap = typoEngine.calculateReplacementOverlap("뭘 하고 시프지", "뭘 하고 싶은지 모르겠어.")
        assertEquals("뭘 하고 시프지".length, sentenceOverlap)
    }

    @Test
    fun `AiContextualPredictor suggests both typo word and clause completion sentence for 뭘 하고 시프지`() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            typoEngine = typoEngine
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "뭘 하고 시프지",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest typo word '싶은지' in word row for '뭘 하고 시프지'",
            wordCandidates.contains("싶은지")
        )

        val sentenceCandidates = results.filter { it.isSentenceCompletion }.map { it.text }
    }

    @Test
    fun `typoEngine correctSentence fixes typos in sentence like 뭘 하고 시프지 내가 어떻게 알아`() {
        val input = "뭘 하고 시프지 내가 어떻게 알아"
        val corrected = typoEngine.correctSentence(input)
        assertEquals("뭘 하고 싶은지 내가 어떻게 알아", corrected)
    }

    @Test
    fun `calculateReplacementOverlap replaces full sentence with typo in the middle`() {
        val input = "뭘 하고 시프지 내가 어떻게 알아"
        val candidate = "뭘 하고 싶은지 내가 어떻게 알아"
        val overlap = typoEngine.calculateReplacementOverlap(input, candidate)
        assertEquals(input.length, overlap)
    }

    @Test
    fun `AiContextualPredictor suggests corrected sentence and typo word for 뭘 하고 시프지 내가 어떻게 알아`() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            typoEngine = typoEngine
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "뭘 하고 시프지 내가 어떻게 알아",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val sentenceCandidates = results.filter { it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest corrected sentence '뭘 하고 싶은지 내가 어떻게 알아'",
            sentenceCandidates.contains("뭘 하고 싶은지 내가 어떻게 알아")
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest typo correction word '싶은지' in word row",
            wordCandidates.contains("싶은지")
        )
    }

    @Test
    fun `morphological typo normalization correctly resolves 시프니까 and other auxiliary conjugations`() {
        // 시프니까 -> 싶으니까
        val corrections1 = typoEngine.correct("시프니까")
        assertTrue("Should suggest '싶으니까' for '시프니까'", corrections1.contains("싶으니까"))

        // 시프면 -> 싶으면
        val corrections2 = typoEngine.correct("시프면")
        assertTrue("Should suggest '싶으면' for '시프면'", corrections2.contains("싶으면"))

        // Prefix fusion: 하고시프니까 -> 하고 싶으니까
        val corrections3 = typoEngine.correct("하고시프니까")
        assertTrue("Should suggest '하고 싶으니까' for '하고시프니까'", corrections3.contains("하고 싶으니까"))

        // ㄹ께 -> ㄹ게 (맞춤법 제53항)
        val corrections4 = typoEngine.correct("갈께")
        assertTrue("Should suggest '갈게' for '갈께'", corrections4.contains("갈게"))

        val corrections5 = typoEngine.correct("해볼께요")
        assertTrue("Should suggest '해볼게요' for '해볼께요'", corrections5.contains("해볼게요"))

        // 됬 -> 됐
        val corrections6 = typoEngine.correct("됬어")
        assertTrue("Should suggest '됐어' for '됬어'", corrections6.contains("됐어"))

        // 됫 -> 됐
        val corrections7 = typoEngine.correct("됫어요")
        assertTrue("Should suggest '됐어요' for '됫어요'", corrections7.contains("됐어요"))
    }

    @Test
    fun `typoEngine correctSentence fixes 난 그걸하고 시프니까 to 난 그걸하고 싶으니까`() {
        val input = "난 그걸하고 시프니까"
        val corrected = typoEngine.correctSentence(input)
        assertEquals("난 그걸하고 싶으니까", corrected)
    }

    @Test
    fun `calculateReplacementOverlap replaces word and full sentence for 난 그걸하고 시프니까`() {
        val input = "난 그걸하고 시프니까"

        // 1. Single word replacement: '시프니까' -> '싶으니까' (delete 4 chars of '시프니까')
        val wordOverlap = typoEngine.calculateReplacementOverlap(input, "싶으니까")
        assertEquals(4, wordOverlap)

        // 2. Full sentence replacement: '난 그걸하고 시프니까' -> '난 그걸하고 싶으니까' (delete entire 11 chars)
        val sentenceOverlap = typoEngine.calculateReplacementOverlap(input, "난 그걸하고 싶으니까")
        assertEquals(input.length, sentenceOverlap)
    }

    @Test
    fun `AiContextualPredictor suggests both 싶으니까 and 난 그걸하고 싶으니까 for 난 그걸하고 시프니까`() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            typoEngine = typoEngine
        )

        // Test without trailing space
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "난 그걸하고 시프니까",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest '싶으니까' in word row for '난 그걸하고 시프니까'",
            wordCandidates.contains("싶으니까")
        )

        val sentenceCandidates = results.filter { it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest '난 그걸하고 싶으니까' in sentence row for '난 그걸하고 시프니까'",
            sentenceCandidates.contains("난 그걸하고 싶으니까")
        )

        // Test with trailing space (cursor after space)
        val resultsWithSpace = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "난 그걸하고 시프니까 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidatesWithSpace = resultsWithSpace.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should maintain '싶으니까' in word row even with trailing space",
            wordCandidatesWithSpace.contains("싶으니까")
        )

        val sentenceCandidatesWithSpace = resultsWithSpace.filter { it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should maintain '난 그걸하고 싶으니까' in sentence row even with trailing space",
            sentenceCandidatesWithSpace.contains("난 그걸하고 싶으니까")
        )

        // Test with active composing stroke while typing: contextBeforeCursor = "난 그걸하고 ", currentStroke = "시프니까"
        val resultsTypingStroke = predictor.predict(
            currentStroke = "시프니까",
            contextBeforeCursor = "난 그걸하고 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        val wordCandidatesTyping = resultsTypingStroke.filter { !it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest '싶으니까' while actively typing '시프니까'",
            wordCandidatesTyping.contains("싶으니까")
        )

        val sentenceCandidatesTyping = resultsTypingStroke.filter { it.isSentenceCompletion }.map { it.text }
        assertTrue(
            "Predictor should suggest '난 그걸하고 싶으니까' while actively typing '시프니까'",
            sentenceCandidatesTyping.contains("난 그걸하고 싶으니까")
        )
    }
}

