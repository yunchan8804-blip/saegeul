/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.verifier

import org.fcitx.fcitx5.android.input.ai.grammar.GrammarConstrainedEngine
import kotlin.math.exp
import kotlin.math.ln

/**
 * EAI-11: Lightweight N-gram Perplexity (PPL) Scorer.
 *
 * Estimates perplexity for sentence continuations:
 * - Natural phrases (e.g. "회의에 참석합니다", "자료를 준비했습니다") -> Low PPL (< 50.0)
 * - Awkward / ungrammatical collocations (e.g. "마음을 감사해요", "회의를 참석해요") -> High PPL (> 200.0)
 */
class KenLmScorer {

    companion object {
        // Log-probability thresholds
        private const val BASE_LOG_PROB = -2.6 // Standard transition log prob (~0.074, PPL ~ 13.5)
        private const val NATURAL_LOG_PROB = -1.2 // Collocation boost (~0.30, PPL ~ 3.3)
        private const val PENALTY_LOG_PROB = -8.5 // Unnatural/erroneous transition (~0.0002, PPL ~ 4900)

        // Well-known natural collocations (pairs of adjacent words)
        private val NATURAL_COLLOCATIONS = setOf(
            "회의에" to "참석합니다",
            "회의에" to "참석해요",
            "회의에" to "참석",
            "미팅에" to "참석합니다",
            "모임에" to "참석합니다",
            "행사에" to "참석합니다",
            "자료를" to "준비했습니다",
            "자료를" to "준비했어요",
            "자료를" to "준비합니다",
            "자료를" to "공유합니다",
            "보고서를" to "작성했습니다",
            "내일" to "뵙겠습니다",
            "내일" to "봬요",
            "진심으로" to "감사드립니다",
            "정말" to "감사합니다",
            "깊이" to "감사드립니다",
            "좋은" to "하루",
            "하루" to "보내세요",
            "도움을" to "주셔서",
            "확인" to "부탁드립니다"
        )

        // Explicit ungrammatical / awkward collocations
        private val AWKWARD_COLLOCATIONS = setOf(
            "마음을" to "감사해요",
            "마음을" to "감사합니다",
            "마음을" to "고마워",
            "마음을" to "고맙습니다",
            "회의를" to "참석해요",
            "회의를" to "참석합니다",
            "모임을" to "참석해요",
            "행사를" to "참석해요",
            "인사를" to "감사해요",
            "축하를" to "기뻐요"
        )

        // Regex pattern detecting accusative particle + intransitive/adjective verb
        private val AWKWARD_ACCUSATIVE_INTRANSITIVE = Regex(
            """([가-힣]+[을를])\s*(감사|고맙|고마워|기쁘|기뻐|슬프|슬퍼|참석|도착)"""
        )
    }

    /**
     * Calculates the perplexity (PPL) of the given [sentence].
     * PPL = exp(-1/N * sum(ln P(w_i | w_{i-1})))
     */
    fun calculatePerplexity(sentence: String): Double {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return 1.0

        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return 1.0

        var totalLogProb = 0.0
        val n = words.size

        // Check if there's a strong pattern violation in the sentence string
        val hasAwkwardAccusative = AWKWARD_ACCUSATIVE_INTRANSITIVE.containsMatchIn(trimmed)

        for (i in 0 until n) {
            val currentWord = words[i]

            // Check intra-word coda compatibility (e.g. "사람를", "바다을")
            if (hasIntraWordCodaViolation(currentWord)) {
                totalLogProb += PENALTY_LOG_PROB
                continue
            }

            if (i == 0) {
                // Unigram probability
                totalLogProb += BASE_LOG_PROB
                continue
            }

            val prevWord = words[i - 1]
            val pair = prevWord to currentWord

            when {
                AWKWARD_COLLOCATIONS.contains(pair) -> {
                    totalLogProb += PENALTY_LOG_PROB
                }
                NATURAL_COLLOCATIONS.contains(pair) -> {
                    totalLogProb += NATURAL_LOG_PROB
                }
                hasAwkwardAccusative && (prevWord.endsWith("을") || prevWord.endsWith("를")) -> {
                    totalLogProb += PENALTY_LOG_PROB
                }
                else -> {
                    totalLogProb += BASE_LOG_PROB
                }
            }
        }

        val avgNegLogProb = -totalLogProb / n
        return exp(avgNegLogProb)
    }

    /**
     * Checks if a single word has an obvious Korean coda-particle mismatch,
     * e.g., "사람를", "바다을".
     */
    private fun hasIntraWordCodaViolation(word: String): Boolean {
        if (word.length < 2) return false

        // Check last syllable as particle attached to second to last
        for (particleLen in 1..2) {
            if (word.length > particleLen) {
                val stemChar = word[word.length - particleLen - 1]
                val particle = word.substring(word.length - particleLen)
                if (!GrammarConstrainedEngine.Companion.hasCoda(stemChar)) {
                    // No coda: should not have "은", "이", "을", "과", "으로"
                    if (particle in setOf("은", "이", "을", "과", "으로")) return true
                } else {
                    val coda = GrammarConstrainedEngine.getCoda(stemChar)
                    if (coda == 8) { // 'ㄹ' coda
                        if (particle in setOf("는", "가", "를", "와", "으로")) return true
                    } else { // other codas
                        if (particle in setOf("는", "가", "를", "와", "로")) return true
                    }
                }
            }
        }
        return false
    }
}
