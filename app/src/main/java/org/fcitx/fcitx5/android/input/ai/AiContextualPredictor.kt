/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

data class AiPrediction(
    val text: String,
    val confidenceScore: Float,
    val isSentenceCompletion: Boolean = false,
    val source: String = "local_ai"
)

/**
 * Realtime AI Stroke-Level Next Word & Sentence Prediction Engine for Saegeul Keyboard.
 * Combines Jaso decomposition, Choseong matching, Personalized N-gram Markov context,
 * and built-in Korean conversational templates for sub-5ms instant suggestions.
 */
class AiContextualPredictor(
    private val lexicon: PersonalizedLexiconModel,
    private val morphology: ChoseongMorphologyEngine
) {

    private val baseKoreanLexicon = listOf(
        "안녕하세요", "안녕하세요!", "좋은 하루 보내세요", "감사합니다", "고맙습니다",
        "확인했습니다", "부탁드립니다", "지금 이동 중입니다", "잠시 후 연락드릴게요",
        "회의", "회의 참석 부탁드립니다", "회의 끝나고 밥 먹자", "배포 완료했습니다",
        "수고하셨습니다", "축하드립니다", "네 알겠습니다", "죄송합니다", "도착했습니다"
    )

    fun predict(
        currentStroke: String,
        contextBeforeCursor: String,
        packageName: String,
        limit: Int = 5
    ): List<AiPrediction> {
        val results = mutableListOf<AiPrediction>()
        val seen = mutableSetOf<String>()

        val cleanStroke = currentStroke.trim()
        val cleanContext = contextBeforeCursor.trim()

        // 1. Personalized N-gram Context Predictions
        if (cleanContext.isNotBlank()) {
            val lastWord = cleanContext.split(Regex("\\s+")).lastOrNull() ?: cleanContext
            val transitions = lexicon.getTransitions(lastWord, packageName)

            transitions.forEach { candidate ->
                if (cleanStroke.isBlank() || candidate.word.startsWith(cleanStroke) || morphology.matchesChoseong(candidate.word, cleanStroke)) {
                    if (seen.add(candidate.word)) {
                        val score = (0.90f + (candidate.frequency * 0.02f)).coerceAtMost(0.99f)
                        results.add(AiPrediction(candidate.word, score, isSentenceCompletion = candidate.word.contains(" ")))
                    }
                }
            }
        }

        // 2. Choseong & Prefix Realtime Matching from Base Lexicon
        if (cleanStroke.isNotBlank()) {
            baseKoreanLexicon.forEach { template ->
                val isPrefixMatch = template.startsWith(cleanStroke)
                val isChoseongMatch = morphology.matchesChoseong(template, cleanStroke)

                if (isPrefixMatch || isChoseongMatch) {
                    if (seen.add(template)) {
                        val score = if (isPrefixMatch) 0.80f else 0.70f
                        results.add(AiPrediction(template, score, isSentenceCompletion = template.length > 5))
                    }
                }
            }
        }

        // 3. Fallback frequent vocabulary suggestions if empty
        if (results.isEmpty()) {
            baseKoreanLexicon.take(limit).forEach { defaultWord ->
                if (seen.add(defaultWord)) {
                    results.add(AiPrediction(defaultWord, 0.50f, isSentenceCompletion = defaultWord.length > 5))
                }
            }
        }

        return results.sortedByDescending { it.confidenceScore }.take(limit)
    }

    fun learnSentence(sentence: String, packageName: String) {
        val words = sentence.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) {
            if (words.isNotEmpty()) {
                lexicon.recordTransition("", words[0], packageName)
            }
            return
        }

        for (i in 0 until words.size - 1) {
            lexicon.recordTransition(words[i], words[i + 1], packageName)
        }
    }
}
