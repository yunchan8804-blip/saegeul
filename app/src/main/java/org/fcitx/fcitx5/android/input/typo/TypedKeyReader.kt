/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

/**
 * 친 어절을 두벌식 키 순서로 읽은 결과. [keys]의 i번째 키에 [extraCost]가 붙는다: 글자 키에 함께 적힌
 * 보조 문자(숫자·기호)를 그 글자 키로 읽은 자리의 추가 치환 비용이다.
 */
class TypedKeyReading(val keys: String, private val extraCosts: FloatArray) {

    init {
        require(keys.length == extraCosts.size) { "keys=${keys.length} costs=${extraCosts.size}" }
    }

    fun extraCost(index: Int): Float = extraCosts[index]
}

/**
 * 어절의 각 글자를 두벌식으로 칠 때 누르는 키로 바꾼다. [DubeolsikKeyMap.keySequence]와 달리
 * 낱자모, 영문자, 글자 키에 함께 적힌 보조 문자가 섞인 어절도 키 순서와 비용을 돌려준다.
 *
 * - 완성 음절과 호환 자모: 자모로 분해해 키로 바꾼다. 비용 0.
 * - 영문자: 한/영 전환을 잊고 친 것으로 보고 같은 위치의 키로 바꾼다. 비용 0.
 * - [secondaryKeys]의 보조 문자(윗줄 숫자, 둘째·셋째 줄 기호): 글자 키를 길게 누르거나 위로 밀어 들어간
 *   것으로 보고 그 글자 키로 바꾼다. 비용 [SECONDARY_CHAR_COST].
 * - 그 밖의 글자가 있으면 어절 전체를 읽지 않는다(null).
 *
 * 보조 문자는 낱자모가 함께 있거나 양쪽이 모두 한글 글자인 자리에서만 글자 키로 읽는다. 그렇지 않은 자리의
 * 숫자·기호("10시", "3개", "카페24")는 정상 입력일 가능성이 커서 읽지 않고 글자 그대로 둔다.
 */
class TypedKeyReader(private val secondaryKeys: () -> Map<Char, Char>) {

    fun read(text: String): TypedKeyReading? {
        if (text.isEmpty()) return null
        val secondary = secondaryKeys()
        val hasLooseJamo = text.any(HangulSyllable::isCompatJamo)
        val keys = StringBuilder(text.length * 2)
        val costs = ArrayList<Float>(text.length * 2)
        for ((index, c) in text.withIndex()) {
            when {
                HangulSyllable.isSyllable(c) || c in DubeolsikKeyMap.JAMO_TO_KEYS -> {
                    val chunk = DubeolsikKeyMap.keySequence(c.toString())
                    keys.append(chunk)
                    repeat(chunk.length) { costs.add(0f) }
                }
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    keys.append(DubeolsikKeyMap.keyChar(c, shifted = c.isUpperCase()))
                    costs.add(0f)
                }
                c in secondary -> {
                    val readsAsKey = hasLooseJamo || (isHangul(text.getOrNull(index - 1)) && isHangul(text.getOrNull(index + 1)))
                    if (readsAsKey) {
                        keys.append(secondary.getValue(c))
                        costs.add(SECONDARY_CHAR_COST)
                    } else {
                        keys.append(c)
                        costs.add(0f)
                    }
                }
                else -> return null
            }
        }
        return TypedKeyReading(keys.toString(), costs.toFloatArray())
    }

    private fun isHangul(c: Char?): Boolean =
        c != null && (HangulSyllable.isSyllable(c) || c in DubeolsikKeyMap.JAMO_TO_KEYS)

    companion object {
        /** 보조 문자를 글자 키로 읽을 때 그 자리에 더하는 치환 비용. */
        const val SECONDARY_CHAR_COST = 0.3f
    }
}
