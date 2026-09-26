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
 * Collects the actual NON-personal sentence-line candidates offered on held-out contexts, so the
 * generic filler content that occupies the sentence line can be inspected directly.
 *
 * Only public/bundled content (sentence_pack, ondevice_generated, collocation, base_lexicon,
 * discourse) is emitted. The user's own sentences and the typed context are never emitted.
 */
class SentenceLineFillerSampleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val SENTENCE_SLOTS = 2
        const val PACKAGE_NAME = "com.kakao.talk"
        const val MAX_SAMPLES = 40
    }

    private val publicSources = setOf(
        "sentence_pack", "ondevice_generated", "ondevice_generated_spacing",
        "collocation_next_word", "base_lexicon", "discourse_continuation", "choseong_abbrev"
    )

    @Test
    fun sampleSentenceLineFiller() {
        val app = FcitxApplication.getInstance()
        val all = app.personalSentenceVault.exportForEnrichment(4000)

        val train = mutableListOf<String>()
        val heldOut = mutableListOf<String>()
        all.forEach { sentence ->
            if (Math.floorMod(sentence.hashCode(), 5) == 0) heldOut.add(sentence) else train.add(sentence)
        }

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

        val samples = JSONArray()
        val wordLineSamples = JSONArray()
        var collected = 0
        var wordCollected = 0

        outer@ for (sentence in heldOut) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            if (tokens.size < 3) continue
            for (cut in 1 until minOf(tokens.size, 5)) {
                val context = tokens.subList(0, cut).joinToString(" ") + " "
                val predictions = predictor.predict(
                    currentStroke = "",
                    contextBeforeCursor = context,
                    packageName = PACKAGE_NAME,
                    limit = 10
                )
                val sentenceLine = predictions.filter { it.isSentenceCompletion }.take(SENTENCE_SLOTS)
                sentenceLine.filter { it.source in publicSources }.forEach { pred ->
                    if (collected < MAX_SAMPLES) {
                        samples.put(JSONObject().apply {
                            put("source", pred.source)
                            put("contextWords", cut)
                            put("offeredSuffix", pred.append?.suffix ?: pred.text)
                        })
                        collected++
                    }
                }
                val wordLine = predictions.filter { !it.isSentenceCompletion }.take(4)
                wordLine.filter { it.source == "collocation_next_word" || it.source == "base_lexicon" }
                    .forEach { pred ->
                        if (wordCollected < MAX_SAMPLES) {
                            wordLineSamples.put(JSONObject().apply {
                                put("source", pred.source)
                                put("word", pred.text)
                                put("score", pred.confidenceScore.toDouble())
                            })
                            wordCollected++
                        }
                    }
                if (collected >= MAX_SAMPLES && wordCollected >= MAX_SAMPLES) break@outer
            }
        }

        val result = JSONObject().apply {
            put("generatedBankSentences", app.generatedSentenceBank.sentenceCount)
            put("sentencePackFillerSamples", samples)
            put("hardcodedWordLineSamples", wordLineSamples)
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("fillerSample", result.toString()) }
        )
    }
}
