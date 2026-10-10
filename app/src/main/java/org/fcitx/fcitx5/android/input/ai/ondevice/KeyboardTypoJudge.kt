/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.prediction.source.KeyboardTypoCorrectionSource
import org.fcitx.fcitx5.android.input.hangul.HangulSyllable
import org.fcitx.fcitx5.android.input.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.typo.KeyboardAwareTypoCorrector

/**
 * 자동 추천의 [TypoEvidence]를 키보드의 오타 교정기로 판정한다. 기본 어휘나 개인 단어에 정확히 있는 어절은 오타가
 * 아니다. 그렇지 않은 한글 어절은 교정기가 표준 비용 상한 안에서 찾은 최상위 교정어가 있을 때만 오타다.
 * 앞뒤 문장 부호는 떼고 판정하고 교정어에 다시 붙인다.
 */
internal class KeyboardTypoJudge(
    private val typoCorrector: () -> KeyboardAwareTypoCorrector,
    private val baseVocabulary: () -> BaseKoreanVocabulary,
    private val personalUnigramCount: (String) -> Float
) {

    fun correctionFor(token: String): String? {
        val parts = split(token) ?: return null
        val keys = DubeolsikKeyMap.keySequence(parts.core)
        if (keys.length < MIN_KEYS || parts.core.count(HangulSyllable::isSyllable) < MIN_SYLLABLES) return null
        if (baseVocabulary().contains(parts.core) || personalUnigramCount(parts.core) >= KNOWN_PERSONAL_COUNT) {
            return null
        }
        val best = typoCorrector().correct(
            parts.core,
            limit = 1,
            maxCost = KeyboardAwareTypoCorrector.standardMaxCost(keys.length)
        ).firstOrNull() ?: return null
        if (best.word == parts.core) return null
        return "${parts.head}${best.word}${parts.tail}"
    }

    fun isWordPrefix(token: String): Boolean {
        val core = split(token)?.core ?: return false
        return baseVocabulary().completions(core, 1).isNotEmpty()
    }

    private class Parts(val head: String, val core: String, val tail: String)

    /** 한글 음절·호환 자모로만 된 어절의 알맹이만 [Parts]로 나눈다. 숫자·영문이 섞였으면 판정하지 않아 null이다. */
    private fun split(token: String): Parts? {
        val head = token.takeWhile { it in KeyboardTypoCorrectionSource.HEAD_PUNCTUATION }
        val afterHead = token.substring(head.length)
        val tail = afterHead.takeLastWhile { it in KeyboardTypoCorrectionSource.TAIL_PUNCTUATION }
        val core = afterHead.substring(0, afterHead.length - tail.length)
        if (core.isEmpty() || !core.all { HangulSyllable.isSyllable(it) || HangulSyllable.isCompatJamo(it) }) return null
        return Parts(head, core, tail)
    }

    private companion object {
        const val MIN_KEYS = 3
        const val MIN_SYLLABLES = 2
        const val KNOWN_PERSONAL_COUNT = 2f
    }
}
