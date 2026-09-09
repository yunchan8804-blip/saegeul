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

    private val observedEndings = listOf(
        "부탁드립니다", "드리겠습니다", "겠습니다", "할까요", "할까", "인가요", "거든요",
        "했어용", "드립니다", "했습니다",
        "합니다", "입니다", "됩니다", "하세요", "이네요", "네요", "세요",
        "어요", "아요", "해요", "습니다", "했어", "했지", "해봐", "먹자",
        "보자", "ㅋㅋ", "ㅎㅎ", "네용", "구요", "거든", "죠"
    ).sortedByDescending { it.length }

    fun sentenceFragments(text: String): List<String> = text
        .split(sentenceBoundary)
        .map(::normalizeFragment)
        .filter { it.isNotBlank() }

    fun endingOf(fragment: String): String? {
        val normalized = normalizeFragment(fragment)
        return observedEndings.firstOrNull { normalized.endsWith(it) }
    }

    fun topEndings(sentences: Iterable<String>, limit: Int = 5): List<String> {
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
