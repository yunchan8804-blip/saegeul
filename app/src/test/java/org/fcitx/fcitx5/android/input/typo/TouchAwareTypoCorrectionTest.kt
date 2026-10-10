/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.centeredTrace
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.touch
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 같은 오타 문자열이라도 눌린 위치에 따라 의도한 단어가 달라지는지: 트라이 교정과 단일 편집 탐색기.
 * 어휘의 두 단어는 우선순위가 같고 오타 어절에서 격자 비용도 같아서, 터치 흔적이 없으면 비긴다.
 */
class TouchAwareTypoCorrectionTest {

    private fun trieCorrector(
        words: List<String>,
        personalDiscount: (Char, Char) -> Float = { _, _ -> 1f }
    ): KeyboardAwareTypoCorrector {
        val corrector = KeyboardAwareTypoCorrector(personalDiscount = personalDiscount)
        words.forEach { corrector.addWord(it, 0.5f) }
        return corrector
    }

    // "다"는 ek. e의 오른쪽 이웃이 r(가), 왼쪽 이웃이 w(자)다.
    private val corrector = trieCorrector(listOf("가", "자"))

    @Test
    fun withoutATraceBothNeighborsCostTheGridCost() {
        val costs = corrector.correct("다", limit = 3).associate { it.word to it.cost }
        assertEquals(0.4f, costs.getValue("가"), 0.0001f)
        assertEquals(0.4f, costs.getValue("자"), 0.0001f)
    }

    @Test
    fun touchNearTheRightBoundaryPromotesTheRightNeighbor() {
        val trace = trace(touch('e', dx = 0.45f), touch('k'))
        val results = corrector.correct("다", limit = 3, touchTrace = trace)

        assertEquals("가", results.first().word)
        assertEquals(TouchSubstitutionCost.MIN_COST, results.first().cost, 0.0001f)
        assertTrue(results.first { it.word == "자" }.cost > 0.4f)
    }

    @Test
    fun touchNearTheLeftBoundaryPromotesTheLeftNeighbor() {
        val trace = trace(touch('e', dx = -0.45f), touch('k'))
        val results = corrector.correct("다", limit = 3, touchTrace = trace)

        assertEquals("자", results.first().word)
        assertEquals(TouchSubstitutionCost.MIN_COST, results.first().cost, 0.0001f)
    }

    @Test
    fun centerTouchKeepsBothNeighborsAtTheTouchScaleCost() {
        val results = corrector.correct("다", limit = 3, touchTrace = centeredTrace("ek"))
        results.forEach { assertEquals(0.37f, it.cost, 0.005f) }
    }

    @Test
    fun traceAlignsToTheTailOfAlongerHistory() {
        val trace = trace(touch('a'), touch('h'), touch('e', dx = 0.45f), touch('k'))
        assertEquals("가", corrector.correct("다", limit = 3, touchTrace = trace).first().word)
    }

    @Test
    fun traceWithDifferentKeysIsIgnoredAndTheGridCostApplies() {
        val trace = trace(touch('e', dx = 0.45f), touch('j'))
        val costs = corrector.correct("다", limit = 3, touchTrace = trace).associate { it.word to it.cost }
        assertEquals(0.4f, costs.getValue("가"), 0.0001f)
        assertEquals(0.4f, costs.getValue("자"), 0.0001f)
    }

    @Test
    fun traceShorterThanTheWordIsIgnored() {
        val costs = corrector.correct("다", limit = 3, touchTrace = trace(touch('k', dx = 0.45f)))
            .associate { it.word to it.cost }
        assertEquals(0.4f, costs.getValue("가"), 0.0001f)
    }

    @Test
    fun personalDiscountMultipliesTheTouchCost() {
        val discounted = trieCorrector(listOf("가", "자")) { typed, intended ->
            if (typed == 'e' && intended == 'w') 0.5f else 1f
        }
        val results = discounted.correct("다", limit = 3, touchTrace = centeredTrace("ek"))

        assertEquals("자", results.first().word)
        assertEquals(0.185f, results.first().cost, 0.005f)
        assertEquals(0.37f, results.first { it.word == "가" }.cost, 0.005f)
    }

    @Test
    fun personalDiscountIsAskedWithTheTypedKeyFirst() {
        val asked = mutableListOf<Pair<Char, Char>>()
        val recording = trieCorrector(listOf("가")) { typed, intended ->
            asked += typed to intended
            1f
        }
        recording.correct("다", limit = 3, touchTrace = centeredTrace("ek"))

        assertTrue(asked.toString(), ('e' to 'r') in asked)
        assertTrue(asked.toString(), ('r' to 'e') !in asked)
    }

    private fun probeFor(vararg words: String): SingleEditTypoProbe {
        val tsv = words.joinToString("\n") { "$it\t100" }
        val vocabulary = BaseKoreanVocabulary { StringReader(tsv) }
        vocabulary.load()
        return SingleEditTypoProbe(vocabulary, PersonalNgramModel())
    }

    private fun SingleEditTypoProbe.words(typed: String, trace: TouchTrace?): List<String> =
        probe(typed, limit = 5, touchTrace = trace).map { it.correction.word }

    @Test
    fun probeWithoutATraceCostsTheGridCost() {
        val hits = probeFor("가", "자").probe("다", limit = 5)
        assertEquals(listOf(0.4f, 0.4f), hits.map { it.correction.cost })
    }

    @Test
    fun probeChoseongEditFollowsTheTouch() {
        val probe = probeFor("가", "자")
        assertEquals("가", probe.words("다", trace(touch('e', dx = 0.45f), touch('k'))).first())
        assertEquals("자", probe.words("다", trace(touch('e', dx = -0.45f), touch('k'))).first())
    }

    @Test
    fun probeJungseongEditFollowsTheTouchOnTheSecondKey() {
        // 기 = rl. l의 왼쪽 이웃은 k(가), 위쪽 이웃은 o(개)다.
        val probe = probeFor("가", "개")
        assertEquals("가", probe.words("기", trace(touch('r'), touch('l', dx = -0.45f))).first())
        assertEquals("개", probe.words("기", trace(touch('r'), touch('l', dx = 0.1f, dy = -0.45f))).first())
    }

    @Test
    fun probeJongseongEditUsesTheKeyPositionOfALaterSyllable() {
        // 나간 = sk rks. 받침 s의 오른쪽 이웃은 d(ㅇ), 왼쪽 이웃은 a(ㅁ)다.
        val probe = probeFor("나강", "나감")
        val keys = listOf('s', 'k', 'r', 'k')
        fun traceOf(jongTouch: KeyTouch) = trace(*keys.map { touch(it) }.toTypedArray(), jongTouch)
        assertEquals("나강", probe.words("나간", traceOf(touch('s', dx = 0.45f))).first())
        assertEquals("나감", probe.words("나간", traceOf(touch('s', dx = -0.45f))).first())
    }

    @Test
    fun probeIgnoresATraceThatDoesNotMatchTheWord() {
        val probe = probeFor("가", "자")
        val hits = probe.probe("다", limit = 5, touchTrace = trace(touch('e', dx = 0.45f), touch('j')))
        assertEquals(listOf(0.4f, 0.4f), hits.map { it.correction.cost })
    }
}
