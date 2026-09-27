/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vega's own composer behavior: cross-key vowel combination and its ㅣㅡ multitap key. */
class MobileHangulVegaComposerTest {

    @Test
    fun `K3 vega ie key replays without corrupting the base syllable`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val ie = MobileHangulComposer.Token.Cycle("vg_ie", listOf('ㅣ', 'ㅡ', 'ㅢ'))

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅜ')))
        assertEquals("구", engine.content())

        engine.apply(c.press(ie, 0))
        assertEquals("×1 -> 귀", "귀", engine.content())

        engine.apply(c.press(ie, 100))
        assertEquals("×2 -> 구 committed + open ㅡ", "구ㅡ", engine.content())

        engine.apply(c.press(ie, 200))
        assertEquals("×3 -> 구 committed + open ㅢ", "구ㅢ", engine.content())

        // ×4 wraps the cycle back to ㅣ, but P was already let go at ×2: it must NOT re-combine
        // into 귀 again. Only the still-open selection changes.
        engine.apply(c.press(ie, 300))
        assertEquals("×4 -> 구 committed + open ㅣ, not 귀 again", "구ㅣ", engine.content())
    }

    @Test
    fun `vega ie key still lets P go when the very first tap doesn't combine`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        // vg_ie itself always combines on its first tap, so use a plain jamo P with a cycle
        // whose first jamo can't combine with it at all (vg_a's ㅏ never combines with ㅜ).
        val a = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅜ')))
        assertEquals("구", engine.content())

        engine.apply(c.press(a, 0))
        assertEquals("구 committed, open ㅏ, ㄱ never touched", "구ㅏ", engine.content())

        engine.apply(c.press(a, 100))
        assertEquals("replacing again only touches the open ㅏ/ㅑ, never 구", "구ㅑ", engine.content())
    }

    @Test
    fun `vega consonant cycle steps through giyeok kieuk ssanggiyeok`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val g = MobileHangulComposer.Token.Cycle("vg_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))

        engine.apply(c.press(g, 0))
        assertEquals("ㄱ", engine.content())
        engine.apply(c.press(g, 100))
        assertEquals("ㅋ", engine.content())
        engine.apply(c.press(g, 200))
        assertEquals("ㄲ", engine.content())
    }

    @Test
    fun `vega ㅏㅑ cycle replaces cleanly when there is no leading vowel`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val a = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))

        engine.apply(c.press(a, 0))
        assertEquals("ㅏ", engine.content())
        engine.apply(c.press(a, 100))
        assertEquals("ㅑ", engine.content())
    }

    @Test
    fun `vega cross-key vowels combine ㅗ then ㅏ into ㅘ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val o = MobileHangulComposer.Token.Cycle("vg_o", listOf('ㅗ', 'ㅛ'))
        val a = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(o, 0))
        assertEquals("고", engine.content())

        engine.apply(c.press(a, 1_000))
        assertEquals("과", engine.content())
    }
}
