/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Accumulates a composer's raw Dubeolsik key stream the same way [RedTeamHangulEngineAutomataTest]'s
 * VirtualDubeolsikBuffer does, kept local to this file rather than shared.
 */
private class VegaDubeolsikBuffer {
    private val buffer = StringBuilder()

    fun applyAll(outputs: List<MobileHangulComposer.Output>) {
        outputs.forEach { output ->
            when (output) {
                MobileHangulComposer.Output.Backspace ->
                    if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                MobileHangulComposer.Output.Space -> buffer.append(' ')
                is MobileHangulComposer.Output.Keys -> buffer.append(output.value)
            }
        }
    }

    fun content(): String = buffer.toString()
}

/** Vega's own composer behavior: cross-key vowel combination and its ㅣㅡ multitap key. */
class MobileHangulVegaComposerTest {

    @Test
    fun `K3 vega ie key replays without corrupting the base syllable`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = VegaDubeolsikBuffer()
        val ie = MobileHangulComposer.Token.Cycle("vg_ie", listOf('ㅣ', 'ㅡ', 'ㅢ'))

        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㅜ')))
        assertEquals("구", "rn", buffer.content())

        buffer.applyAll(c.press(ie, 0))
        assertEquals("×1 -> 귀", "rnl", buffer.content())

        buffer.applyAll(c.press(ie, 100))
        assertEquals("×2 -> 구ㅡ", "rnm", buffer.content())

        buffer.applyAll(c.press(ie, 200))
        assertEquals("×3 -> 구ㅢ (구의)", "rnml", buffer.content())

        buffer.applyAll(c.press(ie, 300))
        assertEquals("×4 -> back to 귀", "rnl", buffer.content())
    }

    @Test
    fun `vega consonant cycle steps through giyeok kieuk ssanggiyeok`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = VegaDubeolsikBuffer()
        val g = MobileHangulComposer.Token.Cycle("vg_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))

        buffer.applyAll(c.press(g, 0))
        assertEquals("r", buffer.content())
        buffer.applyAll(c.press(g, 100))
        assertEquals("z", buffer.content())
        buffer.applyAll(c.press(g, 200))
        assertEquals("R", buffer.content())
    }

    @Test
    fun `vega ㅏㅑ cycle replaces cleanly when there is no leading vowel`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = VegaDubeolsikBuffer()
        val a = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))

        buffer.applyAll(c.press(a, 0))
        assertEquals("k", buffer.content())
        buffer.applyAll(c.press(a, 100))
        assertEquals("i", buffer.content())
    }

    @Test
    fun `vega cross-key vowels combine ㅗ then ㅏ into ㅘ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = VegaDubeolsikBuffer()
        val o = MobileHangulComposer.Token.Cycle("vg_o", listOf('ㅗ', 'ㅛ'))
        val a = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))

        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        buffer.applyAll(c.press(o, 0))
        assertEquals("고", "rh", buffer.content())

        buffer.applyAll(c.press(a, 1_000))
        assertEquals("과", "rhk", buffer.content())
    }
}
