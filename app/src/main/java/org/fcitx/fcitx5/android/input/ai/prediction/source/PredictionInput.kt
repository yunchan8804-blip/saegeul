/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput

/**
 * Fragment handed to keyboard-aware typo correction, with how many characters before the cursor
 * a correction replaces (the fragment plus any trailing whitespace after a committed word).
 */
internal class TypoTarget(val typed: String, val replaceLength: Int)

/**
 * The composing stroke and the text before the cursor, split into the pieces the candidate
 * sources read. Computed once per [org.fcitx.fcitx5.android.input.ai.AiContextualPredictor.predict] call.
 */
internal class PredictionInput(val currentStroke: String, val contextBeforeCursor: String) {

    val cleanStroke: String = currentStroke.trim()

    val cleanContext: String = contextBeforeCursor.trim()

    val hasTrailingSpace: Boolean = contextBeforeCursor.endsWith(" ") || contextBeforeCursor.endsWith("\n")

    val lastWordInContext: String = cleanContext
        .substringAfterLast(' ')
        .substringAfterLast('\n')
        .substringAfterLast('\t')
        .substringAfterLast('\r')
        .trim()

    val typoTarget: TypoTarget? = when {
        cleanStroke.isNotBlank() -> TypoTarget(cleanStroke, cleanStroke.length)
        hasTrailingSpace && lastWordInContext.isNotBlank() -> {
            val trailingSpaces = contextBeforeCursor.length - contextBeforeCursor.trimEnd().length
            TypoTarget(lastWordInContext, lastWordInContext.length + trailingSpaces)
        }
        else -> null
    }

    /** The sentence being typed: the context after its last terminator, joined with the stroke. */
    val currentSentence: String = run {
        val baseSentence = cleanContext
            .substringAfterLast('.')
            .substringAfterLast('?')
            .substringAfterLast('!')
            .substringAfterLast('\n')
            .trim()
        if (cleanStroke.isNotBlank()) {
            if (baseSentence.isNotBlank()) {
                if (baseSentence.endsWith(cleanStroke)) {
                    baseSentence
                } else if (hasTrailingSpace || cleanContext.endsWith(" ")) {
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
        val precedingBeforeStroke = cleanContext.removeSuffix(cleanStroke).trim()
        precedingBeforeStroke.substringAfterLast(' ').substringAfterLast('\n').trim()
    }
}
