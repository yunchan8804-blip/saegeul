/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

/** Decides what exactly one recognized sentence adds to the editor. */
internal object DictationInsertRule {
    private const val LEADING_PUNCTUATION = ".,!?…"

    /**
     * Trims the sentence and adds one space in front when it would otherwise stick to the text
     * before the cursor. Returns null for a sentence with nothing in it.
     */
    fun compose(textBeforeCursor: CharSequence, sentence: String): String? {
        val trimmed = VoiceTranscriptPolicy.normalize(sentence) ?: return null
        val needsSpace = textBeforeCursor.isNotEmpty() &&
            !textBeforeCursor.last().isWhitespace() &&
            trimmed.first() !in LEADING_PUNCTUATION
        return if (needsSpace) " $trimmed" else trimmed
    }
}

/** The last dictated piece may be removed only while it is still exactly what precedes the cursor. */
internal object DictationErasePolicy {
    fun canErase(textBeforeCursor: CharSequence?, inserted: String): Boolean =
        inserted.isNotEmpty() && textBeforeCursor != null && textBeforeCursor.toString() == inserted
}

/** What a final result adds on top of the segments of the same session that were already typed in. */
internal sealed interface SegmentTail {
    /** The final result is exactly what the segments already put in. */
    data object Duplicate : SegmentTail

    /** The final result continues the segments; [text] is the part not typed in yet. */
    data class Rest(val text: String) : SegmentTail

    /** The final result does not start with the segments, so nothing of it can be told apart. */
    data object Unrelated : SegmentTail
}

internal object DictationSegmentTail {
    private val WHITESPACE = Regex("\\s+")

    fun of(typedSegments: List<String>, finalText: String): SegmentTail {
        val typed = squeeze(typedSegments.joinToString(" "))
        val final = squeeze(finalText)
        return when {
            final == typed -> SegmentTail.Duplicate
            final.startsWith(typed) -> SegmentTail.Rest(final.substring(typed.length).trim())
            else -> SegmentTail.Unrelated
        }
    }

    private fun squeeze(text: String): String = text.trim().replace(WHITESPACE, " ")
}
