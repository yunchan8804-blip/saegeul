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
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Read-only diagnostic: replays the user's own stored sentences through the real
 * AiContextualPredictor and measures which sources actually win the visible candidate slots
 * (4 word slots, 2 sentence slots), plus per-call latency.
 *
 * Aggregate counts only. No stored sentence text is emitted.
 */
class PersonalizationSlotShareDiagnosticTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val WORD_SLOTS = 4
        const val SENTENCE_SLOTS = 2
        const val SAMPLE_SENTENCES = 120
        const val PACKAGE_NAME = "com.kakao.talk"
    }

    @Test
    fun measureSlotShare() {
        val app = FcitxApplication.getInstance()
        val vault = app.personalSentenceVault
        val ngram = app.personalNgramModel

        val morphology = ChoseongMorphologyEngine()
        val personalizedStore = PersonalizedSentenceStore(
            storageFile = File(app.filesDir, "personalized_sentences.json"),
            morphology = morphology,
            cipher = app.vaultCipher
        ).apply { load() }

        app.baseKoreanVocabulary.load()
        val typoCorrector = KeyboardAwareTypoCorrector(
            substitutionCost = app.correctionPatternStore::personalizedSubstitutionCost
        )
        app.baseKoreanVocabulary.forEachWord(org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        ngram.forEachUnigram { word, count ->
            typoCorrector.addWord(word, PersonalNgramModel.personalPrior(count))
        }

        app.sentencePacks.prepare()

        val predictor = AiContextualPredictor(
            morphology = morphology,
            personalizedStore = personalizedStore,
            ngram = ngram,
            typoCorrector = typoCorrector,
            baseVocabulary = app.baseKoreanVocabulary,
            correctionStore = app.correctionPatternStore,
            personalSentenceVault = vault,
            personalGraphStore = app.personalGraphStore,
            sentencePackLookup = app.sentencePacks::complete
        )

        val personalSources = setOf(
            "personal_ngram", "typo_personal", "personalized_style", "rag_personal"
        )

        val wordSlotBySource = mutableMapOf<String, Int>()
        val sentenceSlotBySource = mutableMapOf<String, Int>()
        val topWordBySource = mutableMapOf<String, Int>()
        var trials = 0
        var trialsWithAnyPersonalWordSlot = 0
        var trialsWithAnyPersonalSentenceSlot = 0
        var trialsWithCorrectNextWordAnySlot = 0
        var trialsWithCorrectNextWordTopSlot = 0
        var trialsWithEmptySentenceLine = 0
        var latencyTotalNanos = 0L
        var latencyMaxNanos = 0L
        val latencies = mutableListOf<Long>()

        val stored = vault.exportForEnrichment(4000)
        val sample = stored.take(SAMPLE_SENTENCES)

        for (sentence in sample) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            if (tokens.size < 3) continue
            for (cut in 1 until minOf(tokens.size, 6)) {
                val typedWords = tokens.subList(0, cut)
                val expectedNextWord = tokens[cut]
                val context = typedWords.joinToString(" ") + " "

                val startedAt = System.nanoTime()
                val predictions = predictor.predict(
                    currentStroke = "",
                    contextBeforeCursor = context,
                    packageName = PACKAGE_NAME,
                    limit = 10
                )
                val elapsed = System.nanoTime() - startedAt
                latencyTotalNanos += elapsed
                latencies.add(elapsed)
                if (elapsed > latencyMaxNanos) latencyMaxNanos = elapsed

                trials++

                val words = predictions.filter { !it.isSentenceCompletion }.take(WORD_SLOTS)
                val sentences = predictions.filter { it.isSentenceCompletion }.take(SENTENCE_SLOTS)

                words.forEach { wordSlotBySource.increment(it.source) }
                sentences.forEach { sentenceSlotBySource.increment(it.source) }
                words.firstOrNull()?.let { topWordBySource.increment(it.source) }

                if (words.any { it.source in personalSources }) trialsWithAnyPersonalWordSlot++
                if (sentences.any { it.source in personalSources }) trialsWithAnyPersonalSentenceSlot++
                if (sentences.isEmpty()) trialsWithEmptySentenceLine++

                val matchesExpected = { text: String ->
                    val firstNew = text.removePrefix(context).trim().substringBefore(' ')
                    text.trim() == expectedNextWord || firstNew == expectedNextWord
                }
                if (words.any { matchesExpected(it.text) }) trialsWithCorrectNextWordAnySlot++
                if (words.firstOrNull()?.let { matchesExpected(it.text) } == true) {
                    trialsWithCorrectNextWordTopSlot++
                }
            }
        }

        latencies.sort()
        val p50 = if (latencies.isEmpty()) 0L else latencies[latencies.size / 2] / 1_000_000
        val p95 = if (latencies.isEmpty()) 0L else latencies[(latencies.size * 95) / 100] / 1_000_000

        val result = JSONObject().apply {
            put("trials", trials)
            put("sampledSentences", sample.size)
            put("wordSlotBySource", wordSlotBySource.toSortedJson())
            put("sentenceSlotBySource", sentenceSlotBySource.toSortedJson())
            put("topWordSlotBySource", topWordBySource.toSortedJson())
            put("trialsWithAnyPersonalWordSlot", trialsWithAnyPersonalWordSlot)
            put("trialsWithAnyPersonalSentenceSlot", trialsWithAnyPersonalSentenceSlot)
            put("trialsWithEmptySentenceLine", trialsWithEmptySentenceLine)
            put("trialsWithCorrectNextWordAnySlot", trialsWithCorrectNextWordAnySlot)
            put("trialsWithCorrectNextWordTopSlot", trialsWithCorrectNextWordTopSlot)
            put("nextWordHitRateAnySlot", if (trials == 0) 0.0 else trialsWithCorrectNextWordAnySlot.toDouble() / trials)
            put("nextWordHitRateTopSlot", if (trials == 0) 0.0 else trialsWithCorrectNextWordTopSlot.toDouble() / trials)
            put("latencyMeanMs", if (trials == 0) 0L else (latencyTotalNanos / trials) / 1_000_000)
            put("latencyP50Ms", p50)
            put("latencyP95Ms", p95)
            put("latencyMaxMs", latencyMaxNanos / 1_000_000)
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("slotShareDiagnostic", result.toString()) }
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
