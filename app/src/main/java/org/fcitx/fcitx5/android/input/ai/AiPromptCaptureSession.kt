/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * End-cursor text target used while the real keyboard surface is entering an AI instruction.
 *
 * The Fcitx engine remains the sole composer. This state only mirrors its committed and preedit
 * output so none of the prompt leaks into the editor that originally opened the IME.
 */
class AiPromptCaptureSession(initialText: String = "") {
    private var committed = initialText.take(AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS)
    private var preedit = ""

    val committedText: String
        get() = committed

    val preeditText: String
        get() = preedit

    val displayText: String
        get() = bounded(committed + preedit)

    fun updatePreedit(text: String) {
        preedit = text.take(remainingAfterCommitted())
    }

    fun commit(text: String) {
        if (text.isEmpty()) return
        committed = bounded(committed + text)
        preedit = ""
    }

    fun commitPreedit() {
        if (preedit.isEmpty()) return
        committed = bounded(committed + preedit)
        preedit = ""
    }

    fun deleteBeforeCursor(codePoints: Int = 1) {
        if (codePoints <= 0) return
        if (preedit.isNotEmpty()) {
            preedit = preedit.dropLastCodePoints(codePoints)
        } else {
            committed = committed.dropLastCodePoints(codePoints)
        }
    }

    fun submission(): String = displayText.trim()

    private fun remainingAfterCommitted(): Int =
        (AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS - committed.length).coerceAtLeast(0)

    private fun bounded(text: String): String =
        text.take(AiAction.MAX_CUSTOM_INSTRUCTION_CHARACTERS)

    private fun String.dropLastCodePoints(count: Int): String {
        if (isEmpty()) return this
        val available = codePointCount(0, length)
        val removed = count.coerceAtMost(available)
        return substring(0, offsetByCodePoints(length, -removed))
    }
}
