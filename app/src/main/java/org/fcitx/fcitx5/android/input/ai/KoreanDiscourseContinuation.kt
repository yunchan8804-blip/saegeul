/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

internal object KoreanDiscourseContinuation {
    private val connectiveSuffixes = listOf("는데", "은데")
    private val connectiveWords = listOf("아직", "생각보다", "그래도")
    private val contrastWords = listOf("그래도", "아직")
    private val nextSentenceWords = listOf("그리고", "그런데", "이제")

    fun suggest(rawContext: String): List<String> {
        if (!isSafeContext(rawContext)) return emptyList()
        connectiveSuggestion(rawContext)?.let { return it }
        return nextSentenceSuggestion(rawContext)
    }

    private fun connectiveSuggestion(rawContext: String): List<String>? {
        if (rawContext.lastOrNull() != ' ') return null
        val lastWord = rawContext.trimEnd(' ').split(Regex("\\s+")).lastOrNull().orEmpty()
        if (lastWord.any { it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }) return null
        return when {
            lastWord.endsWith("지만") -> contrastWords
            connectiveSuffixes.any(lastWord::endsWith) -> connectiveWords
            else -> null
        }
    }

    private fun nextSentenceSuggestion(rawContext: String): List<String> {
        val terminalContext = rawContext.trimEnd(' ')
        if (terminalContext.lastOrNull() !in setOf('.', '!', '?')) return emptyList()
        if (terminalContext.dropLast(1).lastOrNull()?.isDigit() == true) return emptyList()
        val lastSentence = terminalContext.dropLast(1).split(Regex("[.!?\\n\\r]")).lastOrNull().orEmpty()
        if (lastSentence.any { it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }) return emptyList()
        return if (lastSentence.any { it in '가'..'힣' }) nextSentenceWords else emptyList()
    }

    private fun isSafeContext(rawContext: String): Boolean {
        if (rawContext.isBlank()) return false
        val trimmed = rawContext.trimEnd(' ')
        if (trimmed.endsWith('\n') || trimmed.endsWith('\r')) return false
        return rawContext.none {
            Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t'
        }
    }
}
