/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import kotlin.math.sqrt

/**
 * 두벌식 QWERTY 자판 매핑과 키보드 인접도 기반 치환 비용 계산.
 * 한글 음절·호환 자모 문자열을 물리 키 시퀀스로 변환하고, 두 키 사이의
 * 유클리드 거리를 기준으로 오타 치환 비용을 계산한다.
 */
object DubeolsikKeyMap {

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3

    private val CHOSEONG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
        'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    private val JUNGSEONG = charArrayOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    )

    private val JONGSEONG = charArrayOf(
        ' ', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    // 두벌식 33키 매핑 (자음·모음 27 + 시프트 6)
    private val JAMO_TO_KEY: Map<Char, Char> = mapOf(
        'ㅂ' to 'q', 'ㅈ' to 'w', 'ㄷ' to 'e', 'ㄱ' to 'r', 'ㅅ' to 't',
        'ㅛ' to 'y', 'ㅕ' to 'u', 'ㅑ' to 'i', 'ㅐ' to 'o', 'ㅔ' to 'p',
        'ㅁ' to 'a', 'ㄴ' to 's', 'ㅇ' to 'd', 'ㄹ' to 'f', 'ㅎ' to 'g',
        'ㅗ' to 'h', 'ㅓ' to 'j', 'ㅏ' to 'k', 'ㅣ' to 'l',
        'ㅋ' to 'z', 'ㅌ' to 'x', 'ㅊ' to 'c', 'ㅍ' to 'v', 'ㅠ' to 'b', 'ㅜ' to 'n', 'ㅡ' to 'm',
        'ㅃ' to 'Q', 'ㅉ' to 'W', 'ㄸ' to 'E', 'ㄲ' to 'R', 'ㅆ' to 'T', 'ㅒ' to 'O', 'ㅖ' to 'P'
    )

    // 복합 모음은 두 개의 단모음 키 입력으로 조합된다.
    private val COMPOUND_VOWEL_SPLIT: Map<Char, String> = mapOf(
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ",
        'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ",
        'ㅢ' to "ㅡㅣ"
    )

    // 겹받침도 두 개의 낱자음 키 입력으로 조합된다.
    private val COMPOUND_JONGSEONG_SPLIT: Map<Char, String> = mapOf(
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ",
        'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ",
        'ㄽ' to "ㄹㅅ", 'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ",
        'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ"
    )

    private const val ROW0 = "qwertyuiop"
    private const val ROW1 = "asdfghjkl"
    private const val ROW2 = "zxcvbnm"

    /**
     * 텍스트를 두벌식 물리 키 입력 시퀀스로 변환한다.
     * 한글 음절은 초성·중성·종성으로 분해되고, 복합 모음·겹받침은 낱자모로 다시 쪼개진다.
     * 한글이 아닌 문자는 소문자로 그대로 남는다.
     */
    fun keySequence(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (c in text) {
            appendKeysForChar(sb, c)
        }
        return sb.toString()
    }

    private fun appendKeysForChar(sb: StringBuilder, c: Char) {
        val code = c.code
        if (code in HANGUL_BASE..HANGUL_END) {
            val offset = code - HANGUL_BASE
            val choIdx = offset / (21 * 28)
            val jungIdx = (offset % (21 * 28)) / 28
            val jongIdx = offset % 28
            appendJamoKeys(sb, CHOSEONG[choIdx])
            appendJamoKeys(sb, JUNGSEONG[jungIdx])
            if (jongIdx > 0) {
                appendJamoKeys(sb, JONGSEONG[jongIdx])
            }
            return
        }
        if (JAMO_TO_KEY.containsKey(c) || COMPOUND_VOWEL_SPLIT.containsKey(c) || COMPOUND_JONGSEONG_SPLIT.containsKey(c)) {
            appendJamoKeys(sb, c)
            return
        }
        sb.append(c.lowercaseChar())
    }

    private fun appendJamoKeys(sb: StringBuilder, jamo: Char) {
        val vowelSplit = COMPOUND_VOWEL_SPLIT[jamo]
        if (vowelSplit != null) {
            for (part in vowelSplit) appendJamoKeys(sb, part)
            return
        }
        val jongSplit = COMPOUND_JONGSEONG_SPLIT[jamo]
        if (jongSplit != null) {
            for (part in jongSplit) appendJamoKeys(sb, part)
            return
        }
        val key = JAMO_TO_KEY[jamo]
        sb.append(key ?: jamo)
    }

    /**
     * 키 문자의 QWERTY 물리 좌표. 시프트 키는 기본 키와 같은 좌표를 갖는다.
     * 두벌식 33키에 속하지 않는 문자는 null을 반환한다.
     */
    fun keyCoordinate(key: Char): Pair<Float, Float>? {
        val base = key.lowercaseChar()
        val idx0 = ROW0.indexOf(base)
        if (idx0 >= 0) return idx0.toFloat() to 0f
        val idx1 = ROW1.indexOf(base)
        if (idx1 >= 0) return (0.5f + idx1) to 1f
        val idx2 = ROW2.indexOf(base)
        if (idx2 >= 0) return (1.0f + idx2) to 2f
        return null
    }

    /**
     * 두 키 사이의 치환 비용. 같으면 0, 같은 키의 시프트 차이면 0.35,
     * 인접도(유클리드 거리)에 따라 0.4 / 0.7, 그 외에는 1.0.
     */
    fun substitutionCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        if (a.lowercaseChar() == b.lowercaseChar()) return 0.35f
        val coordA = keyCoordinate(a) ?: return 1.0f
        val coordB = keyCoordinate(b) ?: return 1.0f
        val dx = coordA.first - coordB.first
        val dy = coordA.second - coordB.second
        val distance = sqrt(dx * dx + dy * dy)
        return when {
            distance <= 1.2f -> 0.4f
            distance <= 2.2f -> 0.7f
            else -> 1.0f
        }
    }
}
