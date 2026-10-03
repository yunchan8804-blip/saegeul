/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel

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

    private class Alt(val index: Int, val cost: Float, val isShift: Boolean)

    private class Edit(var cost: Float, var isShift: Boolean)

    /** [correction]이 Shift 되돌림 변형으로도 얻어지는 후보인지 함께 담은 탐색 결과. */
    class Hit(val correction: KeyboardAwareTypoCorrector.Correction, val isShiftEdit: Boolean)

    /** 한 번의 오타 되돌림으로 어휘에 닿는 후보를 score 내림차순으로 최대 [limit]개 돌려준다. */
    fun correct(
        typed: String,
        limit: Int = 3,
        contextBoost: (String) -> Float = { 0f }
    ): List<KeyboardAwareTypoCorrector.Correction> = probe(typed, limit, contextBoost).map { it.correction }

    /** [correct]와 같은 후보를 Shift 되돌림 여부와 함께 돌려준다. */
    fun probe(
        typed: String,
        limit: Int = 3,
        contextBoost: (String) -> Float = { 0f }
    ): List<Hit> {
        if (typed.isEmpty() || typed.length > MAX_TYPED_LENGTH) return emptyList()
        val out = ArrayList<Hit>()
        for ((word, edit) in variants(typed, upgradeOnly = false)) {
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
        for ((word, edit) in variants(typed, upgradeOnly = true)) {
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

    private fun variants(typed: String, upgradeOnly: Boolean): Map<String, Edit> {
        val out = HashMap<String, Edit>()
        val chars = typed.toCharArray()
        for (i in chars.indices) {
            val offset = chars[i].code - SYLLABLE_BASE
            if (offset !in 0 until SYLLABLE_COUNT) continue
            val cho = offset / JUNG_JONG_COUNT
            val jung = (offset % JUNG_JONG_COUNT) / JONG_COUNT
            val jong = offset % JONG_COUNT

            for (alt in CHO_ALTS[cho]) {
                if (upgradeOnly && !alt.isShift) continue
                put(out, chars, i, compose(alt.index, jung, jong), alt.cost, alt.isShift)
            }
            for (alt in JUNG_ALTS[jung]) {
                if (upgradeOnly && !alt.isShift) continue
                put(out, chars, i, compose(cho, alt.index, jong), alt.cost, alt.isShift)
            }
            for (target in JUNG_RESTORE[jung]) {
                put(out, chars, i, compose(cho, target, jong), COMPOUND_RESTORE_COST, false)
            }
            for (alt in JONG_ALTS[jong]) {
                if (upgradeOnly && !alt.isShift) continue
                put(out, chars, i, compose(cho, jung, alt.index), alt.cost, alt.isShift)
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
            if (!isSyllable(a) || !isSyllable(b)) continue
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

    private fun compose(cho: Int, jung: Int, jong: Int): Char =
        (SYLLABLE_BASE + (cho * JUNG_COUNT + jung) * JONG_COUNT + jong).toChar()

    private fun isSyllable(c: Char): Boolean = c.code - SYLLABLE_BASE in 0 until SYLLABLE_COUNT

    companion object {
        private const val SYLLABLE_BASE = 0xAC00
        private const val JUNG_COUNT = 21
        private const val JONG_COUNT = 28
        private const val JUNG_JONG_COUNT = JUNG_COUNT * JONG_COUNT
        private const val SYLLABLE_COUNT = 19 * JUNG_JONG_COUNT

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

        private val CHO_ALTS: Array<Array<Alt>> = alternatives(DubeolsikKeyMap.CHOSEONG)
        private val JUNG_ALTS: Array<Array<Alt>> = alternatives(DubeolsikKeyMap.JUNGSEONG)
        private val JONG_ALTS: Array<Array<Alt>> = alternatives(DubeolsikKeyMap.JONGSEONG)

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
            val jungseong = DubeolsikKeyMap.JUNGSEONG
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
                        alts.add(Alt(to, SHIFT_COST, isShift = true))
                    } else if (!toKey.isUpperCase()) {
                        val cost = DubeolsikKeyMap.substitutionCost(fromKey, toKey)
                        if (cost <= ADJACENT_MAX_COST) alts.add(Alt(to, cost, isShift = false))
                    }
                }
                alts.toTypedArray()
            }
        }
    }
}
