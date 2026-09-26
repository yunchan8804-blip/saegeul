/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 자판 인식 오타 교정(typo_keyboard/typo_personal)과 기본 어휘 완성(base_vocab)이
 * [AiContextualPredictor.predict]의 실제 후보 경로에 연결되는지 검증한다. 실제 번들 자산
 * (ko_base_vocab.tsv)으로 어휘와 corrector를 채워 프로덕션과 동일한 조건에서 확인한다.
 */
class AiContextualPredictorTypoTest {

    private lateinit var vocabulary: BaseKoreanVocabulary
    private lateinit var typoCorrector: KeyboardAwareTypoCorrector
    private lateinit var correctionStore: CorrectionPatternStore
    private lateinit var ngram: PersonalNgramModel
    private lateinit var predictor: AiContextualPredictor

    private fun findVocabFile(): File {
        val candidates = listOf(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        throw AssertionError(
            "ko_base_vocab.tsv를 찾을 수 없습니다. 시도한 경로: $candidates, cwd=${File(".").absolutePath}"
        )
    }

    @Before
    fun setUp() {
        val vocabFile = findVocabFile()
        vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()
        typoCorrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        correctionStore = CorrectionPatternStore()
        ngram = PersonalNgramModel()
        predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            personalizedStore = null,
            ngram = ngram,
            typoCorrector = typoCorrector,
            baseVocabulary = vocabulary,
            correctionStore = correctionStore
        )
    }

