/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Tokenizes committed Korean sentences into learnable words for [PersonalNgramModel],
 * stripping punctuation, PII placeholders, and noise tokens that should never be learned.
 */
object PersonalNgramTokenizer {

    private val WHITESPACE = Regex("\\s+")
    private val DIGIT_RUN = Regex("\\d{4,}")
    private val PUNCTUATION = ".,!?~…\"'()[]{}<>:;。？！、".toSet()
    private val morphology = ChoseongMorphologyEngine()

    // Ordered longest-first so the first matching suffix is always the longest applicable one.
    private val PARTICLES = listOf(
        "에서는", "으로는", "에게는", "한테는",
        "이랑", "하고", "에서", "으로", "에게", "한테", "부터", "까지", "처럼", "보다", "마다",
        "은", "는", "이", "가", "을", "를", "도", "의", "에", "로", "와", "과", "랑", "께", "만", "요"
    )

    fun tokenize(sentence: String): List<String> {
        if (sentence.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        for (raw in sentence.trim().split(WHITESPACE)) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("[") && raw.endsWith("]")) continue
            val trimmed = trimPunctuation(raw)
            if (isDroppable(trimmed)) continue
            result.add(trimmed)
        }
        return result
    }

    fun stem(word: String): String? {
        for (particle in PARTICLES) {
            if (word.endsWith(particle)) {
                val remainder = word.length - particle.length
                return if (remainder >= 1) word.substring(0, remainder) else null
            }
        }
        return null
    }

    private fun trimPunctuation(token: String): String {
        var start = 0
        var end = token.length
        while (start < end && token[start] in PUNCTUATION) start++
        while (end > start && token[end - 1] in PUNCTUATION) end--
        return token.substring(start, end)
    }

    private fun isDroppable(token: String): Boolean {
        if (token.isEmpty()) return true
        if (token.length > 20) return true
        if (DIGIT_RUN.containsMatchIn(token)) return true
        if (token.contains("@") && token.contains(".")) return true
        val lower = token.lowercase()
        if (lower.startsWith("http") || lower.startsWith("www.")) return true
        // Korean sentence continuation only learns Korean words. Any token carrying a Latin
        // letter is either a mode-confusion typo (the user meant Hangul but the keyboard was in
        // English, e.g. "ghldml"/"ckatjr") or a mixed-script fragment ("g회의"). Both are noise
        // that must never be learned or surfaced as a suggestion, so they are dropped here.
        if (token.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
        if (token.none { isRelevantChar(it) }) return true
        return false
    }

    private fun isRelevantChar(c: Char): Boolean {
        if (morphology.isHangulSyllable(c)) return true
        if (c in 'a'..'z' || c in 'A'..'Z') return true
        if (c.isDigit()) return true
        return c == 'ㅋ' || c == 'ㅎ' || c == 'ㅠ' || c == 'ㅜ'
    }
}
