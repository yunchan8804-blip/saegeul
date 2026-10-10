/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.hangul

/**
 * 유니코드 한글 음절(U+AC00..U+D7A3)과 호환 자모(U+3131..U+318E)의 범위·자모 테이블·분해·받침 판정.
 *
 * 음절 = BASE + (초성 * 21 + 중성) * 28 + 종성이고, 종성 인덱스 0은 받침 없음이다.
 * 받침 없음은 자모 문자가 없으므로 [JONGSEONG_TAIL]에는 넣지 않는다. 받침 없음 자리에 공백·널·null을
 * 두는 쪽은 각 사용처가 [JONGSEONG_TAIL]로 자기 형태의 표를 만든다.
 */
object HangulSyllable {
    const val BASE = 0xAC00
    const val END = 0xD7A3

    const val COMPAT_JAMO_START = 0x3131
    const val COMPAT_JAMO_END = 0x318E

    const val CHOSEONG_COUNT = 19
    const val JUNGSEONG_COUNT = 21

    /** 종성 칸 수. 인덱스 0(받침 없음)을 포함한다. */
    const val JONGSEONG_COUNT = 28

    const val NO_JONGSEONG = 0
    const val RIEUL_JONGSEONG = 8

    private const val SYLLABLES_PER_CHOSEONG = JUNGSEONG_COUNT * JONGSEONG_COUNT

    val CHOSEONG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    val JUNGSEONG = charArrayOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    )

    /** 종성 인덱스 1..27의 자모. 배열 인덱스 i는 종성 인덱스 i + 1이다. */
    val JONGSEONG_TAIL = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    /** 표준 겹받침 11개. (앞 자음, 뒤 자음) -> 겹받침. 두벌식에서 두 자음 키를 이어 치면 만들어진다. */
    val COMPOUND_JONGSEONG_JOIN: Map<Pair<Char, Char>, Char> = mapOf(
        ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
        ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
        ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
        ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ'
    )

    /** [COMPOUND_JONGSEONG_JOIN]의 역: 겹받침 -> (앞 자음, 뒤 자음). */
    val COMPOUND_JONGSEONG_SPLIT: Map<Char, Pair<Char, Char>> =
        COMPOUND_JONGSEONG_JOIN.entries.associate { (parts, compound) -> compound to parts }

    fun isSyllable(c: Char): Boolean = c.code in BASE..END

    fun isCompatJamo(c: Char): Boolean = c.code in COMPAT_JAMO_START..COMPAT_JAMO_END

    /** 음절의 초성 인덱스(0..18). 음절이 아니면 -1. */
    fun choseongIndex(c: Char): Int =
        if (isSyllable(c)) (c.code - BASE) / SYLLABLES_PER_CHOSEONG else -1

    /** 음절의 중성 인덱스(0..20). 음절이 아니면 -1. */
    fun jungseongIndex(c: Char): Int =
        if (isSyllable(c)) ((c.code - BASE) % SYLLABLES_PER_CHOSEONG) / JONGSEONG_COUNT else -1

    /** 음절의 종성 인덱스(0..27, 0은 받침 없음). 음절이 아니면 -1. */
    fun jongseongIndex(c: Char): Int =
        if (isSyllable(c)) (c.code - BASE) % JONGSEONG_COUNT else -1

    /** 종성 인덱스 1..27의 자모. */
    fun jongseongJamo(index: Int): Char = JONGSEONG_TAIL[index - 1]

    /** 종성으로 쓰이는 자모의 종성 인덱스(1..27). 종성 자모가 아니면 -1. */
    fun jongseongIndexOf(jamo: Char): Int {
        val position = JONGSEONG_TAIL.indexOf(jamo)
        return if (position < 0) -1 else position + 1
    }

    fun hasBatchim(c: Char): Boolean = jongseongIndex(c) > NO_JONGSEONG

    fun hasRieulBatchim(c: Char): Boolean = jongseongIndex(c) == RIEUL_JONGSEONG

    /** 받침 없음 자리를 [noneSlot]으로 채운 28칸 종성 표. */
    fun jongseongTable(noneSlot: Char): CharArray = charArrayOf(noneSlot) + JONGSEONG_TAIL

    /** 초성·중성·종성 인덱스로 음절을 만든다. 인덱스가 범위를 벗어나면 결과가 보장되지 않는다. */
    fun compose(choseongIndex: Int, jungseongIndex: Int, jongseongIndex: Int): Char =
        (BASE + (choseongIndex * JUNGSEONG_COUNT + jungseongIndex) * JONGSEONG_COUNT + jongseongIndex).toChar()

    /** 음절을 초성·중성·(있으면)종성 자모로 풀고, 음절이 아닌 글자는 그대로 둔다. */
    fun decomposeToJamo(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (isSyllable(c)) {
                sb.append(CHOSEONG[choseongIndex(c)])
                sb.append(JUNGSEONG[jungseongIndex(c)])
                val jong = jongseongIndex(c)
                if (jong > NO_JONGSEONG) sb.append(jongseongJamo(jong))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