    @Test
    fun `keyboard-aware typo correction surfaces the corrected word with exact replace length`() {
        val results = predictor.predict(
            currentStroke = "사묘ㅏ함니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        // The new keyboard-aware engine handling "사묘ㅏ함니다" must suppress the legacy
        // KoreanTypoCorrectionEngine's blind '함니다'->'합니다' ending swap for the same typed
        // fragment, so the accurate correction wins the overall top spot.
        val top = results.first()
        assertEquals("감사합니다", top.text)
        assertEquals("typo_keyboard", top.source)
        assertEquals("✏️", top.badge)
        assertEquals(6, top.replaceLength)
    }

    @Test
    fun `known word does not trigger a keyboard-aware fuzzy correction`() {
        val results = predictor.predict(
            currentStroke = "감사합니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        assertFalse(results.any { it.source == "typo_keyboard" })
    }

    @Test
    fun `numeric and symbol input produces no Korean typo corrections`() {
        val numericResults = predictor.predict(
            currentStroke = "2026-09-10",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )
        val symbolResults = predictor.predict(
            currentStroke = "!@#\$%^&*",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        assertFalse(numericResults.any { it.source == "typo_keyboard" })
        assertFalse(symbolResults.any { it.source == "typo_keyboard" })
    }

    @Test
    fun `numeric input retains an explicit personal correction`() {
        assertTrue(correctionStore.recordCorrection("123", "124"))

        val results = predictor.predict(
            currentStroke = "123",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val hit = results.firstOrNull { it.source == "typo_personal" }
        assertTrue("explicit personal correction must be retained: ${results.map { it.text to it.source }}", hit != null)
        assertEquals("124", hit!!.text)
        assertEquals(3, hit.replaceLength)
    }

    @Test
    fun `a single learned occurrence of a typo does not yet suppress the keyboard-aware correction`() {
        // A user typing a typo once and moving on (space) feeds it into the personal n-gram as a
        // unigram. One occurrence must not be enough to mark it "known" and skip correction.
        ngram.learn("사묘ㅏ함니다", "com.test.app")

        val results = predictor.predict(
            currentStroke = "사묘ㅏ함니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val top = results.first()
        assertEquals("감사합니다", top.text)
        assertEquals("typo_keyboard", top.source)
        assertEquals("✏️", top.badge)
    }

    @Test
    fun `two learned occurrences of a typo still surface a single lower-confidence keyboard correction`() {
        // Known only through the user's own n-gram (not the bundled base vocabulary), the typo is
        // no longer fully suppressed: it still surfaces, but capped to a single, lower-confidence
        // candidate so a genuinely-known personal word keeps its lead over a plausible typo read.
        ngram.learn("사묘ㅏ함니다", "com.test.app")
        ngram.learn("사묘ㅏ함니다", "com.test.app")

        val results = predictor.predict(
            currentStroke = "사묘ㅏ함니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val keyboardHits = results.filter { it.source == "typo_keyboard" }
        assertEquals(1, keyboardHits.size)
        assertEquals("감사합니다", keyboardHits.first().text)
    }

    @Test
    fun `a recorded personal correction outranks the keyboard-aware fuzzy correction`() {
        correctionStore.recordCorrection("사묘ㅏ함니다", "감사합니다")

        val results = predictor.predict(
            currentStroke = "사묘ㅏ함니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val top = results.first()
        assertEquals("감사합니다", top.text)
        assertEquals("typo_personal", top.source)
        val keyboardDuplicate = results.count { it.text == "감사합니다" && it.source == "typo_keyboard" }
        assertEquals("typo_personal must supersede typo_keyboard for the same text", 0, keyboardDuplicate)
    }

    @Test
    fun `base vocabulary completion surfaces a word candidate by choseong prefix`() {
        // "-는 것" 명사화 어미는 실제 개인 n-gram이 학습해 두는 흔한 이어짐이다. 빈 ngram으로는
        // "것"(빈도 3813)이 choseong 'ㄱ' 상위 8개 중 8위라 재정렬 없이는 상위 4개에서 잘린다.
        ngram.learnBigram("하는", "것", 5f, "com.test.app")

        val results = predictor.predict(
            currentStroke = "ㄱ",
            contextBeforeCursor = "지금부터 이야기 하는 ",
            packageName = "com.test.app",
            limit = 10
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }
        assertTrue(
            "word candidates must include '것': ${wordCandidates.map { it.text }}",
            wordCandidates.any { it.text == "것" }
        )
    }

    @Test
    fun `keyboard-aware correction fires for an unknown word right after a trailing space`() {
        // "참석합니다"는 번들 기본 어휘(ko_base_vocab.tsv)에 없는 어절이므로, 사용자가 과거에
        // 정확히 입력해 개인 n-gram이 학습한 상태를 재현해 warmUpLanguageAssets와 동일하게
        // typoCorrector에 미리 채워 둔다.
        ngram.learn("참석합니다", "com.test.app")
        typoCorrector.addWord("참석합니다", PersonalNgramModel.personalPrior(ngram.unigramCount("참석합니다")))

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 참석함니다 ",
            packageName = "com.test.app",
            limit = 5
        )

        val hit = results.firstOrNull { it.text == "참석합니다" }
        assertTrue("참석합니다 candidate must be present: ${results.map { it.text }}", hit != null)
        assertEquals("✏️", hit!!.badge)
        assertEquals(6, hit.replaceLength)
    }

    @Test
    fun `corrects adjacent final consonant with trailing punctuation`() {
        typoCorrector.addWord("걸린", 111f)
        typoCorrector.addWord("건가", 324f)
        typoCorrector.addWord("감기", 500f)

        val results = predictor.predict(
            currentStroke = "걸림건가???",
            contextBeforeCursor = "감기 ",
            packageName = "com.test.app",
            limit = 5
        )

        val hit = results.firstOrNull { it.text == "걸린건가???" }
        assertTrue(
            "걸린건가??? candidate must be present: ${results.map { it.text to it.source }}",
            hit != null
        )
        assertEquals(7, hit!!.replaceLength)
        assertTrue(
            "source must be typo_keyboard_stem or typo_keyboard: ${hit.source}",
            hit.source == "typo_keyboard_stem" || hit.source == "typo_keyboard"
        )
    }

    @Test
    fun `keeps correcting when only personal ngram knows the typo`() {
        typoCorrector.addWord("오늘", 1000f)

        ngram.learn("오눌", "com.test.app")
        ngram.learn("오눌", "com.test.app")

        val results = predictor.predict(
            currentStroke = "오눌",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        val hit = results.firstOrNull { it.text == "오늘" }
        assertTrue(
            "오늘 correction must still be surfaced: ${results.map { it.text to it.source }}",
            hit != null
        )
    }

    @Test
    fun `skips correction for base vocabulary word`() {
        typoCorrector.addWord("감사합니다", 1000f)

        val results = predictor.predict(
            currentStroke = "감사합니다",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        assertFalse(
            "no typo correction expected for a base-vocabulary word: ${results.map { it.text to it.source }}",
            results.any { it.source == "typo_keyboard" || it.source == "typo_keyboard_stem" }
        )
    }
}
