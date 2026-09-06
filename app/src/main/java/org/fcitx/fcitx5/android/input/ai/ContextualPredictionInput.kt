/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Resolves the raw text before the cursor and any in-progress composing text into the
 * (stroke, context) pair the contextual predictor expects. Buffered Hangul compatibility mode
 * can commit intermediate syllables to the editor while jamo for the next syllable are still
 * being composed, so the trailing word already sitting in [textBeforeCursor] must be stitched
 * back together with [composingText] rather than treated as a finished previous word.
 */
internal object ContextualPredictionInput {

    data class Resolved(val stroke: String, val context: String)

    private val WHITESPACE = charArrayOf(' ', '\n', '\t', '\r')

    fun resolve(textBeforeCursor: String, composingText: String): Resolved {
        val endsWithWhitespace = textBeforeCursor.isNotEmpty() && textBeforeCursor.last() in WHITESPACE

        if (endsWithWhitespace) {
            return if (composingText.isNotEmpty()) {
                Resolved(stroke = composingText, context = textBeforeCursor)
            } else {
                Resolved(stroke = "", context = textBeforeCursor)
            }
        }

        val lastWs = textBeforeCursor.lastIndexOfAny(WHITESPACE)
        val token = if (lastWs >= 0) textBeforeCursor.substring(lastWs + 1) else textBeforeCursor
        val context = if (lastWs >= 0) textBeforeCursor.substring(0, lastWs + 1) else ""

        return if (composingText.isNotEmpty()) {
            Resolved(stroke = token + composingText, context = context)
        } else {
            Resolved(stroke = token, context = context)
        }
    }
}
