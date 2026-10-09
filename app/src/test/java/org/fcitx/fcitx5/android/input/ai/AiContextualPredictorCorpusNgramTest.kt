/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.CandidateRequest
import org.fcitx.fcitx5.android.input.ai.prediction.source.CorpusNgramSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class AiContextualPredictorCorpusNgramTest {

    companion object {
        private lateinit var corpus: BundledKoreanNgram

        @BeforeClass
        @JvmStatic
        fun loadAsset() {
            val file = listOf(
                "app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../src/main/assets/${BundledKoreanNgram.ASSET_PATH}"
            ).map(::File).first { it.exists() }
            corpus = file.inputStream().use(BundledKoreanNgram::read)
        }
    }

    private fun predictor(ngram: PersonalNgramModel = PersonalNgramModel()) = AiContextualPredictor(
        morphology = ChoseongMorphologyEngine(),
        ngram = ngram,
        bundledNgram = { corpus }
    )

    @Test
    fun nextWordModeOffersCorpusContinuationWithoutBadge() {
        val results = predictor().predict("", "퇴근하고 ", "com.example.chat", limit = 10)
        val corpusWords = results.filter { it.source == "corpus_ngram" }
        assertTrue(results.toString(), corpusWords.any { it.text == "집에" })
        assertTrue(corpusWords.all { !it.isSentenceCompletion && it.badge.isEmpty() })
        assertTrue(corpusWords.size <= 4)
    }

    @Test
    fun strokeModeFiltersCorpusCandidatesByJamoPrefix() {
        val results = predictor().predict("지", "퇴근하고 ", "com.example.chat", limit = 10)
        val corpusWords = results.filter { it.source == "corpus_ngram" }.map { it.text }
        assertTrue(results.toString(), "집에" in corpusWords)
        assertTrue(corpusWords.toString(), corpusWords.all { it.startsWith("지") || it.startsWith("집") })
    }

    @Test
    fun choseongStrokeMatchesOnlyFromWordStart() {
        val results = predictor().predict("ㅈ", "퇴근하고 ", "com.example.chat", limit = 10)
        val corpusWords = results.filter { it.source == "corpus_ngram" }.map { it.text }
        val morphology = ChoseongMorphologyEngine()
        assertTrue(corpusWords.toString(), corpusWords.all { morphology.extractChoseongSequence(it).startsWith("ㅈ") })
    }

    @Test
    fun sentenceBoundaryAndMissingAssetYieldNoCorpusCandidates() {
        val afterPeriod = predictor().predict("", "퇴근했다. ", "com.example.chat", limit = 10)
        assertTrue(afterPeriod.none { it.source == "corpus_ngram" })
        val withoutAsset = AiContextualPredictor(morphology = ChoseongMorphologyEngine())
            .predict("", "퇴근하고 ", "com.example.chat", limit = 10)
        assertTrue(withoutAsset.none { it.source == "corpus_ngram" })
    }

    @Test
    fun corpusPredictorRetainsConnectiveDiscourseContinuation() {
        val context = "밥 먹었는데 "
        val results = predictor().predict("", context, "com.example.chat", limit = 10)
        val discourse = results.firstOrNull { it.source == "discourse_continuation" }

        assertTrue(results.toString(), discourse != null)
        assertTrue(discourse?.append?.insertionFor(context) != null)
    }

    @Test
    fun corpusDeuridaIsNotOfferedDetachedFromTheNounBeforeTheSpace() {
        val results = predictor().predict("", "확인 부탁 ", "com.example.chat", limit = 10)
        val detached = results.filter { prediction ->
            listOf("드리", "드립", "드려", "드렸", "드릴", "드린", "드림").any { prediction.text.startsWith(it) }
        }
        assertTrue(detached.map { "${it.text}(${it.source})" }.toString(), detached.isEmpty())
    }

    @Test
    fun staticCollocationFallsBelowCorpusButLearnedBigramStaysAbove() {
        val collocation = KoreanCollocationModel()
        collocation.injectDynamicBigrams(mapOf("지금" to listOf("헬스장")), isInformal = false)
        collocation.injectDynamicBigrams(mapOf("지금" to listOf("헬스장")), isInformal = true)
        val results = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            collocationModel = collocation,
            bundledNgram = { corpus }
        ).predict("", "지금 ", "com.example.chat", limit = 10)

        val scoreOf = results.filter { !it.isSentenceCompletion }.associate { it.text to it.confidenceScore }
        val corpusScores = results.filter { it.source == "corpus_ngram" }.map { it.confidenceScore }
        val staticScores = results
            .filter { it.source == "collocation_next_word" && it.text != "헬스장" }
            .map { it.confidenceScore }
        assertTrue(results.toString(), corpusScores.isNotEmpty() && staticScores.isNotEmpty())
        assertTrue(results.toString(), staticScores.max() < corpusScores.min())
        assertTrue(results.toString(), scoreOf.getValue("헬스장") > corpusScores.max())
    }

    @Test
    fun personalNgramOutranksCorpusForTheSameContext() {
        val ngram = PersonalNgramModel()
        repeat(5) { ngram.learn("퇴근하고 헬스장 가자", "com.example.chat") }
        val results = predictor(ngram).predict("", "퇴근하고 ", "com.example.chat", limit = 10)
        val words = results.filter { !it.isSentenceCompletion }.map { it.text }
        val personalIdx = words.indexOf("헬스장")
        val corpusIdx = words.indexOf("집에")
        assertTrue(words.toString(), personalIdx >= 0 && corpusIdx >= 0)
        assertTrue(words.toString(), personalIdx < corpusIdx)
        assertEquals("personal_ngram", results.first { it.text == "헬스장" }.source)
    }

    private fun startPredictor() = AiContextualPredictor(
        morphology = ChoseongMorphologyEngine(),
        bundledNgram = {
            SyntheticKoreanNgram.parse(
                mapOf(
                    BundledKoreanNgram.SENTENCE_START to listOf("그" to 50, "그리고" to 30, "하지만" to 15),
                    "퇴근하고" to listOf("집에" to 10, "그냥" to 5)
                )
            )
        }
    )

    private fun corpusWords(stroke: String, context: String) =
        startPredictor().predict(stroke, context, "com.example.chat", limit = 10)
            .filter { it.source == "corpus_ngram" }

    @Test
    fun sentenceStartStrokeOffersStartDistributionInStoredOrder() {
        for (context in listOf("", "  ", "안녕. ", "끝났다?", "줄\n")) {
            val results = corpusWords("ㄱ", context)
            assertEquals("context=[$context] $results", listOf("그", "그리고"), results.map { it.text })
            assertTrue(results.all { !it.isSentenceCompletion && it.badge.isEmpty() })
            assertTrue(results[0].confidenceScore > results[1].confidenceScore)
            assertEquals(0.957f, results[0].confidenceScore, 1e-6f)
        }
    }

    @Test
    fun sentenceStartWithoutStrokeOffersNoCorpusCandidates() {
        for (context in listOf("", "안녕. ", "줄\n")) {
            assertTrue(context, corpusWords("", context).isEmpty())
        }
    }

    @Test
    fun midSentenceKeepsContextBigramAndIgnoresStartDistribution() {
        val afterWord = corpusWords("ㅈ", "퇴근하고 ").map { it.text }
        assertEquals(listOf("집에"), afterWord)
        val afterNonHangul = corpusWords("ㄱ", "2023년 ")
        assertTrue(afterNonHangul.toString(), afterNonHangul.isEmpty())
        val unknownWord = corpusWords("ㄱ", "처음 보는 ")
        assertTrue(unknownWord.toString(), unknownWord.isEmpty())
    }

    @Test
    fun openingQuoteOrBracketBeforeTheFirstWordStillOffersSentenceStartCandidates() {
        val plain = corpusWords("ㄱ", "")
        assertEquals(listOf("그", "그리고"), plain.map { it.text })
        for (head in listOf("\"", "'", "(", "[", "{", "“", "‘", "「", "『", "〈", "《")) {
            for (context in listOf("", "안녕. ", "줄\n")) {
                val results = corpusWords("${head}ㄱ", context)
                assertEquals("head=[$head] context=[$context] $results", plain.map { it.text }, results.map { it.text })
                assertEquals(plain.map { it.confidenceScore }, results.map { it.confidenceScore })
            }
        }
        assertEquals(listOf("그리고"), corpusWords("\"그리", "").map { it.text })
        assertEquals(listOf("그", "그리고"), corpusWords("((ㄱ", "").map { it.text })
    }

    @Test
    fun openingQuoteBeforeAMidSentenceWordKeepsContextBigramCandidates() {
        assertEquals(listOf("집에"), corpusWords("\"ㅈ", "퇴근하고 ").map { it.text })
        assertEquals(listOf("집에"), corpusWords("(집", "퇴근하고 ").map { it.text })
    }

    @Test
    fun bareOpeningQuoteOffersNoCorpusCandidates() {
        assertTrue(corpusWords("\"", "").isEmpty())
        assertTrue(corpusWords("((", "").isEmpty())
        assertTrue(corpusWords("\"", "퇴근하고 ").isEmpty())
    }

    @Test
    fun unreadableTextBeforeCursorGivesNoSentenceStartCandidates() {
        val source = CorpusNgramSource(ChoseongMorphologyEngine()) {
            SyntheticKoreanNgram.parse(
                mapOf(BundledKoreanNgram.SENTENCE_START to listOf("그" to 50, "그리고" to 30))
            )
        }
        fun collect(input: PredictionInput) = source.collect(
            CandidateRequest(input, "com.example.chat", 10, "", false, emptyList(), emptyList())
        )
        assertEquals(listOf("그", "그리고"), collect(PredictionInput("ㄱ", "")).map { it.text })
        assertEquals(
            listOf("그", "그리고"),
            collect(PredictionInput("ㄱ", "", isContextKnown = true)).map { it.text }
        )
        assertTrue(collect(PredictionInput("ㄱ", "", isContextKnown = false)).isEmpty())
    }

    @Test
    fun predictorOffersNoSentenceStartCandidatesWhenTheContextIsUnreadable() {
        val known = startPredictor().predict("ㄱ", "", "com.example.chat", limit = 10)
        assertTrue(known.toString(), known.any { it.source == "corpus_ngram" })
        val unknown = startPredictor().predict("ㄱ", "", "com.example.chat", limit = 10, isContextKnown = false)
        assertTrue(unknown.toString(), unknown.none { it.source == "corpus_ngram" })
    }

    @Test
    fun hangulTypedRightAfterSentenceEndingMarkOffersSentenceStartCandidates() {
        val resolved = ContextualPredictionInput.resolve("고마워요.", "ㄱ")
        val results = corpusWords(resolved.stroke, resolved.context)
        assertEquals(listOf("그", "그리고"), results.map { it.text })
    }

    @Test
    fun realAssetOffersSentenceStartCandidatesForChoseongStroke() {
        val results = predictor().predict("ㄱ", "", "com.example.chat", limit = 10)
        val corpusWords = results.filter { it.source == "corpus_ngram" }.map { it.text }
        assertTrue(results.toString(), "그" in corpusWords)
        assertTrue(corpusWords.toString(), corpusWords.size <= 3)
        val noStroke = predictor().predict("", "", "com.example.chat", limit = 10)
        assertTrue(noStroke.none { it.source == "corpus_ngram" })
    }
}
