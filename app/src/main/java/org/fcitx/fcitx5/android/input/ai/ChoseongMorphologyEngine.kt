/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Fast Korean Jaso & Choseong Morphological Decomposition Engine.
 * Supports unicode Hangul syllables, choseong sequence extraction, and sub-syllable fuzzy matching.
 */
class ChoseongMorphologyEngine {

    companion object {
        private const val HANGUL_BASE = 0xAC00
        private const val HANGUL_END = 0xD7A3

        val CHOSEONG = charArrayOf(
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
            'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )

        val JUNGSEONG = charArrayOf(
            'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
            'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
        )

        val JONGSEONG = charArrayOf(
            ' ', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
            'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )
    }

    fun isHangulSyllable(c: Char): Boolean = c.code in HANGUL_BASE..HANGUL_END

    fun isChoseong(c: Char): Boolean = CHOSEONG.contains(c)

    fun decomposeHangul(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (isHangulSyllable(c)) {
                val code = c.code - HANGUL_BASE
                val cho = code / (21 * 28)
                val jung = (code % (21 * 28)) / 28
                val jong = code % 28

                sb.append(CHOSEONG[cho])
                sb.append(JUNGSEONG[jung])
                if (jong > 0) {
                    sb.append(JONGSEONG[jong])
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun extractChoseong(c: Char): String {
        if (isHangulSyllable(c)) {
            val cho = (c.code - HANGUL_BASE) / (21 * 28)
            return CHOSEONG[cho].toString()
        }
        if (isChoseong(c)) {
            return c.toString()
        }
        return c.toString()
    }

    fun extractChoseong(s: String): String = if (s.isEmpty()) "" else extractChoseong(s[0])

    fun extractChoseongSequence(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (isHangulSyllable(c)) {
                val cho = (c.code - HANGUL_BASE) / (21 * 28)
                sb.append(CHOSEONG[cho])
            } else if (isChoseong(c)) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun matchesChoseong(targetWord: String, queryChoseong: String): Boolean {
        if (queryChoseong.isBlank()) return true
        val targetCho = extractChoseongSequence(targetWord)
        return targetCho.startsWith(queryChoseong) || targetCho.contains(queryChoseong)
    }
}
