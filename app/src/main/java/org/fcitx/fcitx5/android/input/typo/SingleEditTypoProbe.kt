/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.hangul.HangulSyllable

/**
 * 흔한 오타 한 번을 되돌린 변형을 만들어 어휘에서 정확 일치로 찾는 탐색기.
 *
 * 교정 트라이는 상위 [BaseKoreanVocabulary.TYPO_VOCAB_LIMIT]개만 담지만, 이 탐색기는 트라이를
 * 키우지 않고 전체 기본 어휘와 개인 어휘를 직접 조회한다. 변형은 음절 단위(초성·중성·종성
 * 분해 후 유니코드 재조합)로 만들며, 한 번에 한 가지 편집만 적용한다. 완성형 음절이 아닌
 * 문자(낱자모 포함)가 있는 위치는 건너뛴다.
 */
class SingleEditTypoProbe(
    private val baseVocabulary: BaseKoreanVocabulary?,
    private val ngram: PersonalNgramModel
) {

    private class Alt(val index: Int, val cost: Float, val isShift: Boolean, val key: Char)

    private class Edit(var cost: Float, var isShift: Boolean)

    /** [correction]이 Shift 되돌림 변형으로도 얻어지는 후보인지 함께 담은 탐색 결과. */
    class Hit(val correction: KeyboardAwareTypoCorrector.Correction, val isShiftEdit: Boolean)

    /** 한 번의 오타 되돌림으로 어휘에 닿는 후보를 score 내림차순으로 최대 [limit]개 돌려준다. */
    fun correct(
        typed: String,
        limit: Int = 3,
        contextBoost: (String) -> Float = { 0f },
        touchTrace: TouchTrace? = null
    ): List<KeyboardAwareTypoCorrector.Correction> =
        probe(typed, limit, contextBoost, touchTrace).map { it.correction }

    /**
     * [correct]와 같은 후보를 Shift 되돌림 여부와 함께 돌려준다. [touchTrace] 끝부분이 [typed]의 키
     * 순서와 정확히 같으면, 인접 키로 바꾸는 편집의 비용을 격자 대신 그 키를 누른 실제 터치 지점으로
     * 계산한다. 편집 후보의 범위와 Shift·복합 모음·중복·교환 편집의 비용은 흔적과 상관없다.
     */
    fun probe(
        typed: String,
        limit: Int = 3,
        contextBoost: (String) -> Float = { 0f },
        touchTrace: TouchTrace? = null
    ): List<Hit> {
        if (typed.isEmpty() || typed.length > MAX_TYPED_LENGTH) return emptyList()
        val out = ArrayList<Hit>()
        // 흔적 끝부분이 이 어절의 키 순서와 정확히 같을 때만 위치별 터치를 쓴다. 다르면 격자 비용이다.
        val touches = touchTrace?.alignedTo(DubeolsikKeyMap.keySequence(typed))
        for ((word, edit) in variants(typed, upgradeOnly = false, touches = touches)) {
            if (word == typed) continue
            val rank = baseVocabulary?.rankOf(word) ?: 0
            val personalCount = ngram.unigramCount(word)
            val personalKnown = personalCount >= PERSONAL_MIN_COUNT
            if (rank <= 0 && !personalKnown) continue
            var prior = 0f
            if (rank > 0) prior = baseVocabulary!!.priorAtRank(rank)
            if (personalKnown) prior = maxOf(prior, PersonalNgramModel.personalPrior(personalCount))
            val correction = KeyboardAwareTypoCorrector.Correction(
                word, edit.cost, prior, prior + contextBoost(word) - 2.0f * edit.cost
            )
            out.add(Hit(correction, edit.isShift))
        }
        return out.sortedWith(compareByDescending<Hit> { it.correction.score }.thenBy { it.correction.cost })
            .take(limit)
    }

    /**
     * 트라이가 '아는 단어'로 보는 [typed]가 사실은 훨씬 흔한 단어의 오타일 때 그 단어 하나.
     * Shift 되돌림·복합 모음 복원 변형만 보고, 후보가 상위 [UPGRADE_CANDIDATE_RANK]위 안이며
     * 입력보다 [UPGRADE_RANK_RATIO]배 이상 앞설 때만 낸다. 입력이 상위
     * [UPGRADE_MIN_INPUT_RANK]위 안이거나 한 음절이면 건드리지 않는다.
     */
    fun upgradeKnownWord(typed: String): KeyboardAwareTypoCorrector.Correction? {
        val vocabulary = baseVocabulary ?: return null
        if (typed.length < UPGRADE_MIN_INPUT_LENGTH || typed.length > MAX_TYPED_LENGTH) return null
        val inputRank = vocabulary.rankOf(typed)
        if (inputRank <= UPGRADE_MIN_INPUT_RANK) return null
        var best: KeyboardAwareTypoCorrector.Correction? = null
        for ((word, edit) in variants(typed, upgradeOnly = true, touches = null)) {
            val cost = edit.cost
            if (word == typed) continue
            val rank = vocabulary.rankOf(word)
            if (rank <= 0 || rank > UPGRADE_CANDIDATE_RANK) continue
            if (rank.toLong() * UPGRADE_RANK_RATIO >= inputRank) continue
            val prior = vocabulary.priorAtRank(rank)
            val candidate = KeyboardAwareTypoCorrector.Correction(word, cost, prior, prior - 2.0f * cost)
            val current = best
            if (current == null || candidate.score > current.score) best = candidate
        }
        return best
    }

    private fun variants(typed: String, upgradeOnly: Boolean, touches: List<KeyTouch>?): Map<String, Edit> {
        val out = HashMap<String, Edit>()
        val chars = typed.toCharArray()
        // 각 글자의 첫 키가 어절 키 순서에서 놓이는 자리. 터치 흔적과 같은 자리 번호를 쓴다.
        var keyOffset = 0
        for (i in chars.indices) {
            val syllable = chars[i]
            val syllableKeyCount = if (touches == null) 0 else DubeolsikKeyMap.keySequence(syllable.toString()).length
            val syllableKeyOffset = keyOffset
            keyOffset += syllableKeyCount
            if (!HangulSyllable.isSyllable(syllable)) continue
            val cho = HangulSyllable.choseongIndex(syllable)
            val jung = HangulSyllable.jungseongIndex(syllable)
            val jong = HangulSyllable.jongseongIndex(syllable)

            for (alt in CHO_ALTS[cho]) {
                if (upgradeOnly && !alt.isShift) continue
                val cost = altCost(alt, touches, syllableKeyOffset)
                put(out, chars, i, compose(alt.index, jung, jong), cost, alt.isShift)
            }
            // 키 하나인 중성은 초성 바로 다음 키다. 복합 모음은 키가 둘이라 JUNG_ALTS가 비어 있다.
            for (alt in JUNG_ALTS[jung]) {
                if (upgradeOnly && !alt.isShift) continue
                val cost = altCost(alt, touches, syllableKeyOffset + 1)
                put(out, chars, i, compose(cho, alt.index, jong), cost, alt.isShift)
            }
            for (target in JUNG_RESTORE[jung]) {
                put(out, chars, i, compose(cho, target, jong), COMPOUND_RESTORE_COST, false)
            }
            // 키 하나인 받침은 글자의 마지막 키다. 겹받침은 키가 둘이라 JONG_ALTS가 비어 있다.
            for (alt in JONG_ALTS[jong]) {
                if (upgradeOnly && !alt.isShift) continue
                val cost = altCost(alt, touches, syllableKeyOffset + syllableKeyCount - 1)
                put(out, chars, i, compose(cho, jung, alt.index), cost, alt.isShift)
            }
            if (upgradeOnly) continue
            for (target in JUNG_CONFUSE[jung]) {
                put(out, chars, i, compose(cho, target, jong), COMPOUND_CONFUSE_COST, false)
            }
        }
        if (upgradeOnly) return out
        for (i in 0 until chars.size - 1) {
            val a = chars[i]
            val b = chars[i + 1]
            if (!HangulSyllable.isSyllable(a) || !HangulSyllable.isSyllable(b)) continue
            if (a == b) {
                merge(out, typed.removeRange(i, i + 1), DUPLICATE_SYLLABLE_COST, false)
            } else {
                chars[i] = b
                chars[i + 1] = a
                merge(out, String(chars), SWAP_COST, false)
                chars[i] = a
                chars[i + 1] = b
            }
        }
        return out
    }

    /** 시프트 편집이 아닌 인접 키 편집이 흔적 있는 [keyPosition]에 놓이면 터치 지점으로 계산한 비용이다. */
    private fun altCost(alt: Alt, touches: List<KeyTouch>?, keyPosition: Int): Float {
        if (touches == null || alt.isShift) return alt.cost
        return TouchSubstitutionCost.cost(touches[keyPosition], alt.key) ?: alt.cost
    }

    private fun put(
        out: MutableMap<String, Edit>,
        chars: CharArray,
        index: Int,
        syllable: Char,
        cost: Float,
        isShift: Boolean
    ) {
        val original = chars[index]
        chars[index] = syllable
        merge(out, String(chars), cost, isShift)
        chars[index] = original
    }

    private fun merge(out: MutableMap<String, Edit>, word: String, cost: Float, isShift: Boolean) {
        val existing = out[word]
        if (existing == null) {
            out[word] = Edit(cost, isShift)
            return
        }
        if (cost < existing.cost) existing.cost = cost
        if (isShift) existing.isShift = true
    }

    private fun compose(cho: Int, jung: Int, jong: Int): Char = HangulSyllable.compose(cho, jung, jong)

    companion object {
        /** 후보가 입력보다 이만큼 이상 흔해야 한다: 아는 단어의 업그레이드와 희귀어의 Shift 되돌림 후보. */
        const val UPGRADE_RANK_RATIO = 100

        private const val SHIFT_COST = 0.35f
        private const val ADJACENT_MAX_COST = 0.4f
        private const val COMPOUND_RESTORE_COST = 0.45f
        private const val COMPOUND_CONFUSE_COST = 0.4f
        private const val DUPLICATE_SYLLABLE_COST = 0.5f
        private const val SWAP_COST = 0.6f

        // 어절이 너무 길면 변형 수가 수백 개를 넘으므로 탐색하지 않는다.
        private const val MAX_TYPED_LENGTH = 16

        private const val PERSONAL_MIN_COUNT = 2f

        private const val UPGRADE_MIN_INPUT_RANK = 1_000
        private const val UPGRADE_CANDIDATE_RANK = 5_000
        private const val UPGRADE_MIN_INPUT_LENGTH = 2

        private val EMPTY_ALTS = emptyArray<Alt>()

        // 받침 없음 칸은 키 하나로 만드는 자모가 아니므로 어떤 키와도 겹치지 않는 공백으로 채운다.
        private const val NO_JONGSEONG_SLOT = ' '

        private val CHO_ALTS: Array<Array<Alt>> = alternatives(HangulSyllable.CHOSEONG)
        private val JUNG_ALTS: Array<Array<Alt>> = alternatives(HangulSyllable.JUNGSEONG)
        private val JONG_ALTS: Array<Array<Alt>> = alternatives(HangulSyllable.jongseongTable(NO_JONGSEONG_SLOT))

        // 입력 중성 -> 복원할 복합 모음. 머해->뭐해, 가제->과제, 쉬어요->쉬워요.
        private val JUNG_RESTORE: Array<IntArray> = jungTable(
            'ㅓ' to "ㅝ", 'ㅏ' to "ㅘ", 'ㅐ' to "ㅙ", 'ㅣ' to "ㅚㅟㅢ", 'ㅔ' to "ㅞ",
            'ㅗ' to "ㅘㅚ", 'ㅜ' to "ㅝㅟ", 'ㅡ' to "ㅢ"
        )

        // 입력 중성 -> 혼동하기 쉬운 복합 모음. 됬어->됐어.
        private val JUNG_CONFUSE: Array<IntArray> = jungTable(
            'ㅚ' to "ㅙ", 'ㅙ' to "ㅚ", 'ㅐ' to "ㅔ", 'ㅔ' to "ㅐ"
        )

        private fun jungTable(vararg rows: Pair<Char, String>): Array<IntArray> {
            val jungseong = HangulSyllable.JUNGSEONG
            val table = Array(jungseong.size) { IntArray(0) }
            for ((from, targets) in rows) {
                table[jungseong.indexOf(from)] = IntArray(targets.length) { jungseong.indexOf(targets[it]) }
            }
            return table
        }

        private fun keyOf(jamo: Char): Char? {
            val keys = DubeolsikKeyMap.keySequence(jamo.toString())
            return if (keys.length == 1 && keys[0] != jamo) keys[0] else null
        }

        /**
         * 같은 자리(초성·중성·종성)에서 두벌식 키 하나로 만드는 자모끼리의 치환 후보.
         * 같은 키의 Shift 차이는 0.35, 시프트가 아닌 인접 키는 [DubeolsikKeyMap.substitutionCost]
         * 값이다. 복합 모음·겹받침·받침 없음은 키 하나가 아니므로 대상에서 뺀다.
         */
        private fun alternatives(jamos: CharArray): Array<Array<Alt>> {
            val keys = Array(jamos.size) { keyOf(jamos[it]) }
            return Array(jamos.size) { from ->
                val fromKey = keys[from] ?: return@Array EMPTY_ALTS
                val alts = ArrayList<Alt>()
                for (to in jamos.indices) {
                    val toKey = keys[to] ?: continue
                    if (to == from) continue
                    if (fromKey.lowercaseChar() == toKey.lowercaseChar()) {
                        alts.add(Alt(to, SHIFT_COST, isShift = true, key = toKey))
                    } else if (!toKey.isUpperCase()) {
                        val cost = DubeolsikKeyMap.substitutionCost(fromKey, toKey)
                        if (cost <= ADJACENT_MAX_COST) alts.add(Alt(to, cost, isShift = false, key = toKey))
                    }
                }
                alts.toTypedArray()
            }
        }
    }
}
