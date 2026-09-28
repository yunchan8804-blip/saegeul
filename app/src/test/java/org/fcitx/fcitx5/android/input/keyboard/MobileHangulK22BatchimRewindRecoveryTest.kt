/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * K22: a multitap cycle or 쌍자음 transform that passes through ㄸ/ㅃ/ㅉ — which libhangul's
 * Dubeolsik cannot take as a batchim, so it commits a batchim-less syllable before them — and
 * then comes back to a consonant that *can* be that syllable's single batchim must rebuild it,
 * the same way K21 rebuilds a compound batchim ("나" + ㅂ → ㅍ → ㅃ ("나ㅃ") → ㅂ → "납", not
 * "나ㅂ"). See docs/korean-input/mobile-keyboard-fixes-2026-09.md's composer contract section.
 *
 * Each case mirrors the real key sequence a user would press on the actual mobile layout (token
 * ids taken from [MobileHangulKeyboard]'s own chunjiin/naratgul definitions), fed through
 * [DubeolsikEngineSimulator] with the simulator's composing syllable fed back into the composer
 * after every press, exactly as the host's client-preedit broadcast does.
 */
class MobileHangulK22BatchimRewindRecoveryTest {

    private fun MobileHangulComposer.type(
        engine: DubeolsikEngineSimulator,
        token: MobileHangulComposer.Token,
        atMillis: Long
    ): List<MobileHangulComposer.Output> {
        val outputs = press(token, atMillis)
        engine.apply(outputs)
        setComposingSyllable(engine.composingSyllable())
        return outputs
    }

    private val cjG = MobileHangulComposer.Token.Cycle("cj_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))
    private val cjN = MobileHangulComposer.Token.Cycle("cj_n", listOf('ㄴ', 'ㄹ'))
    private val cjD = MobileHangulComposer.Token.Cycle("cj_d", listOf('ㄷ', 'ㅌ', 'ㄸ'))
    private val cjB = MobileHangulComposer.Token.Cycle("cj_b", listOf('ㅂ', 'ㅍ', 'ㅃ'))
    private val i = MobileHangulComposer.Token.VowelI
    private val dot = MobileHangulComposer.Token.VowelDot

    /** Types 나 on Chunjiin: ㄴ, ㅣ, ㆍ. */
    private fun MobileHangulComposer.typeNa(engine: DubeolsikEngineSimulator) {
        type(engine, cjN, 0)
        type(engine, i, 100)
        type(engine, dot, 200)
        assertEquals("나", engine.content())
    }

    @Test
    fun `chunjiin 나 plus the ㅂㅍ key four times rebuilds 납 after ㅃ committed 나`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.typeNa(e)
        c.type(e, cjB, 300) // ㅂ (납)
        c.type(e, cjB, 400) // -> ㅍ (낲)
        c.type(e, cjB, 500) // -> ㅃ (commits 나, opens ㅃ)
        assertEquals("나ㅃ", e.content())
        c.type(e, cjB, 600) // -> ㅂ (recovers 납)
        assertEquals("납", e.content())

        c.type(e, i, 700) // ㅣ: 도깨비불 moves ㅂ onward
        assertEquals("나비", e.content())
    }

    @Test
    fun `chunjiin 나 plus the ㄷㅌ key four times rebuilds 낟 after ㄸ committed 나`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.typeNa(e)
        c.type(e, cjD, 300) // ㄷ (낟)
        c.type(e, cjD, 400) // -> ㅌ (낱)
        c.type(e, cjD, 500) // -> ㄸ (commits 나, opens ㄸ)
        assertEquals("나ㄸ", e.content())
        c.type(e, cjD, 600) // -> ㄷ (recovers 낟)
        assertEquals("낟", e.content())

        c.type(e, cjD, 700) // -> ㅌ again: 낟's ㄷ still open, a plain swap
        assertEquals("낱", e.content())
    }

    @Test
    fun `naratgul 나 plus ㅁ 획추가 쌍자음 쌍자음 rebuilds 납 after ㅃ committed 나`() {
        val nrA = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val e = DubeolsikEngineSimulator()
        c.type(e, MobileHangulComposer.Token.Jamo('ㄴ'), 0) // ㄴ
        c.type(e, nrA, 100) // ㅏ (나)
        c.type(e, MobileHangulComposer.Token.Jamo('ㅁ'), 200) // ㅁ (남)
        c.type(e, MobileHangulComposer.Token.AddStroke, 300) // ㅁ -> ㅂ (납)
        c.type(e, MobileHangulComposer.Token.DoubleConsonant, 400) // ㅂ -> ㅃ (commits 나, opens ㅃ)
        assertEquals("나ㅃ", e.content())
        c.type(e, MobileHangulComposer.Token.DoubleConsonant, 500) // ㅃ -> ㅂ (recovers 납)
        assertEquals("납", e.content())
    }

    // ---- Regressions: nothing to rebuild ----

    @Test
    fun `regression a rewind with nothing composing before it is a plain swap`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjD, 0) // ㄷ
        c.type(e, cjD, 100) // -> ㅌ
        c.type(e, cjD, 200) // -> ㄸ
        val rewind = c.type(e, cjD, 300) // -> ㄷ
        assertEquals(listOf(MobileHangulComposer.Output.Backspace, MobileHangulComposer.Output.Keys("e")), rewind)
        assertEquals("ㄷ", e.content())
    }

    @Test
    fun `regression a rewind after a batchim that no ㄷ-key consonant can extend is a plain swap`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.type(e, cjG, 0) // ㄱ
        c.type(e, i, 100)
        c.type(e, dot, 200) // -> ㅏ (가)
        c.type(e, cjG, 300) // ㄱ (각)
        c.type(e, cjD, 400) // ㄷ: ㄱ+ㄷ is no compound, commits 각
        c.type(e, cjD, 500) // -> ㅌ
        c.type(e, cjD, 600) // -> ㄸ
        assertEquals("각ㄸ", e.content())
        val rewind = c.type(e, cjD, 700) // -> ㄷ: 각 already has a batchim, nothing to rebuild
        assertEquals(listOf(MobileHangulComposer.Output.Backspace, MobileHangulComposer.Output.Keys("e")), rewind)
        assertEquals("각ㄷ", e.content())
    }

    @Test
    fun `regression a single batchim swap on an open syllable never touches it`() {
        val c = MobileHangulComposer()
        val e = DubeolsikEngineSimulator()
        c.typeNa(e)
        c.type(e, cjB, 300) // ㅂ (납)
        val swap = c.type(e, cjB, 400) // -> ㅍ: 납 never committed, so this stays a plain swap
        assertEquals(listOf(MobileHangulComposer.Output.Backspace, MobileHangulComposer.Output.Keys("v")), swap)
        assertEquals("낲", e.content())
    }
}
