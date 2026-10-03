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
