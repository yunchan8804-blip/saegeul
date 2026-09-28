/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/** Extracts observed Korean sentence-final expressions without claiming morphological analysis. */
object KoreanSentenceEndingExtractor {

    private val sentenceBoundary = Regex("[.!?\\n\\r。！？]+")
    private val trailingPunctuation = setOf('.', ',', '!', '?', '…', '。', '！', '？', ':', ';', '·')
    private val closingMarks = setOf('"', '\'', '”', '’', ')', ']', '}', '〉', '》', '」', '』', '】')

    fun sentenceFragments(text: String): List<String> = text
        .split(sentenceBoundary)
        .map(::normalizeFragment)
        .filter { it.isNotBlank() }

    fun endingOf(fragment: String): String? {
        val normalized = normalizeFragment(fragment)
        if (normalized.isBlank()) return null
        return org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer.extractEnding(normalized)
    }

    fun topEndings(sentences: Iterable<String>, limit: Int = 5): List<String> {
        if (limit <= 0) return emptyList()
        val counts = mutableMapOf<String, Int>()
        sentences.forEach { sentence ->
            sentenceFragments(sentence).forEach { fragment ->
                endingOf(fragment)?.let { ending ->
                    counts[ending] = (counts[ending] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun normalizeFragment(fragment: String): String {
        var end = fragment.length
        while (end > 0) {
            val char = fragment[end - 1]
            if (!char.isWhitespace() && char !in trailingPunctuation && char !in closingMarks) break
            end -= 1
        }
        return fragment.substring(0, end).trim()
    }
}
