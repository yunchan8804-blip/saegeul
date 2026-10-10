/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

object KoreanInitials {
    private val initialSet = HangulSyllable.CHOSEONG.toSet()

    fun isInitialQuery(query: String): Boolean {
        val compact = compact(query)
        return compact.isNotEmpty() && compact.all(initialSet::contains)
    }

    fun extract(text: String): String = buildString {
        text.forEach { character ->
            when {
                HangulSyllable.isSyllable(character) -> {
                    append(HangulSyllable.CHOSEONG[HangulSyllable.choseongIndex(character)])
                }
                character in initialSet -> append(character)
            }
        }
    }

    fun compact(text: String): String = buildString(text.length) {
        text.lowercase().forEach { character ->
            if (!character.isWhitespace()) append(character)
        }
    }
}
