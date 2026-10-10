/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

/**
 * 모델의 응답이 쓰는 중인 문장을 오타만 고쳐 다시 쓴 것인지 판정한다.
 *
 * 응답이 쓴 글자 그대로 시작하면 앞부분을 떼고 이어 붙이면 된다. 하지만 오타 때문에 앞부분이 어긋나면 응답
 * 전체가 이어 붙는 접미부가 되어 버린다. 그런 응답은 쓴 문장을 응답으로 바꾸는 후보여야 한다.
 *
 * 공백은 양쪽에서 모두 지우고, 응답 앞부분 중 쓴 문장과 길이가 [LENGTH_WINDOW]자 이내로 비슷한 것들을 자모 단위
 * 편집거리로 비교한다. 가장 잘 맞는 앞부분의 거리가 쓴 문장 자모 수의 [MAX_NORMALIZED_DISTANCE] 이하면 다시 쓴 것이다.
 */
internal object TypoRestatement {

    const val MAX_NORMALIZED_DISTANCE = 0.25
    const val LENGTH_WINDOW = 3
    const val MIN_TYPED_SYLLABLES = 2
    const val MAX_PREVIOUS_DISTANCE = 0.3

    fun isRestatement(typed: String, reply: String): Boolean {
        val typedLetters = typed.filterNot(Char::isWhitespace)
        if (typedLetters.count(HangulSyllable::isSyllable) < MIN_TYPED_SYLLABLES) return false
        val replyLetters = reply.filterNot(Char::isWhitespace)
        val shortest = maxOf(1, typedLetters.length - LENGTH_WINDOW)
        val longest = minOf(replyLetters.length, typedLetters.length + LENGTH_WINDOW)
        if (shortest > longest) return false

        val typedJamo = HangulSyllable.decomposeToJamo(typedLetters)
        val allowedDistance = typedJamo.length * MAX_NORMALIZED_DISTANCE
        return (shortest..longest).any { length ->
            editDistance(typedJamo, HangulSyllable.decomposeToJamo(replyLetters.substring(0, length))) <= allowedDistance
        }
    }

    /**
     * [sentence]가 앞 내용의 문장 [previous]를 말투나 낱말만 바꿔 되풀이한 것인지 본다. 공백을 지우고 자모 단위
     * 편집거리를 구해, [previous] 자모 수의 [MAX_PREVIOUS_DISTANCE] 이하면 되풀이다.
     */
    fun isRewordingOf(previous: String, sentence: String): Boolean {
        val previousLetters = previous.filterNot(Char::isWhitespace)
        if (previousLetters.count(HangulSyllable::isSyllable) < MIN_TYPED_SYLLABLES) return false
        val previousJamo = HangulSyllable.decomposeToJamo(previousLetters)
        val sentenceJamo = HangulSyllable.decomposeToJamo(sentence.filterNot(Char::isWhitespace))
        return editDistance(previousJamo, sentenceJamo) <= previousJamo.length * MAX_PREVIOUS_DISTANCE
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
