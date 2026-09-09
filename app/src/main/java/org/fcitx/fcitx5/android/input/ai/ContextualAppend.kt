/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

data class ContextualAppend(
    val expectedContext: String,
    val suffix: String,
    val joinMode: JoinMode = JoinMode.NEXT_WORD
) {
    enum class JoinMode {
        NEXT_WORD,
        ATTACH
    }

    fun insertionFor(actualBeforeCursor: String): String? {
        if (expectedContext.length > MAX_EXPECTED_CONTEXT_LENGTH || suffix.any(::isInvalidSuffixCharacter)) return null
        val normalizedExpected = normalize(expectedContext)
        val normalizedActual = normalize(actualBeforeCursor)
        val normalizedSuffix = suffix.trim()
        if (normalizedExpected.isBlank()) return null
        if (normalizedSuffix.isBlank()) return null
        if (!normalizedActual.endsWith(normalizedExpected)) return null
        return when (joinMode) {
            JoinMode.NEXT_WORD -> if (actualBeforeCursor.lastOrNull()?.isWhitespace() == true) {
                "$normalizedSuffix "
            } else {
                " $normalizedSuffix "
            }
            JoinMode.ATTACH -> {
                if (actualBeforeCursor.lastOrNull()?.isWhitespace() == true) null else "$normalizedSuffix "
            }
        }
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun isInvalidSuffixCharacter(character: Char): Boolean =
        Character.isISOControl(character) || character == '\uFFFD' || character == '\u200B' || character == '\uFEFF'

    private companion object {
        const val MAX_EXPECTED_CONTEXT_LENGTH = 1024
    }
}
