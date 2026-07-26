/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.fcitx.fcitx5.android.input.typo.KoreanTypoRecovery

enum class GifQueryLanguage {
    Korean,
    English
}

/**
 * Query composition owned by the GIF surface. An IME cannot reliably summon itself for an
 * EditText in its own dialog, so the inline keyboard edits this state without touching the
 * target editor or its InputConnection.
 */
class GifSearchQueryState(initialText: String = "") {
    var language: GifQueryLanguage = GifQueryLanguage.Korean
        private set

    var shifted: Boolean = false
        private set

    private var committed = initialText
    private var pendingKeys = ""

    val text: String
        get() = committed + renderedPending()

    fun type(key: Char) {
        pendingKeys += if (shifted) key.uppercaseChar() else key.lowercaseChar()
        shifted = false
    }

    fun backspace() {
        when {
            pendingKeys.isNotEmpty() -> pendingKeys = pendingKeys.dropLast(1)
            committed.isNotEmpty() -> committed = committed.dropLastCodePoint()
        }
        shifted = false
    }

    fun space() {
        commitPending()
        if (committed.isNotEmpty() && !committed.endsWith(' ')) committed += ' '
        shifted = false
    }

    fun clear() {
        committed = ""
        pendingKeys = ""
        shifted = false
    }

    fun toggleLanguage() {
        commitPending()
        language = when (language) {
            GifQueryLanguage.Korean -> GifQueryLanguage.English
            GifQueryLanguage.English -> GifQueryLanguage.Korean
        }
        shifted = false
    }

    fun toggleShift() {
        shifted = !shifted
    }

    fun submit(): String {
        commitPending()
        committed = committed.trim()
        return committed
    }

    private fun commitPending() {
        committed += renderedPending()
        pendingKeys = ""
    }

    private fun renderedPending(): String = when (language) {
        GifQueryLanguage.Korean -> KoreanTypoRecovery.englishToHangul(pendingKeys)
        GifQueryLanguage.English -> pendingKeys
    }

    private fun String.dropLastCodePoint(): String {
        val end = offsetByCodePoints(length, -1)
        return substring(0, end)
    }
}
