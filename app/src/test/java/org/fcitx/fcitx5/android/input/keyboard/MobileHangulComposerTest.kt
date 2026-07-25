/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileHangulComposerTest {
    private val backspace = MobileHangulComposer.Output.Backspace
    private val space = MobileHangulComposer.Output.Space
    private fun keys(value: String) = MobileHangulComposer.Output.Keys(value)

    @Test
    fun `Samsung phonepad cycle uses its 1500ms timeout`() {
        val token = MobileHangulComposer.Token.Cycle(
            "giyeok", listOf('ㄱ', 'ㅋ', 'ㄲ'), timeoutMillis = 1_500
        )
        val within = MobileHangulComposer()
        assertEquals(listOf(keys("r")), within.press(token, 100))
        assertEquals(listOf(backspace, keys("z")), within.press(token, 1_599))
        assertEquals(listOf(backspace, keys("R")), within.press(token, 3_099))

        val expired = MobileHangulComposer()
        assertEquals(listOf(keys("r")), expired.press(token, 100))
        assertEquals(listOf(keys("r")), expired.press(token, 1_601))
    }

    @Test
    fun `Samsung single vowel cycle uses its 300ms timeout`() {
        val token = MobileHangulComposer.Token.Cycle(
            "dm_a", listOf('ㅏ', 'ㅑ'), timeoutMillis = 300
        )
        val within = MobileHangulComposer()
        assertEquals(listOf(keys("k")), within.press(token, 100))
        assertEquals(listOf(backspace, keys("i")), within.press(token, 400))

        val expired = MobileHangulComposer()
        expired.press(token, 100)
        assertEquals(listOf(keys("k")), expired.press(token, 401))
    }

    @Test
    fun `chunjiin primitives form directional and compound vowels`() {
        val c = MobileHangulComposer()
        assertEquals(listOf(keys("l")), c.press(MobileHangulComposer.Token.VowelI))
        assertEquals(listOf(backspace, keys("k")), c.press(MobileHangulComposer.Token.VowelDot))
        assertEquals(listOf(backspace, keys("i")), c.press(MobileHangulComposer.Token.VowelDot))

        c.reset()
        assertEquals(emptyList<MobileHangulComposer.Output>(), c.press(MobileHangulComposer.Token.VowelDot))
        assertEquals(listOf(keys("j")), c.press(MobileHangulComposer.Token.VowelI))

        c.reset()
        c.press(MobileHangulComposer.Token.VowelDot)
        c.press(MobileHangulComposer.Token.VowelDot)
        assertEquals(listOf(keys("u")), c.press(MobileHangulComposer.Token.VowelI))

        c.reset()
        c.press(MobileHangulComposer.Token.VowelDot)
        c.press(MobileHangulComposer.Token.VowelEu)
        assertEquals(listOf(backspace, keys("hl")), c.press(MobileHangulComposer.Token.VowelI))
        assertEquals(listOf(backspace, keys("hk")), c.press(MobileHangulComposer.Token.VowelDot))
        assertEquals(listOf(backspace, keys("ho")), c.press(MobileHangulComposer.Token.VowelI))
    }

    @Test
    fun `chunjiin produces every modern vowel from the three original strokes`() {
        val i = MobileHangulComposer.Token.VowelI
        val dot = MobileHangulComposer.Token.VowelDot
        val eu = MobileHangulComposer.Token.VowelEu
        val cases = mapOf(
            "k" to listOf(i, dot), "i" to listOf(i, dot, dot),
            "j" to listOf(dot, i), "u" to listOf(dot, dot, i),
            "h" to listOf(dot, eu), "y" to listOf(dot, dot, eu),
            "n" to listOf(eu, dot), "b" to listOf(eu, dot, dot),
            "m" to listOf(eu), "l" to listOf(i),
            "o" to listOf(i, dot, i), "O" to listOf(i, dot, dot, i),
            "p" to listOf(dot, i, i), "P" to listOf(dot, dot, i, i),
            "hl" to listOf(dot, eu, i), "hk" to listOf(dot, eu, i, dot),
            "ho" to listOf(dot, eu, i, dot, i),
            "nl" to listOf(eu, dot, i), "nj" to listOf(eu, dot, dot, i),
            "np" to listOf(eu, dot, dot, i, i), "ml" to listOf(eu, i)
        )

        cases.forEach { (expectedKeys, strokes) ->
            val composer = MobileHangulComposer()
            val outputs = strokes.flatMap(composer::press)
            assertEquals(expectedKeys, (outputs.last() as MobileHangulComposer.Output.Keys).value)
        }
    }

    @Test
    fun `naratgul modifiers and i key produce its documented jamo`() {
        val c = MobileHangulComposer()
        assertEquals(listOf(keys("s")), c.press(MobileHangulComposer.Token.Jamo('ㄴ')))
        assertEquals(listOf(backspace, keys("e")), c.press(MobileHangulComposer.Token.AddStroke))
        assertEquals(listOf(backspace, keys("x")), c.press(MobileHangulComposer.Token.AddStroke))

        c.reset()
        c.press(MobileHangulComposer.Token.Jamo('ㅅ'))
        assertEquals(listOf(backspace, keys("T")), c.press(MobileHangulComposer.Token.DoubleConsonant))

        c.reset()
        c.press(MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), 1_500), 0)
        assertEquals(listOf(backspace, keys("i")), c.press(MobileHangulComposer.Token.AddStroke))
        assertEquals(listOf(backspace, keys("O")), c.press(MobileHangulComposer.Token.VowelI))

        c.reset()
        c.press(MobileHangulComposer.Token.Cycle("nr_o", listOf('ㅗ', 'ㅜ'), 1_500), 0)
        assertEquals(listOf(backspace, keys("hk")), c.press(
            MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), 1_500), 2_000
        ))
        assertEquals(listOf(backspace, keys("ho")), c.press(MobileHangulComposer.Token.VowelI))
    }

    @Test
    fun `naratgul uses its documented u plus a shortcut for wo`() {
        val c = MobileHangulComposer()
        val oU = MobileHangulComposer.Token.Cycle(
            "nr_o", listOf('ㅗ', 'ㅜ'), naratgulVowelPair = true
        )
        val aEo = MobileHangulComposer.Token.Cycle(
            "nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true
        )

        assertEquals(listOf(keys("h")), c.press(oU, 100))
        assertEquals(listOf(backspace, keys("n")), c.press(oU, 200))
        assertEquals(listOf(backspace, keys("nj")), c.press(aEo, 300))
        assertEquals(listOf(backspace, keys("np")), c.press(MobileHangulComposer.Token.VowelI, 400))
    }

    @Test
    fun `space first closes an active multitap group then inserts whitespace`() {
        val c = MobileHangulComposer()
        val token = MobileHangulComposer.Token.Cycle("g", listOf('ㄱ', 'ㅋ'), 1_500)
        c.press(token, 100)
        assertEquals(emptyList<MobileHangulComposer.Output>(), c.press(MobileHangulComposer.Token.Boundary, 200))
        assertEquals(listOf(space), c.press(MobileHangulComposer.Token.Boundary, 201))

        c.reset()
        c.press(token, 100)
        assertEquals(listOf(space), c.press(MobileHangulComposer.Token.Boundary, 1_601))
    }
}
