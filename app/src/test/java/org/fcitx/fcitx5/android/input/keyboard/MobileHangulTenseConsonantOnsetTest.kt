/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ㄸ/ㅃ/ㅉ have no jongseong form in libhangul's Dubeolsik (hangul_ic_choseong_to_jongseong
 * returns 0 for a jongseong outside the conjoinable U+11A7..U+11C2 range), so a tense consonant
 * reached after a vowel-final syllable — a multitap cycle's third entry, or Naratgul's 쌍자음 —
 * commits that syllable and opens as the next one's choseong ("나" + ㅃ → "나ㅃ").
 *
 * Each case mirrors the real key sequence a user would press on the actual mobile layout (token
 * ids taken from [MobileHangulKeyboard]'s own chunjiin/vega/naratgul definitions), fed through
 * [DubeolsikEngineSimulator] the same way [MobileLayoutTypingPlanner] does.
 */
class MobileHangulTenseConsonantOnsetTest {

    private fun MobileHangulComposer.type(
        engine: DubeolsikEngineSimulator,
        token: MobileHangulComposer.Token,
        atMillis: Long
    ) {
        engine.apply(press(token, atMillis))
        setComposingSyllable(engine.composingSyllable())
    }

    @Test
    fun `simulator commits a vowel-final syllable before ㄸ ㅃ ㅉ like libhangul`() {
        for ((key, expected) in listOf("E" to "나ㄸ", "Q" to "나ㅃ", "W" to "나ㅉ")) {
            val e = DubeolsikEngineSimulator()
            e.apply(listOf(MobileHangulComposer.Output.Keys("sk")))
            e.apply(listOf(MobileHangulComposer.Output.Keys(key)))
            assertEquals(expected, e.content())
            assertEquals("나", e.committedText())
        }
    }

    @Test
    fun `simulator still takes ㄲ and ㅆ as a batchim`() {
        for ((key, expected) in listOf("R" to "낚", "T" to "났")) {
            val e = DubeolsikEngineSimulator()
            e.apply(listOf(MobileHangulComposer.Output.Keys("sk$key")))
            assertEquals(expected, e.content())
            assertEquals("", e.committedText())
        }
    }

    @Test
    fun `chunjiin 나쁜 reaches ㅃ as the third tap of the ㅂㅍ key`() {
        val cjN = MobileHangulComposer.Token.Cycle("cj_n", listOf('ㄴ', 'ㄹ'))
        val cjB = MobileHangulComposer.Token.Cycle("cj_b", listOf('ㅂ', 'ㅍ', 'ㅃ'))
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjN, 0) // ㄴ
        c.type(e, MobileHangulComposer.Token.VowelI, 100)
        c.type(e, MobileHangulComposer.Token.VowelDot, 200) // -> ㅏ (나)
        c.type(e, cjB, 300) // ㅂ (납)
        c.type(e, cjB, 400) // -> ㅍ (낲)
        c.type(e, cjB, 500) // -> ㅃ (commits 나, opens ㅃ)
        assertEquals("나ㅃ", e.content())

        c.type(e, MobileHangulComposer.Token.VowelEu, 600) // ㅡ (쁘)
        c.type(e, cjN, 700) // ㄴ (쁜)
        assertEquals("나쁜", e.content())
    }

    @Test
    fun `vega 어쩔 reaches ㅉ as the third tap of the ㅈㅊ key`() {
        val vgNg = MobileHangulComposer.Token.Cycle("vg_ng", listOf('ㅇ', 'ㅎ'))
        val vgEo = MobileHangulComposer.Token.Cycle("vg_eo", listOf('ㅓ', 'ㅕ'))
        val vgJ = MobileHangulComposer.Token.Cycle("vg_j", listOf('ㅈ', 'ㅊ', 'ㅉ'))
        val vgN = MobileHangulComposer.Token.Cycle("vg_n", listOf('ㄴ', 'ㄹ'))
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, vgNg, 0) // ㅇ
        c.type(e, vgEo, 100) // ㅓ (어)
        c.type(e, vgJ, 200) // ㅈ (엊)
        c.type(e, vgJ, 300) // -> ㅊ (엋)
        c.type(e, vgJ, 400) // -> ㅉ (commits 어, opens ㅉ)
        assertEquals("어ㅉ", e.content())

        c.type(e, vgEo, 500) // ㅓ (쩌)
        c.type(e, vgN, 600) // ㄴ (쩐)
        c.type(e, vgN, 700) // -> ㄹ (쩔)
        assertEquals("어쩔", e.content())
    }

    @Test
    fun `naratgul 나쁜 reaches ㅃ through 획추가 and 쌍자음 on ㅁ`() {
        val nrA = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, MobileHangulComposer.Token.Jamo('ㄴ'), 0) // ㄴ
        c.type(e, nrA, 100) // ㅏ (나)
        c.type(e, MobileHangulComposer.Token.Jamo('ㅁ'), 200) // ㅁ (남)
        c.type(e, MobileHangulComposer.Token.AddStroke, 300) // ㅁ -> ㅂ (납)
        c.type(e, MobileHangulComposer.Token.DoubleConsonant, 400) // ㅂ -> ㅃ (commits 나, opens ㅃ)
        assertEquals("나ㅃ", e.content())

        c.type(e, MobileHangulComposer.Token.Jamo('ㅡ'), 500) // ㅡ (쁘)
        c.type(e, MobileHangulComposer.Token.Jamo('ㄴ'), 600) // ㄴ (쁜)
        assertEquals("나쁜", e.content())
    }
}
