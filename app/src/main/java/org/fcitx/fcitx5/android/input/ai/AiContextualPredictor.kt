/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.BaseLexiconSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.BaseVocabularySource
import org.fcitx.fcitx5.android.input.ai.prediction.source.CandidateRequest
import org.fcitx.fcitx5.android.input.ai.prediction.source.CandidateSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.ChoseongAbbreviationSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.CollocationSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.CorpusNgramSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.DeferredDiscourseSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.ImmediateContinuationSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.KeyboardTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PersonalNgramSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PersonalRagSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PersonalizedStyleSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.fcitx.fcitx5.android.input.ai.prediction.source.SentenceTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.WordTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackMatch
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector

data class AiPrediction(
    val text: String,
    val confidenceScore: Float,
    val isSentenceCompletion: Boolean = false,
    val source: String = "local_ai",
    val badge: String = "AI 완성",
    val replaceLength: Int = 0,
    val append: ContextualAppend? = null,
    val replacement: ContextualReplacement? = null
)

/**
 * Realtime AI Stroke-Level Next Word & Sentence Prediction Engine for Saegeul Keyboard.
 * Combines Jaso decomposition, Choseong matching, personalized N-gram context,
 * and cached continuation results.
 */
class AiContextualPredictor(
    morphology: ChoseongMorphologyEngine,
    val semanticPredictor: KoreanSemanticSentencePredictor = KoreanSemanticSentencePredictor(),
    val personalizedStore: PersonalizedSentenceStore? = null,
    val typoEngine: KoreanTypoCorrectionEngine = KoreanTypoCorrectionEngine(),
    val collocationModel: KoreanCollocationModel = KoreanCollocationModel(),
    private val ngram: PersonalNgramModel = PersonalNgramModel(),
    typoCorrector: KeyboardAwareTypoCorrector? = null,
    baseVocabulary: BaseKoreanVocabulary? = null,
    correctionStore: CorrectionPatternStore? = null,
    personalSentenceVault: PersonalSentenceVault? = null,
    private val personalGraphStore: PersonalGraphStore? = null,
    private val sentencePackLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
    bundledNgram: () -> BundledKoreanNgram? = { null }
) {

    companion object {
        // The only sources allowed to produce a full-sentence (isSentenceCompletion=true)
        // candidate: personalized_style (the user's own SOURCE_USER_PHRASE sentences),
        // rag_personal, llm_cached continuation results, and verified sentence-pack continuations.
        // Only hardcoded-content sources are blocked from the sentence line in rankCandidates().
        private val SENTENCE_LINE_SOURCE_BLOCKLIST = setOf("collocation_next_word", "base_lexicon")
    }

    private val keyboardTypoSource = KeyboardTypoCorrectionSource(typoCorrector, baseVocabulary, correctionStore, ngram)
    private val sentenceTypoSource = SentenceTypoCorrectionSource(typoEngine, baseVocabulary)
    private val wordTypoSource = WordTypoCorrectionSource(typoEngine, baseVocabulary)

    // Run after the typo corrections, in priority order: on a duplicate text the candidate from
    // the source listed first is kept.
    private val candidateSources: List<CandidateSource> = listOf(
        PersonalizedStyleSource(personalizedStore),
        PersonalRagSource(personalSentenceVault),
        ImmediateContinuationSource(),
        ChoseongAbbreviationSource(),
        CollocationSource(morphology, collocationModel),
        PersonalNgramSource(),
        CorpusNgramSource(morphology, bundledNgram),
        BaseVocabularySource(baseVocabulary, ngram),
        BaseLexiconSource(morphology),
        DeferredDiscourseSource()
    )

    /**
     * Collects raw candidates from every source, merges duplicates, drops what the quality gate
     * rejects, then ranks the word line and reranks the sentence line.
     */
    fun predict(
        currentStroke: String,
        contextBeforeCursor: String,
        packageName: String,
        limit: Int = 5,
        inputSessionEpoch: Long = 0L
    ): List<AiPrediction> {
        val input = PredictionInput(currentStroke, contextBeforeCursor)
        val collected = collectCandidates(input, packageName, limit, inputSessionEpoch)
        val merged = mergeCandidates(collected)
        val gated = applyQualityGate(merged, input)
        return rankCandidates(gated, input, packageName, limit)
    }

    private fun collectCandidates(
        input: PredictionInput,
        packageName: String,
        limit: Int,
        inputSessionEpoch: Long
    ): List<AiPrediction> {
        val keyboardTypo = keyboardTypoSource.correct(input, packageName)
        val sentenceTypo = sentenceTypoSource.correct(input, keyboardTypo.handledFragment)
        val wordTypo = wordTypoSource.collect(input, keyboardTypo.handledFragment)
        val request = buildRequest(input, packageName, limit, inputSessionEpoch, sentenceTypo.normalizedFullContext)
        return keyboardTypo.candidates + sentenceTypo.candidates + wordTypo +
            candidateSources.flatMap { it.collect(request) }
    }

    private fun buildRequest(
        input: PredictionInput,
        packageName: String,
        limit: Int,
        inputSessionEpoch: Long,
        normalizedFullContext: String
    ): CandidateRequest = CandidateRequest(
        input = input,
        packageName = packageName,
        limit = limit,
        normalizedFullContext = normalizedFullContext,
        isInformal = semanticPredictor.inferTone(normalizedFullContext) == KoreanTone.Informal,
        personalNgramCandidates = if (input.cleanStroke.isBlank()) {
            ngram.predictContextualNext(input.contextBeforeCursor, packageName, 4)
        } else {
            ngram.complete(input.cleanStroke, input.contextBeforeCursor, packageName, 4)
        },
        immediatePredictions = ImmediateContextualPredictions.collect(
            input = ImmediateContextualPredictions.Input(
                rawContext = input.rawFullContext,
                packageName = packageName,
                inputSessionEpoch = inputSessionEpoch,
                limit = limit
            ),
            sentencePackLookup = sentencePackLookup
        )
    )

    // Drops candidates the candidate bar cannot display and keeps the first candidate for each
    // (word line or sentence line, text) pair, so source order decides which duplicate survives.
    private fun mergeCandidates(collected: List<AiPrediction>): List<AiPrediction> {
        val seen = mutableSetOf<String>()
        return collected.filter { pred ->
            KoreanSuggestionSurface.isDisplayable(pred.text) &&
                seen.add(if (pred.isSentenceCompletion) "s:${pred.text}" else "w:${pred.text}")
        }
    }

    // 최종 품질 게이트: 소스를 가리지 않고 모든 후보(단어 줄·문장 줄)를 여기 한 곳에서 정본
    // SuggestionQualityGate로 거른다. 지금 입력 중인 스트로크를 그대로 반영한 후보와, 이미
    // 큐레이션된 초성 약어 확장(choseong_abbrev)만 예외로 둔다. 사용자 데이터(n-gram·금고·RAG)
    // 자체는 지우지 않고 화면에 보여줄 때만 거른다.
    private fun applyQualityGate(merged: List<AiPrediction>, input: PredictionInput): List<AiPrediction> =
        merged.filter { pred ->
            pred.source == "choseong_abbrev" ||
                (input.cleanStroke.isNotBlank() && pred.text.trim() == input.cleanStroke) ||
                SuggestionQualityGate.accepts(pred.text, input.rawFullContext, pred.isSentenceCompletion)
        }

    // Sentence-line whitelist: only sources backed by the user's own data, model output,
    // or a verified on-device sentence-pack continuation may appear as a full-sentence
    // candidate. This blocks hardcoded content templates (collocation_next_word,
    // base_lexicon, etc.) that occasionally produce a multi-word string long enough
    // to be marked isSentenceCompletion=true from leaking into the sentence line.
    // Word-line candidates are unaffected.
    private fun rankCandidates(
        gated: List<AiPrediction>,
        input: PredictionInput,
        packageName: String,
        limit: Int
    ): List<AiPrediction> {
        val words = gated.filter { !it.isSentenceCompletion }.sortedByDescending { it.confidenceScore }.take(limit)
        val sentenceCandidates = gated
            .filter { it.isSentenceCompletion && it.source !in SENTENCE_LINE_SOURCE_BLOCKLIST }
        val sentences = SentenceRelevanceReranker.rerank(
            sentenceCandidates, input.rawFullContext, ngram, packageName, limit, personalGraphStore
        )
        return (words + sentences).sortedByDescending { it.confidenceScore }
    }

    fun learnSentence(sentence: String, packageName: String) {
        ngram.learn(sentence, packageName)
    }
}
