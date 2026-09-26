/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Honest held-out diagnostic. The previous slot-share test replayed sentences that were already
 * indexed in the live vault, which is a positive control and overstates real hit rate.
 *
 * Here the user's real sentences are split 80/20 by a stable hash. Fresh in-memory vault and
 * n-gram models are trained on the 80% train split only, then evaluated on the 20% held-out
 * split the models have never seen. Nothing is written to the user's real on-device stores.
 *
 * Aggregate counts only. No stored sentence text is emitted.
 */
class PersonalizationHeldOutDiagnosticTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val WORD_SLOTS = 4
        const val SENTENCE_SLOTS = 2
        const val PACKAGE_NAME = "com.kakao.talk"
        const val MAX_EVAL_SENTENCES = 150
    }

    @Test
    fun measureHeldOutHitRate() {
        val app = FcitxApplication.getInstance()
        val all = app.personalSentenceVault.exportForEnrichment(4000)

        val train = mutableListOf<String>()
        val heldOut = mutableListOf<String>()
        all.forEach { sentence ->
            // Stable, content-based split so the same sentence always lands in the same bucket.
            val bucket = Math.floorMod(sentence.hashCode(), 5)
            if (bucket == 0) heldOut.add(sentence) else train.add(sentence)
        }

        // Fresh in-memory models: storeFile = null means nothing touches the user's real vault.
        val vault = PersonalSentenceVault(storeFile = null)
        val ngram = PersonalNgramModel(storeFile = null)
        train.forEach { sentence ->
            vault.record(sentence, PACKAGE_NAME)
            ngram.learn(sentence, PACKAGE_NAME)
        }

        val morphology = ChoseongMorphologyEngine()
        app.baseKoreanVocabulary.load()
        val typoCorrector = KeyboardAwareTypoCorrector()
        app.baseKoreanVocabulary.forEachWord(org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        ngram.forEachUnigram { word, count ->
            typoCorrector.addWord(word, PersonalNgramModel.personalPrior(count))
        }
        app.sentencePacks.prepare()

        val predictor = AiContextualPredictor(
            morphology = morphology,
            personalizedStore = null,
            ngram = ngram,
            typoCorrector = typoCorrector,
            baseVocabulary = app.baseKoreanVocabulary,
            correctionStore = null,
            personalSentenceVault = vault,
            personalGraphStore = null,
            sentencePackLookup = app.sentencePacks::complete
        )

        val personalSources = setOf("personal_ngram", "typo_personal", "personalized_style", "rag_personal")

        val wordSlotBySource = mutableMapOf<String, Int>()
        val sentenceSlotBySource = mutableMapOf<String, Int>()
        var trials = 0
        var nextWordAnySlot = 0
        var nextWordTopSlot = 0
        var personalWordSlot = 0
        var emptySentenceLine = 0
        var sentenceLineUseful = 0

        val evalSet = heldOut.take(MAX_EVAL_SENTENCES)
        for (sentence in evalSet) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            if (tokens.size < 3) continue
            for (cut in 1 until minOf(tokens.size, 6)) {
                val context = tokens.subList(0, cut).joinToString(" ") + " "
                val expected = tokens[cut]

                val predictions = predictor.predict(
                    currentStroke = "",
                    contextBeforeCursor = context,
                    packageName = PACKAGE_NAME,
                    limit = 10
                )
                trials++

                val words = predictions.filter { !it.isSentenceCompletion }.take(WORD_SLOTS)
                val sentences = predictions.filter { it.isSentenceCompletion }.take(SENTENCE_SLOTS)
                words.forEach { wordSlotBySource.increment(it.source) }
                sentences.forEach { sentenceSlotBySource.increment(it.source) }
                if (words.any { it.source in personalSources }) personalWordSlot++
                if (sentences.isEmpty()) emptySentenceLine++

                val matches = { text: String ->
                    val firstNew = text.removePrefix(context).trim().substringBefore(' ')
                    text.trim() == expected || firstNew == expected
                }
                if (words.any { matches(it.text) }) nextWordAnySlot++
                if (words.firstOrNull()?.let { matches(it.text) } == true) nextWordTopSlot++
                // A sentence-line candidate is "useful" only if it actually continues toward the
                // real sentence the user went on to type.
                if (sentences.any { sentence.startsWith(it.text.trim()) || it.text.trim().startsWith(sentence.trim()) }) {
                    sentenceLineUseful++
                }
            }
        }

        val result = JSONObject().apply {
            put("vaultTotalSentences", all.size)
            put("trainSentences", train.size)
            put("heldOutSentences", heldOut.size)
            put("evaluatedSentences", evalSet.size)
            put("trials", trials)
            put("nextWordHitRateAnySlot", if (trials == 0) 0.0 else nextWordAnySlot.toDouble() / trials)
            put("nextWordHitRateTopSlot", if (trials == 0) 0.0 else nextWordTopSlot.toDouble() / trials)
            put("personalWordSlotRate", if (trials == 0) 0.0 else personalWordSlot.toDouble() / trials)
            put("emptySentenceLineRate", if (trials == 0) 0.0 else emptySentenceLine.toDouble() / trials)
            put("sentenceLineUsefulRate", if (trials == 0) 0.0 else sentenceLineUseful.toDouble() / trials)
            put("wordSlotBySource", wordSlotBySource.toSortedJson())
            put("sentenceSlotBySource", sentenceSlotBySource.toSortedJson())
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("heldOutDiagnostic", result.toString()) }
        )
    }

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun Map<String, Int>.toSortedJson(): JSONArray {
        val array = JSONArray()
        entries.sortedByDescending { it.value }.forEach { (source, count) ->
            array.put(JSONObject().apply {
                put("source", source)
                put("count", count)
            })
        }
        return array
    }
}
