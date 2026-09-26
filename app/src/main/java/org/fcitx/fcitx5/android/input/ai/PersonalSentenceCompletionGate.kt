/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Allows personal sentence retrieval only when it extends the sentence currently being written.
 */
object PersonalSentenceCompletionGate {

    private val ALLOWED_SUFFIX_CHARS = "요죠네다까며서고도만의는이가을를에로와과자게지어아?!.~…".toSet()
    private val VERBAL_PREFIXES = listOf("하", "했", "합", "할", "해", "되", "됐", "됩", "될", "돼", "시", "셨")

    fun isContinuation(fullContext: String, candidate: String): Boolean {
        val currentSentence = normalize(lastSentence(fullContext))
        val normalizedCandidate = normalize(candidate)
        if (currentSentence.isEmpty() || normalizedCandidate.length <= currentSentence.length) return false
        if (!normalizedCandidate.startsWith(currentSentence)) return false

        val nextChar = normalizedCandidate[currentSentence.length]
        if (nextChar == ' ' || nextChar == '\n') return true

        val nextWordFragment = normalizedCandidate.substring(currentSentence.length).substringBefore(' ')
        if (nextWordFragment.isEmpty()) return false
        if (nextWordFragment.all { it in ALLOWED_SUFFIX_CHARS }) return true
        return VERBAL_PREFIXES.any { nextWordFragment.startsWith(it) }
    }

    private fun lastSentence(text: String): String {
        val boundary = text.lastIndexOfAny(charArrayOf('.', '?', '!', '\n', '~', '。', '！', '？'))
        return text.substring(boundary + 1).trim()
    }

    private fun normalize(text: String): String = text.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
