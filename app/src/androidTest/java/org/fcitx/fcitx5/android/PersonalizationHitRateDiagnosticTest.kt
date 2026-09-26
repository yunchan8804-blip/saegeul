/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.PersonalSentenceCompletionGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Read-only diagnostic: measures how much of the user's own stored data actually survives the
 * suggestion gates at input time. Emits aggregate counts only; no stored sentence text is
 * reported, only lengths and pass/fail tallies.
 */
class PersonalizationHitRateDiagnosticTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun measurePersonalizationHitRate() {
        val app = FcitxApplication.getInstance()
        val vault = app.personalSentenceVault
        val metrics = app.predictionMetricsStore.summary()

        val stats = vault.stats()
        val stored = vault.exportForEnrichment(4000)

        var prefixTrials = 0
        var retrievalHits = 0
        var gatePasses = 0
        var midPhraseTrials = 0
        var midPhrasePasses = 0

        val sample = stored.take(400)
        for (sentence in sample) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            if (tokens.size < 2) continue
            for (cut in 1 until tokens.size) {
                val typed = tokens.subList(0, cut).joinToString(" ")
                prefixTrials++
                val retrieved = vault.retrieve(typed, "com.kakao.talk", 5)
                if (retrieved.any { it.sentence == sentence }) retrievalHits++
                if (retrieved.any {
                        it.sentence == sentence &&
                            PersonalSentenceCompletionGate.isContinuation(typed, it.sentence)
                    }
                ) gatePasses++
            }
            if (tokens.size >= 3) {
                val typed = tokens.subList(1, minOf(3, tokens.size)).joinToString(" ")
                midPhraseTrials++
                val retrieved = vault.retrieve(typed, "com.kakao.talk", 5)
                if (retrieved.any {
                        it.sentence == sentence &&
                            PersonalSentenceCompletionGate.isContinuation(typed, it.sentence)
                    }
                ) midPhrasePasses++
            }
        }

        val lengths = sample.map { it.length }.sorted()
        val result = JSONObject().apply {
            put("vaultSentences", stats.sentences)
            put("vaultUniqueTerms", stats.uniqueTerms)
            put("sampledSentences", sample.size)
            put("medianSentenceLength", if (lengths.isEmpty()) 0 else lengths[lengths.size / 2])
            put("prefixTrials", prefixTrials)
            put("retrievalHits", retrievalHits)
            put("gatePasses", gatePasses)
            put("midPhraseTrials", midPhraseTrials)
            put("midPhrasePasses", midPhrasePasses)
            put("retrievalRate", if (prefixTrials == 0) 0.0 else retrievalHits.toDouble() / prefixTrials)
            put("gatePassRate", if (prefixTrials == 0) 0.0 else gatePasses.toDouble() / prefixTrials)
            put("gateSurvivalOfRetrieved", if (retrievalHits == 0) 0.0 else gatePasses.toDouble() / retrievalHits)
            put("metricsTotalShown", metrics.totalShown)
            put("metricsTotalAccepted", metrics.totalAccepted)
            put("metricsAcceptRate", metrics.acceptRate.toDouble())
            put("metricsPersonalShare", metrics.personalShare.toDouble())
            put("metricsLearnedSentences", metrics.learnedSentences)
            put("metricsLearnedWords", metrics.learnedWords)
            put("metricsActiveDays", metrics.activeDays)
            put("metricsFirstDay", metrics.firstDay ?: "")
            put("metricsLastDay", metrics.lastDay ?: "")
            val recent = JSONArray()
            metrics.recent.takeLast(14).forEach { d ->
                recent.put(
                    JSONObject().apply {
                        put("day", d.day)
                        put("shown", d.shown)
                        put("accepted", d.accepted)
                        put("acceptedPersonal", d.acceptedPersonal)
                        put("learnedSentences", d.learnedSentences)
                    }
                )
            }
            put("recentDays", recent)
        }

        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply { putString("personalizationDiagnostic", result.toString()) }
        )
    }
}
