/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

enum class KoreanSearchSource(val rank: Int) {
    QuickPhrase(0),
    Clipboard(1),
    Emotion(2),
    Emoji(3)
}

data class KoreanSearchEntry(
    val id: String,
    val source: KoreanSearchSource,
    val primaryText: String,
    val secondaryText: String? = null,
    val commitText: String = primaryText,
    val searchTerms: List<String> = listOf(primaryText),
    val sensitive: Boolean = false
)

data class KoreanSearchResult(
    val entry: KoreanSearchEntry,
    val score: Int
)

data class KoreanSearchEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int
)
