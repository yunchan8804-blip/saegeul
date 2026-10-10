/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.KeyboardTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.typo.TouchSubstitutionCost
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures
import org.fcitx.fcitx5.android.input.typo.TouchTrace
import org.fcitx.fcitx5.android.input.typo.TypedKeyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 한 어절 안에서 글자가 뭉개져 들어간 입력(옆 키를 누른 글자, 낱자모, 글자 키에 함께 적힌 숫자·기호)이 실제 번들
 * 어휘의 의도한 어절로 ✏️ 후보 맨 앞에 뜨는지: `얀냄ㅎㅏ게요` -> `안녕하세요`, `4ㅏㅁ사하1니다` -> `감사합니다`.
 * 후보는 띄우기만 하고 자동으로 바꾸지 않는다.
 */
class MangledInputCorrectionTest {

    private lateinit var vocabulary: BaseKoreanVocabulary
    private lateinit var typoCorrector: KeyboardAwareTypoCorrector
    private lateinit var ngram: PersonalNgramModel
    private lateinit var source: KeyboardTypoCorrectionSource
    private lateinit var predictor: AiContextualPredictor

    private val pkg = "com.test.app"
    private var trace: TouchTrace? = null

    private fun findVocabFile(): File {
        val candidates = listOf(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )
        return candidates.map(::File).firstOrNull(File::exists)
            ?: throw AssertionError("ko_base_vocab.tsv를 찾을 수 없습니다. cwd=${File(".").absolutePath}")
    }

