/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

data class PrefetchedContinuation(
    val kind: Kind,
    val text: String
) {
    enum class Kind {
        WORD,
        CONTINUATION,
        CONTINUATION_ATTACH
    }

    fun toWireFormat(): String = "${kind.name}\t$text"

    companion object {
        fun parse(suggestions: List<String>, input: String): List<PrefetchedContinuation> {
            val normalizedInput = normalize(input)
            return suggestions.mapNotNull { suggestion ->
                parseSuggestion(suggestion, normalizedInput)
            }.distinctBy { it.kind to it.text }
        }

        private fun parseSuggestion(
            suggestion: String,
            normalizedInput: String
        ): PrefetchedContinuation? {
            val separator = suggestion.indexOf('\t')
            if (separator <= 0) return null
            val kind = when (suggestion.substring(0, separator)) {
                Kind.WORD.name -> Kind.WORD
                Kind.CONTINUATION.name -> Kind.CONTINUATION
                Kind.CONTINUATION_ATTACH.name -> Kind.CONTINUATION_ATTACH
                else -> return null
            }
            val rawPayload = suggestion.substring(separator + 1)
            if (rawPayload.any(::isInvalidPayloadCharacter)) return null
            val payload = rawPayload.trim()
            if (payload.isBlank()) return null
            if (kind == Kind.WORD && payload.any { it.isWhitespace() }) return null
            val normalizedPayload = normalize(payload)
            if (normalizedInput.isNotBlank() && normalizedPayload.startsWith(normalizedInput)) return null
            return PrefetchedContinuation(kind, payload)
        }

        private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

        private fun isInvalidPayloadCharacter(character: Char): Boolean =
            Character.isISOControl(character) || character == '\uFFFD' || character == '\u200B' || character == '\uFEFF'
    }
}
