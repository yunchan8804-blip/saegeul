/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * K21 "겹받침을 만드는 자음 교체": on a mobile screen keyboard, a multitap cycle's or a
 * stroke-addition's first guessed consonant can fail to extend the syllable before it as a
 * batchim, so libhangul commits that syllable right away; when the *replacement* consonant would
 * have formed a standard Dubeolsik compound batchim with it (ㄴ+ㅎ=ㄶ, ㅂ+ㅅ=ㅄ, ㄹ+ㅌ=ㄾ, ㄹ+ㅁ=ㄻ,
 * ㄴ+ㅈ=ㄵ, …), the composer must recover it instead of leaving the already-committed syllable
 * and the replaced consonant as two separate pieces. See docs/korean-input/
 * mobile-keyboard-fixes-2026-09.md's composer contract section, K21.
 *
 * Each case here mirrors the real key sequence a user would press on the actual mobile layout
 * (token ids taken from [MobileHangulKeyboard]'s own chunjiin/vega/naratgul definitions), fed
 * through [DubeolsikEngineSimulator] the same way [MobileLayoutTypingCampaignTest] and
 * [MobileLayoutTypingPlanner] do — including feeding the simulator's own composing syllable back
 * into the composer via [MobileHangulComposer.setComposingSyllable] after every press, exactly as
 * the real host's client-preedit broadcast does through [MobileHangulActionRouter
 * .onClientPreeditUpdate].
 */
class MobileHangulK21DoubleBatchimRecoveryTest {

    private fun MobileHangulComposer.type(
        engine: DubeolsikEngineSimulator,
        token: MobileHangulComposer.Token,
        atMillis: Long
    ) {
        engine.apply(press(token, atMillis))
        setComposingSyllable(engine.composingSyllable())
    }

    // ---- Chunjiin (MobileHangulKeyboard.chunjiin()) ----
    private val cjNg = MobileHangulComposer.Token.Cycle("cj_ng", listOf('ㅇ', 'ㅁ'))
    private val cjN = MobileHangulComposer.Token.Cycle("cj_n", listOf('ㄴ', 'ㄹ'))
    private val cjD = MobileHangulComposer.Token.Cycle("cj_d", listOf('ㄷ', 'ㅌ', 'ㄸ'))
    private val cjS = MobileHangulComposer.Token.Cycle("cj_s", listOf('ㅅ', 'ㅎ', 'ㅆ'))
    private val cjB = MobileHangulComposer.Token.Cycle("cj_b", listOf('ㅂ', 'ㅍ', 'ㅃ'))
    private val dot = MobileHangulComposer.Token.VowelDot
    private val i = MobileHangulComposer.Token.VowelI
    private val jamoO = MobileHangulComposer.Token.Jamo('ㅇ')