    @Before
    fun setUp() {
        val vocabFile = findVocabFile()
        vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()
        typoCorrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        ngram = PersonalNgramModel()
        trace = null
        val reader = TypedKeyReader { TextKeyboard.secondaryCharKeys(numberRowPinned = false) }
        source = KeyboardTypoCorrectionSource(
            typoCorrector, vocabulary, CorrectionPatternStore(), ngram, { trace }, reader, null
        ) { null }
        predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            personalizedStore = null,
            ngram = ngram,
            typoCorrector = typoCorrector,
            baseVocabulary = vocabulary,
            correctionStore = CorrectionPatternStore(),
            touchTrace = { trace },
            typedKeyReader = reader
        )
    }

    private fun typoWords(typed: String): List<String> =
        source.correct(PredictionInput(typed, ""), pkg).candidates.map { it.text }

    @Test
    fun mangledSyllablesWithLooseJamoReachTheIntendedWord() {
        assertEquals("안녕하세요", typoWords("얀냄ㅎㅏ게요").first())
    }

    @Test
    fun secondaryCharsReadAsTheirLetterKeysReachTheIntendedWord() {
        assertEquals("감사합니다", typoWords("4ㅏㅁ사하1니다").first())
    }

    @Test
    fun theSameInputsReachTheWordWithAPinnedNumberRowToo() {
        val reader = TypedKeyReader { TextKeyboard.secondaryCharKeys(numberRowPinned = true) }
        val pinned = KeyboardTypoCorrectionSource(
            typoCorrector, vocabulary, CorrectionPatternStore(), ngram, null, reader, null
        ) { null }

        // 고정 숫자 줄이 있으면 숫자는 숫자 줄 키로, 윗줄을 밀면 `%^&_[]{}<>`가 나온다. 둘 다 같은 열의 글자 키로 읽는다.
        assertEquals("감사합니다", pinned.correct(PredictionInput("4ㅏㅁ사하1니다", ""), pkg).candidates.first().text)
        assertEquals("감사합니다", pinned.correct(PredictionInput("_ㅏㅁ사하%니다", ""), pkg).candidates.first().text)
    }

    @Test
    fun withATouchTraceThatMatchesTheKeysTheLongFragmentStillReachesTheWord() {
        trace = TouchTestFixtures.centeredTrace(DubeolsikKeyMap.keySequence("얀냄ㅎㅏ게요"))

        assertEquals("안녕하세요", typoWords("얀냄ㅎㅏ게요").first())
    }

    @Test
    fun centerTouchesKeepTheMangledFragmentFarInsideTheCostLimit() {
        val corrector = KeyboardAwareTypoCorrector()
        corrector.addWord("안녕하세요", 0.5f)
        val typed = "얀냄ㅎㅏ게요"
        val keys = DubeolsikKeyMap.keySequence(typed)
        val limit = KeyboardAwareTypoCorrector.keyboardMaxCost(keys.length)

        val grid = corrector.correct(typed, maxCost = limit).single().cost
        val centered = corrector.correct(typed, maxCost = limit, touchTrace = TouchTestFixtures.centeredTrace(keys)).single().cost

        // 바뀐 네 자리(ㅑ->ㅏ, ㅐ->ㅕ, ㅁ->ㅇ, ㄱ->ㅅ)는 모두 격자 이웃(격자 비용 0.7 이하)이라 터치 식의 상한 0.47이다.
        // 터치가 어디에 닿아도 이보다 비싸지지 않는다.
        for ((typedKey, intendedKey) in listOf('i' to 'k', 'o' to 'u', 'a' to 'd', 'r' to 't')) {
            assertTrue("$typedKey->$intendedKey", DubeolsikKeyMap.substitutionCost(typedKey, intendedKey) <= TouchSubstitutionCost.NEIGHBOR_GRID_MAX)
        }
        assertEquals(2.2f, grid, 0.0001f)
        assertEquals(4 * TouchSubstitutionCost.SUBSTITUTION_CAP, centered, 0.0001f)
        assertTrue("centered=$centered limit=$limit", centered <= limit - 1f)
    }

    @Test
    fun ordinaryWordsWithCenterTouchesGetNoCorrection() {
        val controls = listOf(
            "10시", "2024년", "100원", "카페24", "안녕하세요", "감사합니다", "수고하셨습니다",
            "오늘", "내일", "점심", "회사", "친구", "가족", "시간", "약속", "생각", "이야기", "공부"
        )
        val flagged = controls.filter {
            trace = TouchTestFixtures.centeredTrace(DubeolsikKeyMap.keySequence(it))
            predictor.predict(it, "", pkg, limit = 10).any { p -> p.badge == "✏️" }
        }

        assertEquals(emptyList<String>(), flagged)
    }

    @Test
    fun unknownOrdinaryWordsWithCenterTouchesGetNoCorrection() {
        // 사전 밖 정상어: 받침과 조사 자리의 먼 키 치환이 터치 식으로 싸지면 라면을·피자를로 잘못 뜬다.
        val flagged = listOf("라면집", "피자집").filter {
            trace = TouchTestFixtures.centeredTrace(DubeolsikKeyMap.keySequence(it))
            predictor.predict(it, "", pkg, limit = 10).any { p -> p.badge == "✏️" }
        }

        assertEquals(emptyList<String>(), flagged)
    }

    @Test
    fun theCorrectionComesFirstOnTheWordLineAndTheTypedFragmentIsNeverOneOfThem() {
        for (typed in listOf("얀냄ㅎㅏ게요", "4ㅏㅁ사하1니다")) {
            for (context in listOf("", "오늘 ")) {
                val words = predictor.predict(typed, context, pkg, limit = 10).filter { !it.isSentenceCompletion }
                val first = words.first()

                assertEquals("✏️", first.badge)
                assertEquals(typed.length, first.replaceLength)
                assertTrue(typed, first.text == "안녕하세요" || first.text == "감사합니다")
                assertTrue("교정은 원문을 대체하는 후보일 뿐 원문 칩과 같은 글이 아니다", words.none { it.text == typed })
            }
        }
    }

    @Test
    fun aSecondaryCharAddsTheSecondaryCostToItsPosition() {
        val corrector = KeyboardAwareTypoCorrector()
        corrector.addWord("감사", 0.5f)
        val reader = TypedKeyReader { TextKeyboard.secondaryCharKeys(numberRowPinned = false) }
        val typed = "4ㅏㅁ사"

        val correction = corrector.correct(typed, reading = reader.read(typed)).single()

        assertEquals("감사", correction.word)
        assertEquals(TypedKeyReader.SECONDARY_CHAR_COST, correction.cost, 0.0001f)
    }

    @Test
    fun theCostLimitWidensOnlyForTenOrMoreKeys() {
        for (keys in 1..9) {
            val text = "a".repeat(keys)
            assertEquals(KeyboardAwareTypoCorrector.defaultMaxCost(text), KeyboardAwareTypoCorrector.keyboardMaxCost(keys), 0f)
        }
        assertEquals(2.6f, KeyboardAwareTypoCorrector.keyboardMaxCost(10), 0.0001f)
        assertEquals(2.8f, KeyboardAwareTypoCorrector.keyboardMaxCost(11), 0.0001f)
        assertEquals(3.0f, KeyboardAwareTypoCorrector.keyboardMaxCost(12), 0.0001f)
        assertEquals(3.0f, KeyboardAwareTypoCorrector.keyboardMaxCost(20), 0.0001f)
    }

    @Test
    fun ordinaryNumbersAndWordsGetNoCorrection() {
        val controls = listOf(
            "10시", "2024년", "100원", "카페24", "안녕하세요", "감사합니다", "수고하셨습니다",
            "오늘", "내일", "점심", "회사", "친구", "가족", "시간", "약속", "생각", "이야기", "공부"
        )
        val flagged = controls.filter { predictor.predict(it, "", pkg, limit = 10).any { p -> p.badge == "✏️" } }

        assertEquals(emptyList<String>(), flagged)
    }
}
