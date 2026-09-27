/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Typing DNA Profiler:
 * Analyzes accumulated user sentences and extracts the user's distinct linguistic DNA
 * (dominant tone, habitual sentence endings, high-frequency collocations, and situational phrases).
 *
 * 100% on-device statistical analysis; requires zero network access.
 */
class TypingDnaProfiler {

    /**
     * Profiles the given sentences using 100% on-device statistical analysis.
     */
    fun profile(category: String, sentences: List<String>): PersonaDna {
        if (sentences.isEmpty()) {
            return PersonaDna(category = category)
        }
        return profileOnDevice(category, sentences)
    }

    /**
     * 100% On-Device statistical profiling engine.
     * Extracts linguistic patterns with zero network overhead.
     */
    fun profileOnDevice(category: String, sentences: List<String>): PersonaDna {
        var honorificScore = 0
        var informalScore = 0

        val endingCounts = mutableMapOf<String, Int>()
        val bigramCounts = mutableMapOf<Pair<String, String>, Int>()
        val phraseCounts = mutableMapOf<String, Int>()

        for (s in sentences) {
            val trimmed = s.trim()
            if (trimmed.isBlank()) continue

            phraseCounts[trimmed] = (phraseCounts[trimmed] ?: 0) + 1

            KoreanSentenceEndingExtractor.sentenceFragments(trimmed).forEach { fragment ->
                val ending = KoreanSentenceEndingExtractor.endingOf(fragment)
                if (ending in HONORIFIC_ENDINGS || fragment.endsWith("요") || fragment.endsWith("시오")) {
                    honorificScore += 2
                } else if (ending in INFORMAL_ENDINGS ||
                    fragment.endsWith("야") || fragment.endsWith("어") || fragment.endsWith("지") ||
                    fragment.endsWith("자") || fragment.endsWith("해")
                ) {
                    informalScore += 2
                }
                if (ending != null) {
                    endingCounts[ending] = (endingCounts[ending] ?: 0) + 1
                }
            }

            // Bigram extraction
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            for (i in 0 until words.size - 1) {
                val pair = Pair(words[i], words[i + 1])
                bigramCounts[pair] = (bigramCounts[pair] ?: 0) + 1
            }
        }

        val dominantTone = if (informalScore > honorificScore || category == TypingDnaVault.CATEGORY_MESSENGER && informalScore >= honorificScore) {
            "Informal"
        } else {
            "Honorific"
        }

        val topEndings = endingCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        val topBigrams = bigramCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (pair, count) ->
                val weight = (count.toFloat() / sentences.size.coerceAtLeast(1)).coerceIn(0.5f, 1.0f)
                DynamicBigram(pair.first, pair.second, weight)
            }

        val canned = phraseCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        return PersonaDna(
            category = category,
            dominantTone = dominantTone,
            habitualEndings = topEndings,
            frequentBigrams = topBigrams,
            cannedPhrases = canned
        )
    }

}

private val HONORIFIC_ENDINGS = setOf(
    "부탁드립니다", "드리겠습니다", "겠습니다", "할까요", "인가요", "거든요",
    "드립니다", "했습니다", "합니다", "입니다", "됩니다", "하세요", "이네요",
    "네요", "세요", "어요", "아요", "해요", "구요", "네용", "했어용", "습니다", "죠"
)

private val INFORMAL_ENDINGS = setOf("했어", "했지", "해봐", "먹자", "보자", "할까", "거든", "ㅋㅋ", "ㅎㅎ")
