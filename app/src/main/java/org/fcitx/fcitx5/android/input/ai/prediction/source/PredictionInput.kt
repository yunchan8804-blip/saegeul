/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput
import org.fcitx.fcitx5.android.input.text.SentenceBoundary

/**
 * Fragment handed to keyboard-aware typo correction, with how many characters before the cursor
 * a correction replaces (the fragment plus any trailing whitespace after a committed word).
 */
internal class TypoTarget(val typed: String, val replaceLength: Int)

/**
 * The composing stroke and the text before the cursor, split into the pieces the candidate
 * sources read. Computed once per [org.fcitx.fcitx5.android.input.ai.AiContextualPredictor.predict] call.
 */
internal class PredictionInput(
    val currentStroke: String,
    val contextBeforeCursor: String,
    /**
     * False when the editor refused to hand over the text before the cursor: an empty
     * [contextBeforeCursor] then means "unknown", not "start of the document", so nothing may
     * assume the stroke starts a sentence.
     */
    val isContextKnown: Boolean = true
) {

    val cleanStroke: String = currentStroke.trim()

    val cleanContext: String = contextBeforeCursor.trim()

    val hasTrailingSpace: Boolean = contextBeforeCursor.isNotEmpty() && contextBeforeCursor.last().isWhitespace()

    val lastWordInContext: String = lastWordOf(cleanContext)

    /**
     * True when the context ends at a sentence boundary, so the words before the cursor belong to
     * a sentence that already ended and are no longer typo-corrected.
     */
    val contextEndsSentence: Boolean = SentenceBoundary.endsSentence(contextBeforeCursor)

    val typoTarget: TypoTarget? = when {
        cleanStroke.isNotBlank() -> TypoTarget(cleanStroke, cleanStroke.length)
        hasTrailingSpace && lastWordInContext.isNotBlank() && !contextEndsSentence -> {
            val trailingSpaces = contextBeforeCursor.length - contextBeforeCursor.trimEnd().length
            TypoTarget(lastWordInContext, lastWordInContext.length + trailingSpaces)
        }
        else -> null
    }

    /** The sentence being typed: the context after its last sentence boundary, joined with the stroke. */
    val currentSentence: String = run {
        val baseSentence = SentenceBoundary.currentSentence(contextBeforeCursor).trim()
        if (cleanStroke.isNotBlank()) {
            if (baseSentence.isNotBlank()) {
                if (baseSentence.endsWith(cleanStroke)) {
                    baseSentence
                } else if (hasTrailingSpace) {
                    "$baseSentence $cleanStroke"
                } else {
                    "$baseSentence$cleanStroke"
                }
            } else {
                cleanStroke
            }
        } else {
            baseSentence
        }
    }

    val fullContext: String = if (cleanStroke.isNotBlank() && !cleanContext.endsWith(cleanStroke)) {
        "$cleanContext $cleanStroke".trim()
    } else {
        cleanContext
    }

    val rawFullContext: String = ContextualPredictionInput.rawFullContext(currentStroke, contextBeforeCursor)

    /** The committed word the next-word models continue from, excluding a stroke still being typed. */
    val effectiveLastWord: String = if (cleanStroke.isBlank() || hasTrailingSpace) {
        lastWordInContext
    } else {
        lastWordOf(cleanContext.removeSuffix(cleanStroke))
    }

    private fun lastWordOf(text: String): String = text.trim().takeLastWhile { !it.isWhitespace() }
}
