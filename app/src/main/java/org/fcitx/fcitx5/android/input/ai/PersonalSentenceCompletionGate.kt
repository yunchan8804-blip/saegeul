/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Allows personal sentence retrieval only when it extends the sentence currently being written.
 */
object PersonalSentenceCompletionGate {

    fun isContinuation(fullContext: String, candidate: String): Boolean {
        val currentSentence = normalize(lastSentence(fullContext))
        val normalizedCandidate = normalize(candidate)
        return currentSentence.isNotEmpty() &&
            normalizedCandidate.length > currentSentence.length &&
            normalizedCandidate.startsWith(currentSentence)
    }

    private fun lastSentence(text: String): String {
        val boundary = text.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'))
        return text.substring(boundary + 1).trim()
    }

    private fun normalize(text: String): String = text.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
