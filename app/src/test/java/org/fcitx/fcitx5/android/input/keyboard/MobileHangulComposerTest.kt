/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val engine = DubeolsikEngineSimulator()
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(MobileHangulComposer.Token.VowelI))
        engine.apply(c.press(MobileHangulComposer.Token.VowelDot))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅇ')))
        assertEquals("강", engine.content())

        assertTrue(c.press(MobileHangulComposer.Token.VowelDot).isEmpty())
        assertTrue("Backspace must cancel the pending dot locally", c.cancelPendingDot())
        assertEquals("강 stays intact in the backend", "강", engine.content())
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
    fun `K3 naratgul cross-key vowel replace lets go of ㅗ once it stops combining`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val oU = MobileHangulComposer.Token.Cycle("nr_o", listOf('ㅗ', 'ㅜ'), naratgulVowelPair = true)
        val aEo = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(oU, 0))
        engine.apply(c.press(aEo, 100))
        assertEquals("과", engine.content())

        // ㅓ does not combine with ㅗ: libhangul commits 고 right here, so ㄱ can never be lost.
        engine.apply(c.press(aEo, 200))
        assertEquals("고 committed, open ㅓ", "고ㅓ", engine.content())

        // ㅏ is selected again, but P (ㅗ) was already let go: it must not re-combine into 과.
        engine.apply(c.press(aEo, 300))
        assertEquals("고 stays committed, open swaps to ㅏ", "고ㅏ", engine.content())
    }

    @Test
    fun `K3 danmoum single-vowel cycle never touches the syllable's own front vowel`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val ae = MobileHangulComposer.Token.Cycle(
            "dm_ae", listOf('ㅐ', 'ㅒ'), MobileHangulComposer.SINGLE_VOWEL_MULTITAP_TIMEOUT_MS
        )

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅙ')))
        assertEquals("괘", engine.content())

        engine.apply(c.press(ae, 0))
        assertEquals("괘 committed, open ㅐ (ㅐ doesn't combine with ㅙ)", "괘ㅐ", engine.content())

        // Fast second tap (well within the 300ms window): the leading 괘 must stay untouched.
        engine.apply(c.press(ae, 100))
        assertEquals("front 괘 kept, only the trailing vowel cycled to ㅒ", "괘ㅒ", engine.content())
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
        val engine = DubeolsikEngineSimulator()
        assertTrue(c.press(MobileHangulComposer.Token.VowelDot).isEmpty())
        engine.apply(c.press(MobileHangulComposer.Token.VowelEu))
        assertEquals("ㅗ", engine.content())
    }

    @Test
    fun `K9 one-hand moakey vowel key's ㅣ push combines ㅑ and ㅕ into 얘 and 예`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅇ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅑ')))
        assertEquals("야", engine.content())
        engine.apply(c.press(MobileHangulComposer.Token.VowelI))
        assertEquals("얘", engine.content())

        c.reset()
        val engine2 = DubeolsikEngineSimulator()
        engine2.apply(c.press(MobileHangulComposer.Token.Jamo('ㅇ')))
        engine2.apply(c.press(MobileHangulComposer.Token.Jamo('ㅕ')))
        assertEquals("여", engine2.content())
        engine2.apply(c.press(MobileHangulComposer.Token.VowelI))
        assertEquals("예", engine2.content())
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

    // Representative real words, verified against a real Dubeolsik automaton (K3's "검증 기준").

    @Test
    fun `word chunjiin ㆍ toggles a full compound vowel to spell 괘`() {
        val c = MobileHangulComposer()
        val engine = DubeolsikEngineSimulator()
        val dot = MobileHangulComposer.Token.VowelDot
        val eu = MobileHangulComposer.Token.VowelEu
        val i = MobileHangulComposer.Token.VowelI

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(dot)) // pending
        engine.apply(c.press(eu)) // ㅗ
        engine.apply(c.press(i)) // ㅚ
        engine.apply(c.press(dot)) // ㅘ
        engine.apply(c.press(i)) // ㅙ
        assertEquals("괘", engine.content())
    }

    @Test
    fun `word naratgul cross-key vowel then add-stroke and 도깨비불 spell 과자`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()
        val oU = MobileHangulComposer.Token.Cycle("nr_o", listOf('ㅗ', 'ㅜ'), naratgulVowelPair = true)
        val aEo = MobileHangulComposer.Token.Cycle("nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true)

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ')))
        engine.apply(c.press(oU, 0)) // ㅗ
        engine.apply(c.press(aEo, 100)) // ㅗ+ㅏ -> ㅘ (과)
        assertEquals("과", engine.content())

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅅ'))) // tentative batchim
        engine.apply(c.press(MobileHangulComposer.Token.AddStroke)) // ㅅ -> ㅈ
        engine.apply(c.press(aEo, 10_000)) // fresh press, far past any timeout: ㅏ
        assertEquals("과자", engine.content())
    }

    @Test
    fun `word moakey-style consecutive jamo presses spell 뷁 via real automaton combining`() {
        val c = MobileHangulComposer(MobileHangulFamily.Other)
        val engine = DubeolsikEngineSimulator()

        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅂ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅜ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㅔ'))) // ㅜ+ㅔ -> ㅞ, done by the engine
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄹ')))
        engine.apply(c.press(MobileHangulComposer.Token.Jamo('ㄱ'))) // ㄹ+ㄱ -> ㄺ
        assertEquals("뷁", engine.content())
    }
}
