/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber

class GeneratedSpacingIndex private constructor(
    private val targetsByUnspacedSentence: Map<String, String?>
) {
    fun suggestSpacing(sentence: String): String? {
        val source = sentence.trim(' ')
        if (!isAcceptedSentence(source)) return null
        val target = targetsByUnspacedSentence[source.withoutSpaces()] ?: return null
        return target.takeUnless { it == source }
    }

    companion object {
        fun build(entries: List<String>): GeneratedSpacingIndex {
            val targets = LinkedHashMap<String, String?>()
            entries.forEach { target ->
                if (!isAcceptedSentence(target) || target != target.trim(' ')) return@forEach
                val key = target.withoutSpaces()
                when (targets[key]) {
                    null -> if (!targets.containsKey(key)) targets[key] = target
                    target -> Unit
                    else -> targets[key] = null
                }
            }
            return GeneratedSpacingIndex(targets.toMap())
        }

        private fun isAcceptedSentence(sentence: String): Boolean =
            sentence.length in MIN_SENTENCE_LENGTH..MAX_SENTENCE_LENGTH &&
                sentence.lastOrNull() in TERMINALS &&
                sentence.any { it in '가'..'힣' } &&
                sentence.none(::isNonAsciiWhitespace) &&
                !KoreanPiiScrubber.containsPii(sentence)

        private fun isNonAsciiWhitespace(character: Char): Boolean =
            character != ' ' && (character.isWhitespace() || Character.isSpaceChar(character))

        private fun String.withoutSpaces(): String = replace(" ", "")

        private const val MIN_SENTENCE_LENGTH = 8
        private const val MAX_SENTENCE_LENGTH = 160
        private val TERMINALS = setOf('.', '?', '!')
    }
}
