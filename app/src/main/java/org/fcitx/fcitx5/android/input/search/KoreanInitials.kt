/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

object KoreanInitials {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val SYLLABLES_PER_INITIAL = 21 * 28

    private val initials = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )
    private val initialSet = initials.toSet()

    fun isInitialQuery(query: String): Boolean {
        val compact = compact(query)
        return compact.isNotEmpty() && compact.all(initialSet::contains)
    }

    fun extract(text: String): String = buildString {
        text.forEach { character ->
            val code = character.code
            when {
                code in HANGUL_BASE..HANGUL_END -> {
                    append(initials[(code - HANGUL_BASE) / SYLLABLES_PER_INITIAL])
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
