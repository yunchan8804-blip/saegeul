/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accumulates a composer's raw Dubeolsik key stream the way the real engine's input buffer
 * would: [MobileHangulComposer.Output.Backspace] drops the last key, [MobileHangulComposer.Output.Keys]
 * appends. This is the same simple model [RedTeamHangulEngineAutomataTest]'s VirtualDubeolsikBuffer
 * uses, kept local to this file rather than shared.
 */
private class DubeolsikBuffer {
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
        assertEquals(
            listOf(backspace, backspace, keys("hk")),
            c.press(MobileHangulComposer.Token.VowelDot)
        )
        assertEquals(
            listOf(backspace, backspace, keys("ho")),
            c.press(MobileHangulComposer.Token.VowelI)
        )
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
        assertEquals(
            listOf(backspace, backspace, keys("ho")),
            c.press(MobileHangulComposer.Token.VowelI)
        )
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
        assertEquals(
            listOf(backspace, backspace, keys("np")),
            c.press(MobileHangulComposer.Token.VowelI, 400)
        )
    }

    @Test
    fun `compound vowel rewrites erase every Dubeolsik engine key`() {
        val c = MobileHangulComposer()
        val i = MobileHangulComposer.Token.VowelI
        val dot = MobileHangulComposer.Token.VowelDot
        val eu = MobileHangulComposer.Token.VowelEu

        c.press(dot)
        c.press(eu)
        c.press(i)
        assertEquals(
            listOf(backspace, backspace, keys("hk")),
            c.press(dot)
        )
        assertEquals(
            listOf(backspace, backspace, keys("ho")),
            c.press(i)
        )

        c.reset()
        c.press(eu)
        c.press(dot)
        c.press(dot)
        c.press(i)
        assertEquals(
            listOf(backspace, backspace, keys("np")),
            c.press(i)
        )
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

    @Test
    fun `K1 backspace absorbs a lone accidental dot and leaves the completed glyph intact`() {
        val c = MobileHangulComposer()
        val buffer = DubeolsikBuffer()
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        buffer.applyAll(c.press(MobileHangulComposer.Token.VowelI))
        buffer.applyAll(c.press(MobileHangulComposer.Token.VowelDot))
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㅇ')))
        assertEquals("강", "rkd", buffer.content())

        assertTrue(c.press(MobileHangulComposer.Token.VowelDot).isEmpty())
        assertTrue("Backspace must cancel the pending dot locally", c.cancelPendingDot())
        assertEquals("강 stays intact in the backend", "rkd", buffer.content())
    }

    @Test
    fun `K1 backspace on a double pending dot leaves exactly one dot waiting`() {
        val c = MobileHangulComposer()
        c.press(MobileHangulComposer.Token.VowelDot)
        c.press(MobileHangulComposer.Token.VowelDot)
        assertEquals(2, c.pendingDotCount())

        assertTrue(c.cancelPendingDot())
        assertEquals(1, c.pendingDotCount())
    }

    @Test
    fun `K2 a reset breaks a following dot's combination with the old vowel`() {
        val c = MobileHangulComposer()
        c.press(MobileHangulComposer.Token.Jamo('ㅑ'))

        // Stands in for the composer reset that onStartInput / an empty-preedit selection
        // change / a non-mobile key would trigger.
        c.reset()

        assertEquals(
            "ㅣ must not combine into ㅒ once the old ㅑ has been forgotten",
            listOf(keys("l")),
            c.press(MobileHangulComposer.Token.VowelI)
        )
    }

    @Test
    fun `K3 naratgul cross-key vowel replace retypes the base vowel instead of doubling it`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = DubeolsikBuffer()
        val oU = MobileHangulComposer.Token.Cycle("nr_o", listOf('ㅗ', 'ㅜ'), naratgulVowelPair = true)
        val aEo = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)

        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        buffer.applyAll(c.press(oU, 0))
        buffer.applyAll(c.press(aEo, 100))
        assertEquals("과", "rhk", buffer.content())

        buffer.applyAll(c.press(aEo, 200))
        assertEquals("고어 (ㅗ kept, ㅓ replaces ㅏ)", "rhj", buffer.content())

        buffer.applyAll(c.press(aEo, 300))
        assertEquals("back to 과", "rhk", buffer.content())
    }

    @Test
    fun `K3 danmoum single-vowel cycle never touches the syllable's own front vowel`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = DubeolsikBuffer()
        val ae = MobileHangulComposer.Token.Cycle(
            "dm_ae", listOf('ㅐ', 'ㅒ'), MobileHangulComposer.SINGLE_VOWEL_MULTITAP_TIMEOUT_MS
        )

        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㅙ')))
        assertEquals("괘", "rho", buffer.content())

        buffer.applyAll(c.press(ae, 0))
        assertEquals("rhoo", buffer.content())

        // Fast second tap (well within the 300ms window): the leading 괘 must stay untouched.
        buffer.applyAll(c.press(ae, 100))
        assertEquals("front 괘 (rho) kept, only the trailing vowel cycled", "rhoO", buffer.content())
    }

    @Test
    fun `K4 space after a single-vowel or vega vowel cycle always inserts a space`() {
        val c = MobileHangulComposer()
        val danmoumVowel = MobileHangulComposer.Token.Cycle(
            "dm_a", listOf('ㅏ', 'ㅑ'), MobileHangulComposer.SINGLE_VOWEL_MULTITAP_TIMEOUT_MS
        )
        c.press(danmoumVowel, 0)
        assertEquals(listOf(space), c.press(MobileHangulComposer.Token.Boundary, 50))

        c.reset()
        val vegaVowel = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))
        c.press(vegaVowel, 0)
        assertEquals(listOf(space), c.press(MobileHangulComposer.Token.Boundary, 50))
    }

    @Test
    fun `K4 space right after a chunjiin consonant cycle is swallowed to close it`() {
        val c = MobileHangulComposer()
        val consonant = MobileHangulComposer.Token.Cycle("cj_g", listOf('ㄱ', 'ㅋ', 'ㄲ'))
        c.press(consonant, 0)
        assertEquals(emptyList<MobileHangulComposer.Output>(), c.press(MobileHangulComposer.Token.Boundary, 50))
    }

    @Test
    fun `K5 a symbol cycle key replaces one symbol with the next in tap order`() {
        val c = MobileHangulComposer()
        val periodComma = MobileHangulComposer.Token.SymbolCycle("cj_period", listOf('.', ','))
        assertEquals(listOf(keys(".")), c.press(periodComma, 0))
        assertEquals(listOf(backspace, keys(",")), c.press(periodComma, 100))
        assertEquals(listOf(backspace, keys(".")), c.press(periodComma, 200))

        val questionMark = MobileHangulComposer.Token.SymbolCycle("cj_question", listOf('?', '!'))
        assertEquals(listOf(keys("?")), c.press(questionMark, 0))
        assertEquals(listOf(backspace, keys("!")), c.press(questionMark, 100))
    }

    @Test
    fun `K13 the four-symbol punctuation cycle taps in period comma question mark exclaim order`() {
        val c = MobileHangulComposer()
        val punct = MobileHangulComposer.Token.SymbolCycle("vg_punct", listOf('.', ',', '?', '!'))
        val order = listOf(0L, 100L, 200L, 300L).map { at ->
            (c.press(punct, at).last() as MobileHangulComposer.Output.Keys).value
        }
        assertEquals(listOf(".", ",", "?", "!"), order)
    }

    @Test
    fun `K9 two-hand moakey dot then eu forms ㅗ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = DubeolsikBuffer()
        assertTrue(c.press(MobileHangulComposer.Token.VowelDot).isEmpty())
        buffer.applyAll(c.press(MobileHangulComposer.Token.VowelEu))
        assertEquals("ㅗ", "h", buffer.content())
    }

    @Test
    fun `K9 one-hand moakey vowel key's ㅣ push combines ㅑ and ㅕ into ㅒ and ㅖ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val buffer = DubeolsikBuffer()
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㅇ')))
        buffer.applyAll(c.press(MobileHangulComposer.Token.Jamo('ㅑ')))
        assertEquals("야", "di", buffer.content())
        buffer.applyAll(c.press(MobileHangulComposer.Token.VowelI))
        assertEquals("얘", "dO", buffer.content())
    }

    @Test
    fun `K10 chunjiin family combines ㅠ and a following ㅣ into ㅝ`() {
        val c = MobileHangulComposer(MobileHangulFamily.Chunjiin)
        c.press(MobileHangulComposer.Token.VowelEu) // ㅡ
        c.press(MobileHangulComposer.Token.VowelDot) // ㅜ
        c.press(MobileHangulComposer.Token.VowelDot) // ㅠ
        assertEquals(
            listOf(backspace, keys("nj")),
            c.press(MobileHangulComposer.Token.VowelI)
        )
    }

    @Test
    fun `K10 other families keep ㅠ and a following ㅣ as separate vowels`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        c.press(MobileHangulComposer.Token.Jamo('ㅠ'))
        assertEquals(
            listOf(keys("l")),
            c.press(MobileHangulComposer.Token.VowelI)
        )
    }

    @Test
    fun `K12 naratgul add-stroke also turns ㅐ into ㅒ and ㅔ into ㅖ`() {
        val c = MobileHangulComposer()
        c.press(MobileHangulComposer.Token.Jamo('ㅐ'))
        assertEquals(
            listOf(backspace, keys("O")),
            c.press(MobileHangulComposer.Token.AddStroke)
        )

        c.reset()
        c.press(MobileHangulComposer.Token.Jamo('ㅔ'))
        assertEquals(
            listOf(backspace, keys("P")),
            c.press(MobileHangulComposer.Token.AddStroke)
        )
    }
}
