/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

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
}
