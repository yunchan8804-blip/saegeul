/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

/**
 * Fast Korean Jaso & Choseong Morphological Decomposition Engine.
 * Supports unicode Hangul syllables, choseong sequence extraction, and sub-syllable fuzzy matching.
 */
class ChoseongMorphologyEngine {

    companion object {
        /** 상태가 없는 엔진이므로 기본 사용처는 이 인스턴스를 함께 쓴다. */
        val Default = ChoseongMorphologyEngine()
    }

    fun isHangulSyllable(c: Char): Boolean = HangulSyllable.isSyllable(c)

    fun isChoseong(c: Char): Boolean = HangulSyllable.CHOSEONG.contains(c)

    fun decomposeHangul(text: String): String = HangulSyllable.decomposeToJamo(text)

    fun extractChoseong(c: Char): String {
        if (isHangulSyllable(c)) {
            return HangulSyllable.CHOSEONG[HangulSyllable.choseongIndex(c)].toString()
        }
        return c.toString()
    }

    fun extractChoseong(s: String): String = if (s.isEmpty()) "" else extractChoseong(s[0])

    fun extractChoseongSequence(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (isHangulSyllable(c)) {
                sb.append(HangulSyllable.CHOSEONG[HangulSyllable.choseongIndex(c)])
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
