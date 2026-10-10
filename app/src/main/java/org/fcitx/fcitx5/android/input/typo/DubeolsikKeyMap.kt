/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable
import kotlin.math.sqrt

/**
 * 두벌식 QWERTY 자판 매핑과 키보드 인접도 기반 치환 비용 계산.
 * 한글 음절·호환 자모 문자열을 물리 키 시퀀스로 변환하고, 두 키 사이의
 * 유클리드 거리를 기준으로 오타 치환 비용을 계산한다.
 */
object DubeolsikKeyMap {

    /**
     * 두벌식 33키 매핑 (자음·모음 27 + 시프트 6). 키 -> 자모 표가 정본이고, 나머지 표는 여기서 계산한다.
     * 대소문자 정책은 호출부마다 다르다. 이 표는 대소문자를 구분해 정확히 일치하는 키만 받는다.
     */
    val KEY_TO_JAMO: Map<Char, Char> = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ',
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ'
    )

    private val JAMO_TO_KEY: Map<Char, Char> = KEY_TO_JAMO.entries.associate { (key, jamo) -> jamo to key }

    // 시프트를 함께 눌러야 하는 키. 한글이 아닌 문자는 keySequence에서 소문자가 되므로 섞이지 않는다.
    val SHIFTED_KEYS: Set<Char> = KEY_TO_JAMO.keys.filter { it.isUpperCase() }.toSet()

    // 복합 모음은 두 개의 단모음 키 입력으로 조합된다.
    val COMPOUND_VOWEL_SPLIT: Map<Char, String> = mapOf(
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ",
        'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ",
        'ㅢ' to "ㅡㅣ"
    )

    /** [COMPOUND_VOWEL_SPLIT]의 정확한 역: (앞 모음, 뒤 모음) -> 복합 모음. */
    val COMPOUND_VOWEL_JOIN: Map<Pair<Char, Char>, Char> =
        COMPOUND_VOWEL_SPLIT.entries.associate { (compound, parts) -> (parts[0] to parts[1]) to compound }

    // 겹받침도 두 개의 낱자음 키 입력으로 조합된다.
    private val COMPOUND_JONGSEONG_SPLIT: Map<Char, String> =
        HangulSyllable.COMPOUND_JONGSEONG_SPLIT.mapValues { (_, parts) -> "${parts.first}${parts.second}" }

    /**
     * 자모(낱자모·복합 모음·겹받침 51개 포함 전부) -> 두벌식으로 칠 때 누르는 키 순서.
     * 복합 모음·겹받침은 낱자모 키를 이어 붙인 문자열이다.
     */
    val JAMO_TO_KEYS: Map<Char, String> = buildMap {
        for ((jamo, key) in JAMO_TO_KEY) put(jamo, key.toString())
        for ((compound, parts) in COMPOUND_VOWEL_SPLIT) put(compound, parts.map { JAMO_TO_KEY.getValue(it) }.joinToString(""))
        for ((compound, parts) in COMPOUND_JONGSEONG_SPLIT) put(compound, parts.map { JAMO_TO_KEY.getValue(it) }.joinToString(""))
    }

    /**
     * 글자 키 [letter]를 시프트로 눌렀는지([shifted])에 따라 [keySequence]가 만드는 키 문자.
     * 시프트가 자모를 바꾸는 키([SHIFTED_KEYS])만 대문자이고 나머지는 시프트와 상관없이 소문자다.
     */
    fun keyChar(letter: Char, shifted: Boolean): Char {
        val upper = letter.uppercaseChar()
        return if (shifted && upper in SHIFTED_KEYS) upper else letter.lowercaseChar()
    }

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

    /**
     * 텍스트를 두벌식으로 칠 때 누르는 횟수. [keySequence]의 키 하나가 1타이고, 쌍자음 ㄲㄸㅃㅆㅉ와
     * ㅒ·ㅖ는 시프트까지 2타다. 한글이 아닌 글자는 글자(코드 포인트) 하나당 1타로 센다.
     */
    fun keystrokeCount(text: String): Int {
        val keys = keySequence(text)
        return keys.codePointCount(0, keys.length) + keys.count { it in SHIFTED_KEYS }
    }

    private fun appendKeysForChar(sb: StringBuilder, c: Char) {
        if (HangulSyllable.isSyllable(c)) {
            appendJamoKeys(sb, HangulSyllable.CHOSEONG[HangulSyllable.choseongIndex(c)])
            appendJamoKeys(sb, HangulSyllable.JUNGSEONG[HangulSyllable.jungseongIndex(c)])
            val jongIdx = HangulSyllable.jongseongIndex(c)
            if (jongIdx > HangulSyllable.NO_JONGSEONG) {
                appendJamoKeys(sb, HangulSyllable.jongseongJamo(jongIdx))
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
