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
 * Held-out evaluation across stroke conditions. The user rarely predicts a word from zero typed
 * characters alone; usually one or two syllables are already typed. This measures hit rate for
 * strokeChars = 0, 1 and 2 on the same held-out split, so the honest picture covers real usage.
 *
 * Aggregate counts only. No stored sentence text is emitted.
 */
class PersonalizationStrokeConditionDiagnosticTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val WORD_SLOTS = 4
        const val PACKAGE_NAME = "com.kakao.talk"
        const val MAX_EVAL_SENTENCES = 150
    }

    @Test
    fun measureAcrossStrokeConditions() {
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
        val conditions = JSONArray()
        val evalSet = heldOut.take(MAX_EVAL_SENTENCES)

        for (strokeChars in 0..2) {
            var trials = 0
            var anySlot = 0
            var topSlot = 0
            var personalSlotWin = 0
            var personalCorrect = 0
            var genericCorrect = 0
            val bySource = mutableMapOf<String, Int>()

            for (sentence in evalSet) {
                val tokens = PersonalNgramTokenizer.tokenize(sentence)
                if (tokens.size < 3) continue
                for (cut in 1 until minOf(tokens.size, 6)) {
                    val expected = tokens[cut]
                    if (expected.length <= strokeChars) continue
                    val baseContext = tokens.subList(0, cut).joinToString(" ") + " "
                    val stroke = if (strokeChars == 0) "" else expected.take(strokeChars)
                    val context = if (strokeChars == 0) baseContext else baseContext + stroke

                    val predictions = predictor.predict(
                        currentStroke = stroke,
                        contextBeforeCursor = context,
                        packageName = PACKAGE_NAME,
                        limit = 10
                    )
                    trials++

                    val words = predictions.filter { !it.isSentenceCompletion }.take(WORD_SLOTS)
                    words.forEach { bySource.increment(it.source) }
                    if (words.any { it.source in personalSources }) personalSlotWin++

                    val matches = { p: org.fcitx.fcitx5.android.input.ai.AiPrediction ->
                        val firstNew = p.text.removePrefix(baseContext).trim().substringBefore(' ')
                        p.text.trim() == expected || firstNew == expected
                    }
                    val hit = words.firstOrNull(matches)
                    if (hit != null) {
                        anySlot++
                        if (hit.source in personalSources) personalCorrect++ else genericCorrect++
                    }
                    if (words.firstOrNull()?.let { matches(it) } == true) topSlot++
                }
            }

            conditions.put(JSONObject().apply {
                put("strokeChars", strokeChars)
                put("trials", trials)
                put("hitRateAnySlot", if (trials == 0) 0.0 else anySlot.toDouble() / trials)
                put("hitRateTopSlot", if (trials == 0) 0.0 else topSlot.toDouble() / trials)
                put("personalSlotPresentRate", if (trials == 0) 0.0 else personalSlotWin.toDouble() / trials)
                put("correctFromPersonal", personalCorrect)
                put("correctFromGeneric", genericCorrect)
                put("wordSlotBySource", bySource.toSortedJson())
            })
        }

        val result = JSONObject().apply {
            put("trainSentences", train.size)
            put("heldOutSentences", heldOut.size)
            put("conditions", conditions)
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("strokeConditionDiagnostic", result.toString()) }
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
