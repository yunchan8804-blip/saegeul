/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.hangul

import org.fcitx.fcitx5.android.data.personaldictionary.isValidPersonalWord
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput
import org.fcitx.fcitx5.android.input.ai.KoreanCollocationModel
import org.fcitx.fcitx5.android.input.ai.KoreanDiscourseContinuation
import org.fcitx.fcitx5.android.input.ai.KoreanToneClassifier
import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate
import org.fcitx.fcitx5.android.input.ai.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggester
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer
import org.fcitx.fcitx5.android.input.search.KoreanInitials
import org.fcitx.fcitx5.android.input.typo.KoreanTypoRecovery
import org.fcitx.fcitx5.android.input.typo.TypoRecoveryChunk
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 한글 음절 범위·자모 테이블·분해·받침 판정을 쓰는 모든 경로의 현재 동작을 고정한다.
 * 입력은 받침 없음/있음/겹받침, 쌍자음, 겹모음, 낱자 자모, 한글이 아닌 글자, 빈 문자열, 음절 범위
 * 경계(U+ABFF, U+AC00, U+D7A3, U+D7A4)를 포함하고, 기대값은 한글 판정 공용화 이전 코드의 출력이다.
 */
class HangulSyllableGoldenTest {

    private val engine = ChoseongMorphologyEngine()
    private val collocation = KoreanCollocationModel()
    private val cycleB = MobileHangulComposer.Token.Cycle("cj_b", listOf('ㅂ', 'ㅍ', 'ㅃ'))
    private val cycleG = MobileHangulComposer.Token.Cycle("cj_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))

    private fun check(name: String, golden: List<Pair<String, String?>>, actualOf: (String) -> Any?) {
        val failures = golden.mapNotNull { (input, expected) ->
            val actual = actualOf(input)?.toString()
            if (actual == expected) null else "$name(${escaped(input)}) expected=${expected?.let(::escaped)} actual=${actual?.let(::escaped)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun escaped(value: String): String = buildString {
        for (c in value) {
            if (c.code in 0x20..0x7E || c.code in 0xAC00..0xD7A3 || c.code in 0x3131..0x318E) append(c)
            else append("\\u%04X".format(c.code))
        }
    }


    @Test
    fun `ChoseongMorphologyEngine decomposeHangul`() = check("choseongEngineDecomposeHangul", choseongEngineDecomposeHangulGolden()) {
        engine.decomposeHangul(it)
    }

    @Test
    fun `ChoseongMorphologyEngine extractChoseongSequence`() = check("choseongEngineExtractSequence", choseongEngineExtractSequenceGolden()) {
        engine.extractChoseongSequence(it)
    }

    @Test
    fun `ChoseongMorphologyEngine extractChoseong first`() = check("choseongEngineExtractFirst", choseongEngineExtractFirstGolden()) {
        engine.extractChoseong(it)
    }

    @Test
    fun `ChoseongMorphologyEngine isHangulSyllable`() = check("choseongEngineIsSyllable", choseongEngineIsSyllableGolden()) {
        engine.isHangulSyllable(it[0])
    }

    @Test
    fun `ChoseongMorphologyEngine isChoseong`() = check("choseongEngineIsChoseong", choseongEngineIsChoseongGolden()) {
        engine.isChoseong(it[0])
    }

    @Test
    fun `KoreanTypoCorrectionEngine decomposeToJaso`() = check("typoEngineDecomposeToJaso", typoEngineDecomposeToJasoGolden()) {
        KoreanTypoCorrectionEngine.decomposeToJaso(it)
    }

    @Test
    fun `KoreanTypoCorrectionEngine qwertyToHangul`() = check("typoEngineQwertyToHangul", typoEngineQwertyToHangulGolden()) {
        KoreanTypoCorrectionEngine.qwertyToHangul(it)
    }

    @Test
    fun `DubeolsikKeyMap keySequence`() = check("dubeolsikKeySequence", dubeolsikKeySequenceGolden()) {
        DubeolsikKeyMap.keySequence(it)
    }

    @Test
    fun `DubeolsikKeyMap keystrokeCount`() = check("dubeolsikKeystrokeCount", dubeolsikKeystrokeCountGolden()) {
        DubeolsikKeyMap.keystrokeCount(it)
    }

    @Test
    fun `KoreanInitials extract`() = check("initialsExtract", initialsExtractGolden()) {
        KoreanInitials.extract(it)
    }

    @Test
    fun `KoreanInitials isInitialQuery`() = check("initialsIsInitialQuery", initialsIsInitialQueryGolden()) {
        KoreanInitials.isInitialQuery(it)
    }

    @Test
    fun `KoreanTypoRecovery englishToHangul`() = check("recoveryEnglishToHangul", recoveryEnglishToHangulGolden()) {
        KoreanTypoRecovery.englishToHangul(it)
    }

    @Test
    fun `KoreanTypoRecovery hangulToEnglish`() = check("recoveryHangulToEnglish", recoveryHangulToEnglishGolden()) {
        KoreanTypoRecovery.hangulToEnglish(it)
    }

    @Test
    fun `KoreanTypoRecovery proposals`() = check("recoveryProposals", recoveryProposalsGolden()) {
        KoreanTypoRecovery.proposals(TypoRecoveryChunk(it, "")).joinToString("|") { p ->
            "${p.direction}:${p.replacement}"
        }
    }

    @Test
    fun `KoreanTypoRecovery lastChunk`() = check("recoveryLastChunk", recoveryLastChunkGolden()) {
        KoreanTypoRecovery.lastChunk(it)?.let { c -> "${c.text}|${c.trailingPunctuation}" }
    }

    @Test
    fun `KoreanJosaBitmaskEngine getBatchimCode`() = check("josaBatchimCode", josaBatchimCodeGolden()) {
        KoreanJosaBitmaskEngine.getBatchimCode(it[0])
    }

    @Test
    fun `KoreanJosaBitmaskEngine hasBatchim`() = check("josaHasBatchim", josaHasBatchimGolden()) {
        KoreanJosaBitmaskEngine.hasBatchim(it[0])
    }

    @Test
    fun `KoreanJosaBitmaskEngine isRieulBatchim`() = check("josaIsRieulBatchim", josaIsRieulBatchimGolden()) {
        KoreanJosaBitmaskEngine.isRieulBatchim(it[0])
    }

    @Test
    fun `KoreanJosaBitmaskEngine getPhonologicalFlags char`() = check("josaFlagsChar", josaFlagsCharGolden()) {
        KoreanJosaBitmaskEngine.getPhonologicalFlags(it[0])
    }

    @Test
    fun `KoreanJosaBitmaskEngine getPhonologicalFlags string`() = check("josaFlagsString", josaFlagsStringGolden()) {
        KoreanJosaBitmaskEngine.getPhonologicalFlags(it)
    }

    @Test
    fun `KoreanJosaBitmaskEngine correctJosaMismatch`() = check("josaCorrectMismatch", josaCorrectMismatchGolden()) {
        KoreanJosaBitmaskEngine.correctJosaMismatch(it)
    }

    @Test
    fun `KoreanParticleSuggester suggest`() = check("particleSuggesterSuggest", particleSuggesterSuggestGolden()) {
        KoreanParticleSuggester.suggest(it).joinToString("|") { s -> "${s.kind}:${s.text}" }
    }

    @Test
    fun `KoreanCollocationModel predictNextWords particle gating`() = check("collocationPredictNextWords", collocationPredictNextWordsGolden()) {
        collocation.predictNextWords(it, true).joinToString("|") + "#" +
            collocation.predictNextWords(it, false).joinToString("|")
    }

    @Test
    fun `PersonalNgramTokenizer stem`() = check("ngramTokenizerStem", ngramTokenizerStemGolden()) {
        PersonalNgramTokenizer.stem(it)
    }

    @Test
    fun `PersonalNgramTokenizer tokenize`() = check("ngramTokenizerTokenize", ngramTokenizerTokenizeGolden()) {
        PersonalNgramTokenizer.tokenize(it).joinToString("|")
    }

    @Test
    fun `KoreanToneClassifier infer rieul-batchim question`() = check("toneClassifierInfer", toneClassifierInferGolden()) {
        KoreanToneClassifier.infer(it)
    }

    @Test
    fun `KoreanMorphologicalEndingAnalyzer extractEnding`() = check("endingAnalyzerExtractEnding", endingAnalyzerExtractEndingGolden()) {
        KoreanMorphologicalEndingAnalyzer.extractEnding(it)
    }

    @Test
    fun `isValidPersonalWord`() = check("personalWordIsValid", personalWordIsValidGolden()) {
        isValidPersonalWord(it)
    }

    @Test
    fun `ContextualPredictionInput resolve`() = check("predictionInputResolve", predictionInputResolveGolden()) {
        ContextualPredictionInput.resolve(it, "").let { r -> "${r.stroke}|${r.context}" }
    }

    @Test
    fun `KoreanDiscourseContinuation suggest`() = check("discourseContinuationSuggest", discourseContinuationSuggestGolden()) {
        KoreanDiscourseContinuation.suggest(it).joinToString("|")
    }

    @Test
    fun `SuggestionQualityGate evaluate`() = check("qualityGateEvaluate", qualityGateEvaluateGolden()) {
        SuggestionQualityGate.evaluate(it)
    }

    @Test
    fun `MobileHangulComposer cycle batchim recovery ㅂ`() = check("composerCycleB", composerCycleBGolden()) {
        val composer = MobileHangulComposer()
        composer.setComposingSyllable(it.firstOrNull())
        (0 until 4).joinToString(" / ") { i -> composer.press(cycleB, 100L * i).toString() }
    }

    @Test
    fun `MobileHangulComposer cycle batchim recovery ㄱ`() = check("composerCycleG", composerCycleGGolden()) {
        val composer = MobileHangulComposer()
        composer.setComposingSyllable(it.firstOrNull())
        (0 until 4).joinToString(" / ") { i -> composer.press(cycleG, 100L * i).toString() }
    }

    @Test
    fun `MobileHangulComposer jamo double consonant recovery`() = check("composerJamoDouble", composerJamoDoubleGolden()) {
        val composer = MobileHangulComposer()
        composer.setComposingSyllable(it.firstOrNull())
        listOf(
            composer.press(MobileHangulComposer.Token.Jamo('ㅂ'), 0),
            composer.press(MobileHangulComposer.Token.DoubleConsonant, 10),
            composer.press(MobileHangulComposer.Token.DoubleConsonant, 20),
            composer.press(MobileHangulComposer.Token.AddStroke, 30)
        ).joinToString(" / ")
    }

    private fun choseongEngineDecomposeHangulGolden(): List<Pair<String, String?>> = listOf(
        "가" to "ㄱㅏ",
        "각" to "ㄱㅏㄱ",
        "닭" to "ㄷㅏㄺ",
        "없" to "ㅇㅓㅄ",
        "앉" to "ㅇㅏㄵ",
        "읽" to "ㅇㅣㄺ",
        "까" to "ㄲㅏ",
        "했" to "ㅎㅐㅆ",
        "왜" to "ㅇㅙ",
        "의" to "ㅇㅢ",
        "워" to "ㅇㅝ",
        "달" to "ㄷㅏㄹ",
        "값" to "ㄱㅏㅄ",
        "삶" to "ㅅㅏㄻ",
        "굳" to "ㄱㅜㄷ",
        "힣" to "ㅎㅣㅎ",
        "ㄱ" to "ㄱ",
        "ㅏ" to "ㅏ",
        "ㄳ" to "ㄳ",
        "ㅎ" to "ㅎ",
        "ㅣ" to "ㅣ",
        "a" to "a",
        "A" to "A",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "\uD83D\uDE0A",
        "" to "",
        "\uABFF" to "\uABFF",
        "\uD7A4" to "\uD7A4",
        " " to " ",
        "\u1100" to "\u1100",
        "\u11A8" to "\u11A8",
        "안녕하세요" to "ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ",
        "값이 없다" to "ㄱㅏㅄㅇㅣ ㅇㅓㅄㄷㅏ",
        "한글 abc 닭!" to "ㅎㅏㄴㄱㅡㄹ abc ㄷㅏㄺ!",
        "ㅋㅋ 가나다" to "ㅋㅋ ㄱㅏㄴㅏㄷㅏ",
        "뭐해?" to "ㅁㅝㅎㅐ?",
        "ㅇㅋ" to "ㅇㅋ",
        "한국어123" to "ㅎㅏㄴㄱㅜㄱㅇㅓ123"
    )

    private fun choseongEngineExtractSequenceGolden(): List<Pair<String, String?>> = listOf(
        "가" to "ㄱ",
        "각" to "ㄱ",
        "닭" to "ㄷ",
        "없" to "ㅇ",
        "앉" to "ㅇ",
        "읽" to "ㅇ",
        "까" to "ㄲ",
        "했" to "ㅎ",
        "왜" to "ㅇ",
        "의" to "ㅇ",
        "워" to "ㅇ",
        "달" to "ㄷ",
        "값" to "ㄱ",
        "삶" to "ㅅ",
        "굳" to "ㄱ",
        "힣" to "ㅎ",
        "ㄱ" to "ㄱ",
        "ㅏ" to "",
        "ㄳ" to "",
        "ㅎ" to "ㅎ",
        "ㅣ" to "",
        "a" to "",
        "A" to "",
        "1" to "",
        "7" to "",
        "\uD83D\uDE0A" to "",
        "" to "",
        "\uABFF" to "",
        "\uD7A4" to "",
        " " to "",
        "\u1100" to "",
        "\u11A8" to "",
        "안녕하세요" to "ㅇㄴㅎㅅㅇ",
        "값이 없다" to "ㄱㅇㅇㄷ",
        "한글 abc 닭!" to "ㅎㄱㄷ",
        "ㅋㅋ 가나다" to "ㅋㅋㄱㄴㄷ",
        "뭐해?" to "ㅁㅎ",
        "ㅇㅋ" to "ㅇㅋ",
        "한국어123" to "ㅎㄱㅇ"
    )

    private fun choseongEngineExtractFirstGolden(): List<Pair<String, String?>> = listOf(
        "가" to "ㄱ",
        "각" to "ㄱ",
        "닭" to "ㄷ",
        "없" to "ㅇ",
        "앉" to "ㅇ",
        "읽" to "ㅇ",
        "까" to "ㄲ",
        "했" to "ㅎ",
        "왜" to "ㅇ",
        "의" to "ㅇ",
        "워" to "ㅇ",
        "달" to "ㄷ",
        "값" to "ㄱ",
        "삶" to "ㅅ",
        "굳" to "ㄱ",
        "힣" to "ㅎ",
        "ㄱ" to "ㄱ",
        "ㅏ" to "ㅏ",
        "ㄳ" to "ㄳ",
        "ㅎ" to "ㅎ",
        "ㅣ" to "ㅣ",
        "a" to "a",
        "A" to "A",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "\uD83D",
        "" to "",
        "\uABFF" to "\uABFF",
        "\uD7A4" to "\uD7A4",
        " " to " ",
        "\u1100" to "\u1100",
        "\u11A8" to "\u11A8",
        "안녕하세요" to "ㅇ",
        "값이 없다" to "ㄱ",
        "한글 abc 닭!" to "ㅎ",
        "ㅋㅋ 가나다" to "ㅋ",
        "뭐해?" to "ㅁ",
        "ㅇㅋ" to "ㅇ",
        "한국어123" to "ㅎ"
    )

    private fun choseongEngineIsSyllableGolden(): List<Pair<String, String?>> = listOf(
        "가" to "true",
        "각" to "true",
        "닭" to "true",
        "없" to "true",
        "앉" to "true",
        "읽" to "true",
        "까" to "true",
        "했" to "true",
        "왜" to "true",
        "의" to "true",
        "워" to "true",
        "달" to "true",
        "값" to "true",
        "삶" to "true",
        "굳" to "true",
        "힣" to "true",
        "ㄱ" to "false",
        "ㅏ" to "false",
        "ㄳ" to "false",
        "ㅎ" to "false",
        "ㅣ" to "false",
        "a" to "false",
        "A" to "false",
        "1" to "false",
        "7" to "false",
        "\uABFF" to "false",
        "\uD7A4" to "false",
        " " to "false",
        "\u1100" to "false",
        "\u11A8" to "false"
    )

    private fun choseongEngineIsChoseongGolden(): List<Pair<String, String?>> = listOf(
        "가" to "false",
        "각" to "false",
        "닭" to "false",
        "없" to "false",
        "앉" to "false",
        "읽" to "false",
        "까" to "false",
        "했" to "false",
        "왜" to "false",
        "의" to "false",
        "워" to "false",
        "달" to "false",
        "값" to "false",
        "삶" to "false",
        "굳" to "false",
        "힣" to "false",
        "ㄱ" to "true",
        "ㅏ" to "false",
        "ㄳ" to "false",
        "ㅎ" to "true",
        "ㅣ" to "false",
        "a" to "false",
        "A" to "false",
        "1" to "false",
        "7" to "false",
        "\uABFF" to "false",
        "\uD7A4" to "false",
        " " to "false",
        "\u1100" to "false",
        "\u11A8" to "false"
    )

    private fun typoEngineDecomposeToJasoGolden(): List<Pair<String, String?>> = listOf(
        "가" to "ㄱㅏ",
        "각" to "ㄱㅏㄱ",
        "닭" to "ㄷㅏㄺ",
        "없" to "ㅇㅓㅄ",
        "앉" to "ㅇㅏㄵ",
        "읽" to "ㅇㅣㄺ",
        "까" to "ㄲㅏ",
        "했" to "ㅎㅐㅆ",
        "왜" to "ㅇㅙ",
        "의" to "ㅇㅢ",
        "워" to "ㅇㅝ",
        "달" to "ㄷㅏㄹ",
        "값" to "ㄱㅏㅄ",
        "삶" to "ㅅㅏㄻ",
        "굳" to "ㄱㅜㄷ",
        "힣" to "ㅎㅣㅎ",
        "ㄱ" to "ㄱ",
        "ㅏ" to "ㅏ",
        "ㄳ" to "ㄳ",
        "ㅎ" to "ㅎ",
        "ㅣ" to "ㅣ",
        "a" to "a",
        "A" to "A",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "\uD83D\uDE0A",
        "" to "",
        "\uABFF" to "\uABFF",
        "\uD7A4" to "\uD7A4",
        " " to " ",
        "\u1100" to "\u1100",
        "\u11A8" to "\u11A8",
        "안녕하세요" to "ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ",
        "값이 없다" to "ㄱㅏㅄㅇㅣ ㅇㅓㅄㄷㅏ",
        "한글 abc 닭!" to "ㅎㅏㄴㄱㅡㄹ abc ㄷㅏㄺ!",
        "ㅋㅋ 가나다" to "ㅋㅋ ㄱㅏㄴㅏㄷㅏ",
        "뭐해?" to "ㅁㅝㅎㅐ?",
        "ㅇㅋ" to "ㅇㅋ",
        "한국어123" to "ㅎㅏㄴㄱㅜㄱㅇㅓ123"
    )

    private fun typoEngineQwertyToHangulGolden(): List<Pair<String, String?>> = listOf(
        "dkssud" to "안녕",
        "gksrmf" to "한글",
        "rkskek" to "가나다",
        "ehgkrl" to "도하기",
        "R" to "ㄲ",
        "dk1" to "아1",
        "" to "",
        "rt" to "ㄱㅅ",
        "qkqh" to "바보",
        "dhks" to "완",
        "wkdWl" to "장찌",
        "ahrfk" to "목라",
        "rkr" to "각",
        "ekfr" to "닭",
        "ejrql" to "덕비",
        "tkfka" to "사람",
        "hk" to "ㅘ",
        "hl" to "ㅚ",
        "nj" to "ㅝ",
        "ml" to "ㅢ",
        "Rkr" to "깍"
    )

    private fun dubeolsikKeySequenceGolden(): List<Pair<String, String?>> = listOf(
        "가" to "rk",
        "각" to "rkr",
        "닭" to "ekfr",
        "없" to "djqt",
        "앉" to "dksw",
        "읽" to "dlfr",
        "까" to "Rk",
        "했" to "goT",
        "왜" to "dho",
        "의" to "dml",
        "워" to "dnj",
        "달" to "ekf",
        "값" to "rkqt",
        "삶" to "tkfa",
        "굳" to "rne",
        "힣" to "glg",
        "ㄱ" to "r",
        "ㅏ" to "k",
        "ㄳ" to "rt",
        "ㅎ" to "g",
        "ㅣ" to "l",
        "a" to "a",
        "A" to "a",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "\uD83D\uDE0A",
        "" to "",
        "\uABFF" to "\uABFF",
        "\uD7A4" to "\uD7A4",
        " " to " ",
        "\u1100" to "\u1100",
        "\u11A8" to "\u11A8",
        "안녕하세요" to "dkssudgktpdy",
        "값이 없다" to "rkqtdl djqtek",
        "한글 abc 닭!" to "gksrmf abc ekfr!",
        "ㅋㅋ 가나다" to "zz rkskek",
        "뭐해?" to "anjgo?",
        "ㅇㅋ" to "dz",
        "한국어123" to "gksrnrdj123"
    )

    private fun dubeolsikKeystrokeCountGolden(): List<Pair<String, String?>> = listOf(
        "가" to "2",
        "각" to "3",
        "닭" to "4",
        "없" to "4",
        "앉" to "4",
        "읽" to "4",
        "까" to "3",
        "했" to "4",
        "왜" to "3",
        "의" to "3",
        "워" to "3",
        "달" to "3",
        "값" to "4",
        "삶" to "4",
        "굳" to "3",
        "힣" to "3",
        "ㄱ" to "1",
        "ㅏ" to "1",
        "ㄳ" to "2",
        "ㅎ" to "1",
        "ㅣ" to "1",
        "a" to "1",
        "A" to "1",
        "1" to "1",
        "7" to "1",
        "\uD83D\uDE0A" to "1",
        "" to "0",
        "\uABFF" to "1",
        "\uD7A4" to "1",
        " " to "1",
        "\u1100" to "1",
        "\u11A8" to "1",
        "안녕하세요" to "12",
        "값이 없다" to "13",
        "한글 abc 닭!" to "16",
        "ㅋㅋ 가나다" to "9",
        "뭐해?" to "6",
        "ㅇㅋ" to "2",
        "한국어123" to "11"
    )

    private fun initialsExtractGolden(): List<Pair<String, String?>> = listOf(
        "가" to "ㄱ",
        "각" to "ㄱ",
        "닭" to "ㄷ",
        "없" to "ㅇ",
        "앉" to "ㅇ",
        "읽" to "ㅇ",
        "까" to "ㄲ",
        "했" to "ㅎ",
        "왜" to "ㅇ",
        "의" to "ㅇ",
        "워" to "ㅇ",
        "달" to "ㄷ",
        "값" to "ㄱ",
        "삶" to "ㅅ",
        "굳" to "ㄱ",
        "힣" to "ㅎ",
        "ㄱ" to "ㄱ",
        "ㅏ" to "",
        "ㄳ" to "",
        "ㅎ" to "ㅎ",
        "ㅣ" to "",
        "a" to "",
        "A" to "",
        "1" to "",
        "7" to "",
        "\uD83D\uDE0A" to "",
        "" to "",
        "\uABFF" to "",
        "\uD7A4" to "",
        " " to "",
        "\u1100" to "",
        "\u11A8" to "",
        "안녕하세요" to "ㅇㄴㅎㅅㅇ",
        "값이 없다" to "ㄱㅇㅇㄷ",
        "한글 abc 닭!" to "ㅎㄱㄷ",
        "ㅋㅋ 가나다" to "ㅋㅋㄱㄴㄷ",
        "뭐해?" to "ㅁㅎ",
        "ㅇㅋ" to "ㅇㅋ",
        "한국어123" to "ㅎㄱㅇ"
    )

    private fun initialsIsInitialQueryGolden(): List<Pair<String, String?>> = listOf(
        "ㄱㄴ" to "true",
        "ㅎ" to "true",
        "가" to "false",
        "ㄱ ㄴ" to "true",
        "" to "false",
        "ㄱa" to "false"
    )

    private fun recoveryEnglishToHangulGolden(): List<Pair<String, String?>> = listOf(
        "dkssud" to "안녕",
        "gksrmf" to "한글",
        "rkskek" to "가나다",
        "ehgkrl" to "도하기",
        "R" to "ㄲ",
        "dk1" to "아1",
        "" to "",
        "rt" to "ㄱㅅ",
        "qkqh" to "바보",
        "dhks" to "완",
        "wkdWl" to "장찌",
        "ahrfk" to "목라",
        "rkr" to "각",
        "ekfr" to "닭",
        "ejrql" to "덕비",
        "tkfka" to "사람",
        "hk" to "ㅘ",
        "hl" to "ㅚ",
        "nj" to "ㅝ",
        "ml" to "ㅢ",
        "Rkr" to "깍",
        "ekdrP" to "당계",
        "ghkdrnf" to "황굴",
        "wjdfuf" to "정렬"
    )

    private fun recoveryHangulToEnglishGolden(): List<Pair<String, String?>> = listOf(
        "가" to "rk",
        "각" to "rkr",
        "닭" to "ekfr",
        "없" to "djqt",
        "앉" to "dksw",
        "읽" to "dlfr",
        "까" to "Rk",
        "했" to "goT",
        "왜" to "dho",
        "의" to "dml",
        "워" to "dnj",
        "달" to "ekf",
        "값" to "rkqt",
        "삶" to "tkfa",
        "굳" to "rne",
        "힣" to "glg",
        "ㄱ" to "r",
        "ㅏ" to "k",
        "ㄳ" to "rt",
        "ㅎ" to "g",
        "ㅣ" to "l",
        "a" to "a",
        "A" to "A",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "\uD83D\uDE0A",
        "" to "",
        "\uABFF" to "\uABFF",
        "\uD7A4" to "\uD7A4",
        " " to " ",
        "\u1100" to "\u1100",
        "\u11A8" to "\u11A8",
        "안녕하세요" to "dkssudgktpdy",
        "값이 없다" to "rkqtdl djqtek",
        "한글 abc 닭!" to "gksrmf abc ekfr!",
        "ㅋㅋ 가나다" to "zz rkskek",
        "뭐해?" to "anjgo?",
        "ㅇㅋ" to "dz",
        "한국어123" to "gksrnrdj123"
    )

    private fun recoveryProposalsGolden(): List<Pair<String, String?>> = listOf(
        "가" to "HangulToEnglish:rk",
        "각" to "HangulToEnglish:rkr",
        "닭" to "HangulToEnglish:ekfr",
        "없" to "HangulToEnglish:djqt",
        "앉" to "HangulToEnglish:dksw",
        "읽" to "HangulToEnglish:dlfr",
        "까" to "HangulToEnglish:Rk",
        "했" to "HangulToEnglish:goT",
        "왜" to "HangulToEnglish:dho",
        "의" to "HangulToEnglish:dml",
        "워" to "HangulToEnglish:dnj",
        "달" to "HangulToEnglish:ekf",
        "값" to "HangulToEnglish:rkqt",
        "삶" to "HangulToEnglish:tkfa",
        "굳" to "HangulToEnglish:rne",
        "힣" to "HangulToEnglish:glg",
        "ㄱ" to "HangulToEnglish:r",
        "ㅏ" to "HangulToEnglish:k",
        "ㄳ" to "HangulToEnglish:rt",
        "ㅎ" to "HangulToEnglish:g",
        "ㅣ" to "HangulToEnglish:l",
        "a" to "EnglishToHangul:ㅁ",
        "A" to "EnglishToHangul:ㅁ",
        "1" to "",
        "7" to "",
        "\uD83D\uDE0A" to "",
        "" to "",
        "\uABFF" to "",
        "\uD7A4" to "",
        " " to "",
        "\u1100" to "",
        "\u11A8" to "",
        "안녕하세요" to "HangulToEnglish:dkssudgktpdy",
        "값이 없다" to "HangulToEnglish:rkqtdl djqtek",
        "한글 abc 닭!" to "EnglishToHangul:한글 뮻 닭!|HangulToEnglish:gksrmf abc ekfr!",
        "ㅋㅋ 가나다" to "HangulToEnglish:zz rkskek",
        "뭐해?" to "HangulToEnglish:anjgo?",
        "ㅇㅋ" to "HangulToEnglish:dz",
        "한국어123" to "HangulToEnglish:gksrnrdj123"
    )

    private fun recoveryLastChunkGolden(): List<Pair<String, String?>> = listOf(
        "가" to "가|",
        "각" to "각|",
        "닭" to "닭|",
        "없" to "없|",
        "앉" to "앉|",
        "읽" to "읽|",
        "까" to "까|",
        "했" to "했|",
        "왜" to "왜|",
        "의" to "의|",
        "워" to "워|",
        "달" to "달|",
        "값" to "값|",
        "삶" to "삶|",
        "굳" to "굳|",
        "힣" to "힣|",
        "ㄱ" to "ㄱ|",
        "ㅏ" to "ㅏ|",
        "ㄳ" to "ㄳ|",
        "ㅎ" to "ㅎ|",
        "ㅣ" to "ㅣ|",
        "a" to "a|",
        "A" to "A|",
        "1" to null,
        "7" to null,
        "\uD83D\uDE0A" to null,
        "" to null,
        "\uABFF" to null,
        "\uD7A4" to null,
        " " to null,
        "\u1100" to null,
        "\u11A8" to null,
        "안녕하세요" to "안녕하세요|",
        "값이 없다" to "없다|",
        "한글 abc 닭!" to "닭|!",
        "ㅋㅋ 가나다" to "가나다|",
        "뭐해?" to "뭐해|?",
        "ㅇㅋ" to "ㅇㅋ|",
        "한국어123" to null,
        "abc 안녕ㅎㅎ." to "안녕ㅎㅎ|.",
        "ㅁㄴㅇ!!" to "ㅁㄴㅇ|!!",
        "hello 닭a" to "닭a|"
    )

    private fun josaBatchimCodeGolden(): List<Pair<String, String?>> = listOf(
        "가" to "0",
        "각" to "1",
        "닭" to "9",
        "없" to "18",
        "앉" to "5",
        "읽" to "9",
        "까" to "0",
        "했" to "20",
        "왜" to "0",
        "의" to "0",
        "워" to "0",
        "달" to "8",
        "값" to "18",
        "삶" to "10",
        "굳" to "7",
        "힣" to "27",
        "ㄱ" to "-1",
        "ㅏ" to "-1",
        "ㄳ" to "-1",
        "ㅎ" to "-1",
        "ㅣ" to "-1",
        "a" to "-1",
        "A" to "-1",
        "1" to "-1",
        "7" to "-1",
        "\uABFF" to "-1",
        "\uD7A4" to "-1",
        " " to "-1",
        "\u1100" to "-1",
        "\u11A8" to "-1"
    )

    private fun josaHasBatchimGolden(): List<Pair<String, String?>> = listOf(
        "가" to "false",
        "각" to "true",
        "닭" to "true",
        "없" to "true",
        "앉" to "true",
        "읽" to "true",
        "까" to "false",
        "했" to "true",
        "왜" to "false",
        "의" to "false",
        "워" to "false",
        "달" to "true",
        "값" to "true",
        "삶" to "true",
        "굳" to "true",
        "힣" to "true",
        "ㄱ" to "false",
        "ㅏ" to "false",
        "ㄳ" to "false",
        "ㅎ" to "false",
        "ㅣ" to "false",
        "a" to "false",
        "A" to "false",
        "1" to "true",
        "7" to "true",
        "\uABFF" to "false",
        "\uD7A4" to "false",
        " " to "false",
        "\u1100" to "false",
        "\u11A8" to "false"
    )

    private fun josaIsRieulBatchimGolden(): List<Pair<String, String?>> = listOf(
        "가" to "false",
        "각" to "false",
        "닭" to "false",
        "없" to "false",
        "앉" to "false",
        "읽" to "false",
        "까" to "false",
        "했" to "false",
        "왜" to "false",
        "의" to "false",
        "워" to "false",
        "달" to "true",
        "값" to "false",
        "삶" to "false",
        "굳" to "false",
        "힣" to "false",
        "ㄱ" to "false",
        "ㅏ" to "false",
        "ㄳ" to "false",
        "ㅎ" to "false",
        "ㅣ" to "false",
        "a" to "false",
        "A" to "false",
        "1" to "true",
        "7" to "true",
        "\uABFF" to "false",
        "\uD7A4" to "false",
        " " to "false",
        "\u1100" to "false",
        "\u11A8" to "false"
    )

    private fun josaFlagsCharGolden(): List<Pair<String, String?>> = listOf(
        "가" to "1",
        "각" to "10",
        "닭" to "10",
        "없" to "10",
        "앉" to "10",
        "읽" to "10",
        "까" to "1",
        "했" to "10",
        "왜" to "1",
        "의" to "1",
        "워" to "1",
        "달" to "6",
        "값" to "10",
        "삶" to "10",
        "굳" to "10",
        "힣" to "10",
        "ㄱ" to "10",
        "ㅏ" to "1",
        "ㄳ" to "10",
        "ㅎ" to "1",
        "ㅣ" to "1",
        "a" to "1",
        "A" to "1",
        "1" to "6",
        "7" to "6",
        "\uABFF" to "0",
        "\uD7A4" to "0",
        " " to "0",
        "\u1100" to "10",
        "\u11A8" to "10"
    )

    private fun josaFlagsStringGolden(): List<Pair<String, String?>> = listOf(
        "가" to "1",
        "각" to "10",
        "닭" to "10",
        "없" to "10",
        "앉" to "10",
        "읽" to "10",
        "까" to "1",
        "했" to "10",
        "왜" to "1",
        "의" to "1",
        "워" to "1",
        "달" to "6",
        "값" to "10",
        "삶" to "10",
        "굳" to "10",
        "힣" to "10",
        "ㄱ" to "10",
        "ㅏ" to "1",
        "ㄳ" to "10",
        "ㅎ" to "1",
        "ㅣ" to "1",
        "a" to "1",
        "A" to "1",
        "1" to "6",
        "7" to "6",
        "\uD83D\uDE0A" to "1",
        "" to "0",
        "\uABFF" to "0",
        "\uD7A4" to "0",
        " " to "0",
        "\u1100" to "10",
        "\u11A8" to "10",
        "안녕하세요" to "1",
        "값이 없다" to "1",
        "한글 abc 닭!" to "0",
        "ㅋㅋ 가나다" to "1",
        "뭐해?" to "0",
        "ㅇㅋ" to "1",
        "한국어123" to "10"
    )

    private fun josaCorrectMismatchGolden(): List<Pair<String, String?>> = listOf(
        "학교를" to "학교를",
        "학교을" to "학교를",
        "사과가" to "사과가",
        "사과이" to "사과가",
        "물로" to "물로",
        "물으로" to "물로",
        "집로" to "집으로",
        "집으로" to "집으로",
        "서울으로" to "서울로",
        "서울로" to "서울로",
        "닭이" to "닭이",
        "닭가" to "닭이",
        "없는" to "없은",
        "없은" to "없은",
        "읽을" to "읽을",
        "읽를" to "읽을",
        "왜가" to "왜가",
        "의가" to "의가",
        "워는" to "워는",
        "워은" to "워는"
    )

    private fun particleSuggesterSuggestGolden(): List<Pair<String, String?>> = listOf(
        "가" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "각" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "닭" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "없" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "앉" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "읽" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "까" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "했" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "왜" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "의" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "워" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "달" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:로|Copula:이에요",
        "값" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "삶" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "굳" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "힣" to "Topic:은|Subject:이|Object:을|Conjunction:과|Direction:으로|Copula:이에요",
        "ㄱ" to "",
        "ㅏ" to "",
        "ㄳ" to "",
        "ㅎ" to "",
        "ㅣ" to "",
        "a" to "",
        "A" to "",
        "1" to "",
        "7" to "",
        "\uD83D\uDE0A" to "",
        "" to "",
        "\uABFF" to "",
        "\uD7A4" to "",
        " " to "",
        "\u1100" to "",
        "\u11A8" to "",
        "안녕하세요" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "값이 없다" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "한글 abc 닭!" to "",
        "ㅋㅋ 가나다" to "Topic:는|Subject:가|Object:를|Conjunction:와|Direction:로|Copula:예요",
        "뭐해?" to "",
        "ㅇㅋ" to "",
        "한국어123" to ""
    )

    private fun collocationPredictNextWordsGolden(): List<Pair<String, String?>> = listOf(
        "회의이" to "끝나고 연락할게|들어갈게|준비 다 됐어#참석|일정|준비|내용",
        "회의가" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "회의을" to "끝나고 연락할게|들어갈게|준비 다 됐어#참석|일정|준비|내용",
        "회의를" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "회의은" to "끝나고 연락할게|들어갈게|준비 다 됐어#참석|일정|준비|내용",
        "회의는" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "회의과" to "끝나고 연락할게|들어갈게|준비 다 됐어#참석|일정|준비|내용",
        "회의와" to "같이|함께#함께|관련하여|동일하게",
        "회의으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "회의로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "회의에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "학교이" to "#",
        "학교가" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "학교을" to "#",
        "학교를" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "학교은" to "#",
        "학교는" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "학교과" to "#",
        "학교와" to "같이|함께#함께|관련하여|동일하게",
        "학교으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "학교로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "학교에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "책이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "책가" to "#",
        "책을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "책를" to "#",
        "책은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "책는" to "#",
        "책과" to "같이|함께#함께|관련하여|동일하게",
        "책와" to "#",
        "책으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "책로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "책에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "닭이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "닭가" to "#",
        "닭을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "닭를" to "#",
        "닭은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "닭는" to "#",
        "닭과" to "같이|함께#함께|관련하여|동일하게",
        "닭와" to "#",
        "닭으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "닭로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "닭에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "서울이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "서울가" to "#",
        "서울을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "서울를" to "#",
        "서울은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "서울는" to "#",
        "서울과" to "같이|함께#함께|관련하여|동일하게",
        "서울와" to "#",
        "서울으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "서울로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "서울에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "집이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "집가" to "#",
        "집을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "집를" to "#",
        "집은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "집는" to "#",
        "집과" to "같이|함께#함께|관련하여|동일하게",
        "집와" to "#",
        "집으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "집로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "집에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "a이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "a가" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "a을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "a를" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "a은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "a는" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "a과" to "같이|함께#함께|관련하여|동일하게",
        "a와" to "같이|함께#함께|관련하여|동일하게",
        "a으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "a로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "a에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "1이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "1가" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "1을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "1를" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "1은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "1는" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "1과" to "같이|함께#함께|관련하여|동일하게",
        "1와" to "같이|함께#함께|관련하여|동일하게",
        "1으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "1로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "1에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다",
        "ㅋ이" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "ㅋ가" to "필요해|있어|어려울 것 같아|끝났어#필요합니다|있습니다|어려울 것 같습니다|완료되었습니다",
        "ㅋ을" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "ㅋ를" to "확인했어|부탁해|보내줄게|공유할게#확인했습니다|부탁드립니다|검토하겠습니다|보내드립니다",
        "ㅋ은" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "ㅋ는" to "어때?|확인했어?|괜찮아?|어려워#어떠신가요?|확인하셨나요?|괜찮으신가요?|어려울 것 같습니다",
        "ㅋ과" to "같이|함께#함께|관련하여|동일하게",
        "ㅋ와" to "같이|함께#함께|관련하여|동일하게",
        "ㅋ으로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "ㅋ로" to "할게|보낼게|바꿨어|가자#진행하겠습니다|보내드리겠습니다|변경되었습니다|정리했습니다",
        "ㅋ에서" to "보자!|만나자|기다릴게|볼까?#뵙겠습니다|만나요|진행됩니다|기다리겠습니다"
    )

    private fun ngramTokenizerStemGolden(): List<Pair<String, String?>> = listOf(
        "회의이" to null,
        "회의가" to "회의",
        "회의을" to null,
        "회의를" to "회의",
        "회의은" to null,
        "회의는" to "회의",
        "회의과" to null,
        "회의와" to "회의",
        "회의으로" to "회의",
        "회의로" to "회의",
        "회의에서" to "회의",
        "학교이" to null,
        "학교가" to "학교",
        "학교을" to null,
        "학교를" to "학교",
        "학교은" to null,
        "학교는" to "학교",
        "학교과" to null,
        "학교와" to "학교",
        "학교으로" to "학교",
        "학교로" to "학교",
        "학교에서" to "학교",
        "책이" to "책",
        "책가" to null,
        "책을" to "책",
        "책를" to null,
        "책은" to "책",
        "책는" to null,
        "책과" to "책",
        "책와" to null,
        "책으로" to "책",
        "책로" to "책",
        "책에서" to "책",
        "닭이" to "닭",
        "닭가" to null,
        "닭을" to "닭",
        "닭를" to null,
        "닭은" to "닭",
        "닭는" to null,
        "닭과" to "닭",
        "닭와" to null,
        "닭으로" to "닭",
        "닭로" to "닭",
        "닭에서" to "닭",
        "서울이" to "서울",
        "서울가" to null,
        "서울을" to "서울",
        "서울를" to null,
        "서울은" to "서울",
        "서울는" to null,
        "서울과" to "서울",
        "서울와" to null,
        "서울으로" to "서울",
        "서울로" to "서울",
        "서울에서" to "서울",
        "집이" to "집",
        "집가" to null,
        "집을" to "집",
        "집를" to null,
        "집은" to "집",
        "집는" to null,
        "집과" to "집",
        "집와" to null,
        "집으로" to "집",
        "집로" to "집",
        "집에서" to "집",
        "a이" to "a",
        "a가" to "a",
        "a을" to "a",
        "a를" to "a",
        "a은" to "a",
        "a는" to "a",
        "a과" to "a",
        "a와" to "a",
        "a으로" to "a",
        "a로" to "a",
        "a에서" to "a",
        "1이" to "1",
        "1가" to "1",
        "1을" to "1",
        "1를" to "1",
        "1은" to "1",
        "1는" to "1",
        "1과" to "1",
        "1와" to "1",
        "1으로" to "1",
        "1로" to "1",
        "1에서" to "1",
        "ㅋ이" to "ㅋ",
        "ㅋ가" to "ㅋ",
        "ㅋ을" to "ㅋ",
        "ㅋ를" to "ㅋ",
        "ㅋ은" to "ㅋ",
        "ㅋ는" to "ㅋ",
        "ㅋ과" to "ㅋ",
        "ㅋ와" to "ㅋ",
        "ㅋ으로" to "ㅋ",
        "ㅋ로" to "ㅋ",
        "ㅋ에서" to "ㅋ"
    )

    private fun ngramTokenizerTokenizeGolden(): List<Pair<String, String?>> = listOf(
        "가" to "가",
        "각" to "각",
        "닭" to "닭",
        "없" to "없",
        "앉" to "앉",
        "읽" to "읽",
        "까" to "까",
        "했" to "했",
        "왜" to "왜",
        "의" to "의",
        "워" to "워",
        "달" to "달",
        "값" to "값",
        "삶" to "삶",
        "굳" to "굳",
        "힣" to "힣",
        "ㄱ" to "",
        "ㅏ" to "",
        "ㄳ" to "",
        "ㅎ" to "ㅎ",
        "ㅣ" to "",
        "a" to "",
        "A" to "",
        "1" to "1",
        "7" to "7",
        "\uD83D\uDE0A" to "",
        "" to "",
        "\uABFF" to "",
        "\uD7A4" to "",
        " " to "",
        "\u1100" to "",
        "\u11A8" to "",
        "안녕하세요" to "안녕하세요",
        "값이 없다" to "값이|없다",
        "한글 abc 닭!" to "한글|닭",
        "ㅋㅋ 가나다" to "ㅋㅋ|가나다",
        "뭐해?" to "뭐해",
        "ㅇㅋ" to "ㅇㅋ",
        "한국어123" to "한국어123",
        "회의를 했어요 [PII] test1234" to "회의를|했어요",
        "ㅋㅋ \uD83D\uDE0A 가" to "ㅋㅋ|가"
    )

    private fun toneClassifierInferGolden(): List<Pair<String, String?>> = listOf(
        "내일 갈까" to "Honorific",
        "내일 가까" to "Honorific",
        "내일 갈까." to "Informal",
        "내일 가까." to "Honorific",
        "내일 값까" to "Honorific",
        "내일 할까?" to "Informal",
        "내일 a까?" to "Honorific",
        "내일 1까?" to "Honorific",
        "내일 ㄹ까?" to "Honorific",
        "내일 각까?" to "Honorific",
        "까?" to "Honorific",
        "ㄹ까?" to "Honorific",
        "\uD83D\uDE0A까?" to "Honorific",
        "내일 올까?" to "Informal"
    )

    private fun endingAnalyzerExtractEndingGolden(): List<Pair<String, String?>> = listOf(
        "갈게" to "ㄹ게",
        "가게" to null,
        "갈게요" to "ㄹ게요",
        "갈까" to "ㄹ까",
        "가까" to null,
        "갈까요" to "ㄹ까요",
        "합니다" to "합니다",
        "하다" to "다",
        "한다" to "다",
        "가다" to "다",
        "믿음" to null,
        "먹음" to "음",
        "갔어서" to "어서",
        "살아서" to "아서",
        "나서" to null,
        "닭" to null,
        "없어서" to "어서",
        "알아서요" to "아서요",
        "가서" to null,
        "모르겠음" to "겠음",
        "합니까" to "합니까",
        "하니까" to null,
        "ㄹ게" to "ㄹ게",
        "a게" to null,
        "1게" to null,
        "\uD83D\uDE0A게" to null,
        "임" to null,
        "음" to null,
        "가는데" to null,
        "먹어요" to "어요",
        "앉아서" to "아서",
        "읽어서" to "어서",
        "왔어서" to "어서",
        "했어서" to "어서",
        "갑니다" to "ㅂ니다",
        "갑니까" to "ㅂ니까",
        "간다" to "ㄴ다",
        "하면" to null,
        "봤음" to "음",
        "봄" to null
    )

    private fun personalWordIsValidGolden(): List<Pair<String, String?>> = listOf(
        "가" to "false",
        "각" to "false",
        "닭" to "false",
        "없" to "false",
        "앉" to "false",
        "읽" to "false",
        "까" to "false",
        "했" to "false",
        "왜" to "false",
        "의" to "false",
        "워" to "false",
        "달" to "false",
        "값" to "false",
        "삶" to "false",
        "굳" to "false",
        "힣" to "false",
        "ㄱ" to "false",
        "ㅏ" to "false",
        "ㄳ" to "false",
        "ㅎ" to "false",
        "ㅣ" to "false",
        "a" to "false",
        "A" to "false",
        "1" to "false",
        "7" to "false",
        "\uD83D\uDE0A" to "false",
        "" to "false",
        "\uABFF" to "false",
        "\uD7A4" to "false",
        " " to "false",
        "\u1100" to "false",
        "\u11A8" to "false",
        "안녕하세요" to "true",
        "값이 없다" to "false",
        "한글 abc 닭!" to "false",
        "ㅋㅋ 가나다" to "false",
        "뭐해?" to "false",
        "ㅇㅋ" to "false",
        "한국어123" to "false",
        "가나" to "true",
        "가나다ㄱ" to "false",
        "ab" to "false",
        "한글" to "true"
    )

    private fun predictionInputResolveGolden(): List<Pair<String, String?>> = listOf(
        "안녕하세요.가" to "가|안녕하세요.",
        "안녕.ㄱ" to "ㄱ|안녕.",
        "안녕.a" to "안녕.a|",
        "안녕.\uD83D\uDE0A" to "안녕.\uD83D\uDE0A|",
        "안녕?닭" to "닭|안녕?",
        "안녕.1" to "안녕.1|",
        "안녕…ㅏ" to "ㅏ|안녕…",
        "안녕.\uD7A4" to "안녕.\uD7A4|",
        "안녕.\uABFF" to "안녕.\uABFF|",
        "안녕.ㆎ" to "ㆎ|안녕.",
        "안녕.㆏" to "안녕.㆏|",
        "안녕.㄰" to "안녕.㄰|"
    )

    private fun discourseContinuationSuggestGolden(): List<Pair<String, String?>> = listOf(
        "오늘은 날씨가 좋았어요. " to "그리고|그런데|이제",
        "ㅋㅋ." to "",
        "hello." to "",
        "오늘 비가 와요." to "그리고|그런데|이제",
        "오늘 비가 와요. " to "그리고|그런데|이제",
        "Hi 가." to "그리고|그런데|이제",
        "ㅏ가." to "",
        "가." to "그리고|그런데|이제",
        "1가." to "그리고|그런데|이제",
        "아직 안했지만 " to "그래도|아직",
        "했는데 " to "아직|생각보다|그래도",
        "했는데" to "",
        "아직 ㅎ는데 " to "",
        "ㅎ." to "",
        "\uD83D\uDE0A." to "",
        "\uD7A4." to "",
        "\uABFF." to "",
        "와요.\n나는." to "그리고|그런데|이제"
    )

    private fun qualityGateEvaluateGolden(): List<Pair<String, String?>> = listOf(
        "가ㅋ" to "MIXED_JAMO",
        "ㅋㅋ" to null,
        "가나다" to null,
        "ㅇㅋ" to null,
        "가ㅏ" to "MIXED_JAMO",
        "ㄱ가" to "MIXED_JAMO",
        "a" to null,
        "\uD83D\uDE0A" to null,
        "\uD7A4ㅋ" to null,
        "ㅋ\uD7A4" to null
    )

    private fun composerCycleBGolden(): List<Pair<String, String?>> = listOf(
        "가" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=rkq)]",
        "각" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "닭" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "없" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "앉" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "읽" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "까" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=Rkq)]",
        "했" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "왜" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dhoq)]",
        "의" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dmlq)]",
        "워" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dnjq)]",
        "달" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=ekfq)]",
        "값" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "삶" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "굳" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "힣" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "ㄱ" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "ㅏ" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "ㄳ" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "ㅎ" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "ㅣ" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "a" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "A" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "1" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "7" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "\uABFF" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "\uD7A4" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        " " to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "\u1100" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "\u11A8" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "나" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=skq)]",
        "납" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "달" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=ekfq)]",
        "갑" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "닭" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]",
        "않" to "[Keys(value=q)] / [Backspace, Keys(value=v)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)]"
    )

    private fun composerCycleGGolden(): List<Pair<String, String?>> = listOf(
        "가" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "각" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "닭" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "없" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "앉" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "읽" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "까" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "했" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "왜" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "의" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "워" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "달" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Backspace, Keys(value=ekfr)]",
        "값" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "삶" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "굳" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "힣" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "ㄱ" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "ㅏ" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "ㄳ" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "ㅎ" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "ㅣ" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "a" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "A" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "1" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "7" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "\uABFF" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "\uD7A4" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        " " to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "\u1100" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "\u11A8" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "나" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "납" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "달" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Backspace, Keys(value=ekfr)]",
        "갑" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "닭" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]",
        "않" to "[Keys(value=r)] / [Backspace, Keys(value=z)] / [Backspace, Keys(value=R)] / [Backspace, Keys(value=r)]"
    )

    private fun composerJamoDoubleGolden(): List<Pair<String, String?>> = listOf(
        "가" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=rkq)] / [Backspace, Keys(value=v)]",
        "각" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "닭" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "없" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "앉" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "읽" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "까" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=Rkq)] / [Backspace, Keys(value=v)]",
        "했" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "왜" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dhoq)] / [Backspace, Keys(value=v)]",
        "의" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dmlq)] / [Backspace, Keys(value=v)]",
        "워" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=dnjq)] / [Backspace, Keys(value=v)]",
        "달" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=ekfq)] / [Backspace, Keys(value=v)]",
        "값" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "삶" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "굳" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "힣" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "ㄱ" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "ㅏ" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "ㄳ" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "ㅎ" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "ㅣ" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "a" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "A" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "1" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "7" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "\uABFF" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "\uD7A4" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        " " to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "\u1100" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "\u11A8" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "나" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=skq)] / [Backspace, Keys(value=v)]",
        "납" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "달" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Backspace, Keys(value=ekfq)] / [Backspace, Keys(value=v)]",
        "갑" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "닭" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]",
        "않" to "[Keys(value=q)] / [Backspace, Keys(value=Q)] / [Backspace, Keys(value=q)] / [Backspace, Keys(value=v)]"
    )
}
