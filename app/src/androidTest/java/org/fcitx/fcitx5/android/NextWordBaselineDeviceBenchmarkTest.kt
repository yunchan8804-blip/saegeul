/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.BundledKoreanNgram
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.json.JSONObject
import org.junit.Test

/**
 * 기기 위 predict() 지연 측정. 품질 지표의 정본은 JVM의 NextWordBaselineBenchmarkTest이고, 여기서는
 * 같은 코드가 기기에서 내는 지연과 정합성 확인용 hit@1·hit@4만 status로 보낸다.
 * 앱의 실제 에셋(어휘, 문장팩, 내장 n-gram)을 쓰고 개인 저장소는 모두 빈 상태다.
 */
class NextWordBaselineDeviceBenchmarkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val PACKAGE_NAME = "com.kakao.talk"
        const val WORD_SLOTS = 4
        const val WARMUP_CALLS = 200
        const val HELDOUT_ASSET = "benchmark/ko-heldout-fineweb2-test-300.txt"
    }

    @Test
    fun measureNextWordLatency() {
        val app = FcitxApplication.getInstance()
        val sentences = instrumentation.context.assets.open(HELDOUT_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().filterNot { it.startsWith("#") || it.isBlank() }
        }

        val corpusNgram = app.assets.open(BundledKoreanNgram.ASSET_PATH).use(BundledKoreanNgram::read)
        app.baseKoreanVocabulary.load()
        val typoCorrector = KeyboardAwareTypoCorrector()
        app.baseKoreanVocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior ->
            typoCorrector.addWord(word, prior)
        }
        app.sentencePacks.prepare()

        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            personalizedStore = PersonalizedSentenceStore(),
            ngram = PersonalNgramModel(storeFile = null),
            typoCorrector = typoCorrector,
            baseVocabulary = app.baseKoreanVocabulary,
            correctionStore = CorrectionPatternStore(),
            personalSentenceVault = PersonalSentenceVault(storeFile = null),
            sentencePackLookup = app.sentencePacks::complete,
            bundledNgram = { corpusNgram }
        )

        val latenciesNanos = ArrayList<Long>(10_000)
        var trials = 0
        var hit1 = 0
        var hit4 = 0
        for (sentence in sentences) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            for (i in 1 until tokens.size) {
                val context = tokens.subList(0, i).joinToString(" ") + " "
                val expected = tokens[i]
                val before = System.nanoTime()
                val predictions = predictor.predict(
                    currentStroke = "",
                    contextBeforeCursor = context,
                    packageName = PACKAGE_NAME,
                    limit = 10
                )
                latenciesNanos.add(System.nanoTime() - before)
                trials++

                val rank = predictions.filter { !it.isSentenceCompletion }
                    .indexOfFirst { matches(it.text, context, expected) }
                if (rank == 0) hit1++
                if (rank in 0 until WORD_SLOTS) hit4++
            }
        }

        val measured = latenciesNanos.drop(WARMUP_CALLS).sorted()
        val result = JSONObject().apply {
            put("sentences", sentences.size)
            put("trials", trials)
            put("warmupExcluded", minOf(WARMUP_CALLS, latenciesNanos.size))
            put("measuredCalls", measured.size)
            put("p50Ms", percentileMs(measured, 0.50))
            put("p95Ms", percentileMs(measured, 0.95))
            put("p99Ms", percentileMs(measured, 0.99))
            put("maxMs", measured.lastOrNull()?.let { it / 1e6 } ?: 0.0)
            put("hit1Rate", if (trials == 0) 0.0 else hit1.toDouble() / trials)
            put("hit4Rate", if (trials == 0) 0.0 else hit4.toDouble() / trials)
            put("device", android.os.Build.MODEL)
            put("sdk", android.os.Build.VERSION.SDK_INT)
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("nextWordDeviceBenchmark", result.toString()) }
        )
    }

    private fun percentileMs(sortedNanos: List<Long>, quantile: Double): Double {
        if (sortedNanos.isEmpty()) return 0.0
        val index = (Math.ceil(quantile * sortedNanos.size).toInt() - 1).coerceIn(0, sortedNanos.size - 1)
        return sortedNanos[index] / 1e6
    }

    private fun matches(text: String, context: String, expected: String): Boolean {
        val firstNew = text.removePrefix(context).trim().substringBefore(' ')
        return trimPunctuation(text.trim()) == expected || trimPunctuation(firstNew) == expected
    }

    private fun trimPunctuation(token: String) = token.trim { !it.isLetterOrDigit() }
}