    @Test
    fun `chunjiin 많다 recovers ㄴ plus ㅎ into ㄶ after the first guess commits 만`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjNg, 0) // ㅇ
        c.type(e, cjNg, 100) // -> ㅁ
        c.type(e, i, 200) // ㅣ
        c.type(e, dot, 300) // -> ㅏ (마)
        c.type(e, cjN, 400) // ㄴ (만)
        c.type(e, cjS, 500) // ㅅ (commits 만, opens ㅅ)
        c.type(e, cjS, 600) // -> ㅎ (recovers 많)
        assertEquals("많", e.content())

        c.type(e, cjD, 2_200) // ㄷ (commits 많, opens ㄷ)
        c.type(e, i, 2_300)
        c.type(e, dot, 2_400) // -> ㅏ (다)
        assertEquals("많다", e.content())
    }

    @Test
    fun `chunjiin 삶 recovers ㄹ plus ㅁ into ㄻ after the first guess commits 살`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjS, 0) // ㅅ
        c.type(e, i, 100)
        c.type(e, dot, 200) // -> ㅏ (사)
        c.type(e, cjN, 300) // ㄴ (산)
        c.type(e, cjN, 400) // -> ㄹ (살, plain swap: 사 had no batchim yet)
        c.type(e, cjNg, 2_200) // ㅇ (commits 살, opens ㅇ)
        c.type(e, cjNg, 2_300) // -> ㅁ (recovers 삶)
        assertEquals("삶", e.content())
    }

    @Test
    fun `chunjiin 핥다 recovers ㄹ plus ㅌ into ㄾ after the first guess commits 할`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjS, 0) // ㅅ
        c.type(e, cjS, 100) // -> ㅎ (plain swap: nothing composing yet)
        c.type(e, i, 200)
        c.type(e, dot, 300) // -> ㅏ (하)
        c.type(e, cjN, 400) // ㄴ (한)
        c.type(e, cjN, 500) // -> ㄹ (할, plain swap: 하 had no batchim yet)
        c.type(e, cjD, 2_200) // ㄷ (commits 할, opens ㄷ)
        c.type(e, cjD, 2_300) // -> ㅌ (recovers 핥)
        assertEquals("핥", e.content())

        c.type(e, cjD, 4_000) // fresh ㄷ (commits 핥, opens ㄷ)
        c.type(e, i, 4_100)
        c.type(e, dot, 4_200) // -> ㅏ (다)
        assertEquals("핥다", e.content())
    }

    @Test
    fun `chunjiin 괜찮아 recovers ㄶ in the middle of a word after 괜 already committed`() {
        val cjG = MobileHangulComposer.Token.Cycle("cj_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))
        val cjJ = MobileHangulComposer.Token.Cycle("cj_j", listOf('ㅈ', 'ㅊ', 'ㅉ'))
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjG, 0) // ㄱ
        c.type(e, dot, 100)
        c.type(e, MobileHangulComposer.Token.VowelEu, 200) // -> ㅗ (고)
        c.type(e, i, 300) // -> ㅚ (괴)
        c.type(e, dot, 400) // -> ㅘ (과)
        c.type(e, i, 500) // -> ㅙ (괘)
        c.type(e, cjN, 600) // ㄴ (괜)
        c.type(e, cjJ, 2_200) // ㅈ (괝: ㄴ+ㅈ combine)
        c.type(e, cjJ, 2_300) // -> ㅊ (commits 괜, opens ㅊ)
        c.type(e, i, 2_400)
        c.type(e, dot, 2_500) // -> ㅏ (차)
        c.type(e, cjN, 2_600) // ㄴ (찬)
        c.type(e, cjS, 2_700) // ㅅ (commits 찬, opens ㅅ)
        c.type(e, cjS, 2_800) // -> ㅎ (recovers 찮)
        assertEquals("괜찮", e.content())

        c.type(e, cjNg, 4_500) // ㅇ (commits 찮, opens ㅇ)
        c.type(e, i, 4_600)
        c.type(e, dot, 4_700) // -> ㅏ (아)
        assertEquals("괜찮아", e.content())
    }

    @Test
    fun `chunjiin 없다 combines ㅂ and ㅅ into ㅄ directly, needing no recovery`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjNg, 0) // ㅇ
        c.type(e, dot, 100) // ㆍ pending
        c.type(e, i, 200) // -> ㅓ (업의 모음)
        c.type(e, cjB, 300) // ㅂ (업)
        c.type(e, cjS, 400) // ㅅ: ㅂ+ㅅ already combine, so no commit ever happens
        assertEquals("없", e.content())

        c.type(e, cjD, 2_100) // ㄷ (commits 없, opens ㄷ)
        c.type(e, i, 2_200)
        c.type(e, dot, 2_300) // -> ㅏ (다)
        assertEquals("없다", e.content())
    }

    // ---- Vega (MobileHangulKeyboard.vegaCore()) ----
    private val vgNg = MobileHangulComposer.Token.Cycle("vg_ng", listOf('ㅇ', 'ㅎ'))
    private val vgN = MobileHangulComposer.Token.Cycle("vg_n", listOf('ㄴ', 'ㄹ'))
    private val vgD = MobileHangulComposer.Token.Cycle("vg_d", listOf('ㄷ', 'ㅌ', 'ㄸ'))
    private val vgB = MobileHangulComposer.Token.Cycle("vg_b", listOf('ㅂ', 'ㅍ', 'ㅃ'))
    private val vgM = MobileHangulComposer.Token.Cycle("vg_m", listOf('ㅁ', 'ㅅ', 'ㅆ'))
    private val vgA = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))
    private val vgEo = MobileHangulComposer.Token.Cycle("vg_eo", listOf('ㅓ', 'ㅕ'))

    @Test
    fun `vega 값 recovers ㅂ plus ㅅ into ㅄ after the first guess commits 갑`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        val vgG = MobileHangulComposer.Token.Cycle("vg_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))
        c.type(e, vgG, 0) // ㄱ
        c.type(e, vgA, 100) // ㅏ (가)
        c.type(e, vgB, 200) // ㅂ (갑)
        c.type(e, vgM, 300) // ㅁ (commits 갑, opens ㅁ)
        c.type(e, vgM, 400) // -> ㅅ (recovers 값)
        assertEquals("값", e.content())
    }

    @Test
    fun `vega 없다 recovers ㅂ plus ㅅ into ㅄ after the first guess commits 업`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, vgNg, 0) // ㅇ
        c.type(e, vgEo, 100) // ㅓ (어)
        c.type(e, vgB, 200) // ㅂ (업)
        c.type(e, vgM, 300) // ㅁ (commits 업, opens ㅁ)
        c.type(e, vgM, 400) // -> ㅅ (recovers 없)
        assertEquals("없", e.content())

        c.type(e, vgD, 2_100) // ㄷ (commits 없, opens ㄷ)
        c.type(e, vgA, 2_200) // ㅏ (다)
        assertEquals("없다", e.content())
    }

    @Test
    fun `vega 핥다 recovers ㄹ plus ㅌ into ㄾ after the first guess commits 할`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, vgNg, 0) // ㅇ
        c.type(e, vgNg, 100) // -> ㅎ (plain swap: nothing composing yet)
        c.type(e, vgA, 200) // ㅏ (하)
        c.type(e, vgN, 300) // ㄴ (한)
        c.type(e, vgN, 400) // -> ㄹ (할, plain swap: 하 had no batchim yet)
        c.type(e, vgD, 2_100) // ㄷ (commits 할, opens ㄷ)
        c.type(e, vgD, 2_200) // -> ㅌ (recovers 핥)
        assertEquals("핥", e.content())

        c.type(e, vgD, 4_000) // fresh ㄷ (commits 핥, opens ㄷ)
        c.type(e, vgA, 4_100) // ㅏ (다)
        assertEquals("핥다", e.content())
    }

    @Test
    fun `vega 싫어 recovers ㄹ plus ㅎ into ㅀ after the first guess commits 실`() {
        val vgIe = MobileHangulComposer.Token.Cycle("vg_ie", listOf('ㅣ', 'ㅡ', 'ㅢ'))
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, vgM, 0) // ㅁ
        c.type(e, vgM, 100) // -> ㅅ (plain swap: nothing composing yet)
        c.type(e, vgIe, 200) // ㅣ (시)
        c.type(e, vgN, 300) // ㄴ (신)
        c.type(e, vgN, 400) // -> ㄹ (실, plain swap: 시 had no batchim yet)
        c.type(e, vgNg, 500) // ㅇ (commits 실, opens ㅇ)
        c.type(e, vgNg, 600) // -> ㅎ (recovers 싫)
        assertEquals("싫", e.content())

        c.type(e, vgNg, 2_200) // fresh ㅇ (commits 싫, opens ㅇ)
        c.type(e, vgEo, 2_300) // ㅓ (어)
        assertEquals("싫어", e.content())
    }

    // ---- Naratgul (MobileHangulKeyboard.naratgulCore()) ----
    private val nrO = MobileHangulComposer.Token.Jamo('ㅇ')
    private val nrN = MobileHangulComposer.Token.Jamo('ㄴ')
    private val nrS = MobileHangulComposer.Token.Jamo('ㅅ')
    private val nrA = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)
    private val addStroke = MobileHangulComposer.Token.AddStroke

    @Test
    fun `naratgul 앉아 recovers ㄴ plus ㅈ into ㄵ via AddStroke after the first guess commits 안`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, nrO, 0) // ㅇ
        c.type(e, nrA, 100) // ㅏ (아)
        c.type(e, nrN, 200) // ㄴ (안)
        c.type(e, nrS, 300) // ㅅ (commits 안, opens ㅅ)
        c.type(e, addStroke, 400) // ㅅ -> ㅈ (recovers 앉)
        assertEquals("앉", e.content())

        c.type(e, nrO, 2_100) // ㅇ (commits 앉, opens ㅇ)
        c.type(e, nrA, 2_200) // ㅏ (아)
        assertEquals("앉아", e.content())
    }

    @Test
    fun `naratgul 앉아서 continues past the recovered ㄵ with a plain vowel cycle`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, nrO, 0)
        c.type(e, nrA, 100)
        c.type(e, nrN, 200)
        c.type(e, nrS, 300)
        c.type(e, addStroke, 400) // 앉
        c.type(e, nrO, 2_100)
        c.type(e, nrA, 2_200) // 앉아
        c.type(e, nrS, 2_300) // ㅅ tentative batchim of 아
        c.type(e, nrA, 2_400) // ㅏ: 도깨비불 moves ㅅ onward, 아 commits, open 사
        c.type(e, nrA, 2_500) // -> ㅓ (서)
        assertEquals("앉아서", e.content())
    }

    @Test
    fun `naratgul 많다 recovers ㄴ plus ㅎ into ㄶ via AddStroke on ㅇ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, MobileHangulComposer.Token.Jamo('ㅁ'), 0) // ㅁ
        c.type(e, nrA, 100) // ㅏ (마)
        c.type(e, nrN, 200) // ㄴ (만)
        c.type(e, nrO, 300) // ㅇ (commits 만, opens ㅇ)
        c.type(e, addStroke, 400) // ㅇ -> ㅎ (recovers 많)
        assertEquals("많", e.content())

        c.type(e, nrN, 500) // ㄴ (commits 많, opens ㄴ)
        c.type(e, addStroke, 600) // ㄴ -> ㄷ
        c.type(e, nrA, 700) // ㅏ (다)
        assertEquals("많다", e.content())
    }

    @Test
    fun `naratgul 핥다 recovers ㄹ plus ㅌ into ㄾ through two AddStroke presses on ㄴ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, nrO, 0) // ㅇ
        c.type(e, addStroke, 100) // ㅇ -> ㅎ (plain swap: nothing composing yet)
        c.type(e, nrA, 200) // ㅏ (하)
        c.type(e, MobileHangulComposer.Token.Jamo('ㄹ'), 300) // ㄹ (할)
        c.type(e, nrN, 400) // ㄴ (commits 할, opens ㄴ)
        c.type(e, addStroke, 500) // ㄴ -> ㄷ: ㄹ+ㄷ is no compound either, so 할 stays committed
        assertEquals("할ㄷ", e.content())
        c.type(e, addStroke, 600) // ㄷ -> ㅌ (recovers 핥)
        assertEquals("핥", e.content())

        c.type(e, nrN, 700) // ㄴ (commits 핥, opens ㄴ)
        c.type(e, addStroke, 800) // ㄴ -> ㄷ
        c.type(e, nrA, 900) // ㅏ (다)
        assertEquals("핥다", e.content())
    }

    // ---- Regressions the design doc calls out explicitly ----

    @Test
    fun `regression 만세 never recovers when the consonant is a single fresh tap, not a replace`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjNg, 0) // ㅇ
        c.type(e, cjNg, 100) // -> ㅁ
        c.type(e, i, 200)
        c.type(e, dot, 300) // -> ㅏ (마)
        c.type(e, cjN, 400) // ㄴ (만)
        c.type(e, cjS, 2_100) // fresh single ㅅ tap: commits 만, opens ㅅ, no recovery attempted
        c.type(e, dot, 2_200)
        c.type(e, i, 2_300)
        c.type(e, i, 2_400) // dot, i, i -> ㅔ (세)
        assertEquals("만세", e.content())
    }

    @Test
    fun `regression 많이 keeps ㄶ intact once a fresh ㅇ cannot extend it further`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjNg, 0)
        c.type(e, cjNg, 100)
        c.type(e, i, 200)
        c.type(e, dot, 300) // 마
        c.type(e, cjN, 400) // 만
        c.type(e, cjS, 500)
        c.type(e, cjS, 600) // 많 (K21 recovery)
        c.type(e, jamoO, 2_100) // ㅇ cannot extend ㄶ further: commits 많, opens bare ㅇ
        c.type(e, i, 2_200) // ㅣ (이)
        assertEquals("많이", e.content())
    }

    @Test
    fun `regression ㄶ replaced by ㅆ lets 만 commit naturally instead of re-recovering`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjNg, 0)
        c.type(e, cjNg, 100)
        c.type(e, i, 200)
        c.type(e, dot, 300) // 마
        c.type(e, cjN, 400) // 만
        c.type(e, cjS, 500)
        c.type(e, cjS, 600) // 많 (recovery)
        c.type(e, cjS, 700) // -> ㅆ: ㅎ *did* combine, so no re-recovery; libhangul commits 만 itself
        assertEquals("만ㅆ", e.content())
    }
}
