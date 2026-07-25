/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract transcribed from Samsung Keyboard 5.9.00.28 on a Galaxy A35. */
class MobileHangulKeyboardLayoutTest {
    @Test
    fun `chunjiin rows and multitap groups match Samsung phonepad`() {
        val rows = MobileHangulKeyboard.layoutFor(MobileHangulLayout.Chunjiin)

        assertRows(
            rows,
            listOf(
                listOf("ㅣ", "ㆍ", "ㅡ", "BACKSPACE"),
                listOf("ㄱㅋ", "ㄴㄹ", "ㄷㅌ", "RETURN"),
                listOf("ㅂㅍ", "ㅅㅎ", "ㅈㅊ", ".,", "?!"),
                listOf("?123", "LANGUAGE", "ㅇㅁ", "SPACE", ",")
            )
        )
        assertCycle(rows[1][0], 1_500, 'ㄱ', 'ㅋ', 'ㄲ')
        assertCycle(rows[1][1], 1_500, 'ㄴ', 'ㄹ')
        assertCycle(rows[1][2], 1_500, 'ㄷ', 'ㅌ', 'ㄸ')
        assertCycle(rows[2][0], 1_500, 'ㅂ', 'ㅍ', 'ㅃ')
        assertCycle(rows[2][1], 1_500, 'ㅅ', 'ㅎ', 'ㅆ')
        assertCycle(rows[2][2], 1_500, 'ㅈ', 'ㅊ', 'ㅉ')
        assertCycle(rows[3][2], 1_500, 'ㅇ', 'ㅁ')
    }

    @Test
    fun `chunjiin plus separates consonants and puts tense forms on paired keys`() {
        val rows = MobileHangulKeyboard.layoutFor(MobileHangulLayout.ChunjiinPlus)

        assertRows(
            rows,
            listOf(
                listOf("ㅣ", "ㆍ", "ㅡ", "BACKSPACE"),
                listOf("ㄱ", "ㅋ", "ㄴ", "ㄹ", "ㄷ", "ㅌ", "RETURN"),
                listOf("ㅂ", "ㅍ", "ㅅ", "ㅎ", "ㅈ", "ㅊ", ".,", "?!"),
                listOf("?123", "LANGUAGE", "ㅇ", "ㅁ", "SPACE", ",")
            )
        )
        assertCycle(rows[1][1], 1_500, 'ㅋ', 'ㄲ')
        assertCycle(rows[1][5], 1_500, 'ㅌ', 'ㄸ')
        assertCycle(rows[2][1], 1_500, 'ㅍ', 'ㅃ')
        assertCycle(rows[2][3], 1_500, 'ㅎ', 'ㅆ')
        assertCycle(rows[2][5], 1_500, 'ㅊ', 'ㅉ')
        assertEquals("ㄲ", (rows[1][1].appearance as KeyDef.Appearance.AltText).altText)
    }

    @Test
    fun `single vowel rows and 300ms pairs match Samsung`() {
        val rows = MobileHangulKeyboard.layoutFor(MobileHangulLayout.Danmoum)

        assertRows(
            rows,
            listOf(
                listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅗ", "ㅐ", "ㅔ"),
                listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅓ", "ㅏ", "ㅣ"),
                listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅜ", "ㅡ", "BACKSPACE"),
                listOf("?123", "LANGUAGE", ",", "SPACE", ".", "RETURN")
            )
        )
        listOf(
            rows[0][0] to listOf('ㅂ', 'ㅃ'), rows[0][1] to listOf('ㅈ', 'ㅉ'),
            rows[0][2] to listOf('ㄷ', 'ㄸ'), rows[0][3] to listOf('ㄱ', 'ㄲ'),
            rows[0][4] to listOf('ㅅ', 'ㅆ'), rows[0][5] to listOf('ㅗ', 'ㅛ'),
            rows[0][6] to listOf('ㅐ', 'ㅒ'), rows[0][7] to listOf('ㅔ', 'ㅖ'),
            rows[1][5] to listOf('ㅓ', 'ㅕ'), rows[1][6] to listOf('ㅏ', 'ㅑ'),
            rows[2][4] to listOf('ㅜ', 'ㅠ')
        ).forEach { (key, jamo) -> assertCycle(key, 300, *jamo.toCharArray()) }
    }

    @Test
    fun `vega full and center rows keep Samsung side controls`() {
        val full = MobileHangulKeyboard.layoutFor(MobileHangulLayout.Vega)
        val center = MobileHangulKeyboard.layoutFor(MobileHangulLayout.VegaCenter)

        assertRows(
            full,
            listOf(
                listOf("ㄱㅋ", "ㅣㅡ", "ㅏㅑ", "BACKSPACE"),
                listOf("ㄷㅌ", "ㄴㄹ", "ㅓㅕ", "SPACE"),
                listOf("ㅁㅅ", "ㅂㅍ", "ㅗㅛ", ",", "RETURN"),
                listOf("ㅈㅊ", "ㅇㅎ", "ㅜㅠ", "?123", "LANGUAGE")
            )
        )
        assertRows(
            center,
            listOf(
                listOf("?!", "ㄱㅋ", "ㅣㅡ", "ㅏㅑ", "BACKSPACE"),
                listOf(",", "ㄷㅌ", "ㄴㄹ", "ㅓㅕ", "SPACE"),
                listOf("LANGUAGE", "ㅁㅅ", "ㅂㅍ", "ㅗㅛ", "RETURN"),
                listOf("?123", "ㅈㅊ", "ㅇㅎ", "ㅜㅠ", ".")
            )
        )
        assertCycle(full[0][1], 1_500, 'ㅣ', 'ㅡ', 'ㅢ')
        assertCycle(full[2][0], 1_500, 'ㅁ', 'ㅅ', 'ㅆ')
    }

    @Test
    fun `naratgul full and center rows use the two correct vowel pairs`() {
        val full = MobileHangulKeyboard.layoutFor(MobileHangulLayout.Naratgul)
        val center = MobileHangulKeyboard.layoutFor(MobileHangulLayout.NaratgulCenter)

        assertRows(
            full,
            listOf(
                listOf("ㄱ", "ㄴ", "ㅏㅓ", "BACKSPACE"),
                listOf("ㄹ", "ㅁ", "ㅗㅜ", "SPACE"),
                listOf("ㅅ", "ㅇ", "ㅣ", ",", "RETURN"),
                listOf("획추가", "ㅡ", "쌍자음", "?123", "LANGUAGE")
            )
        )
        assertRows(
            center,
            listOf(
                listOf("?!", "ㄱ", "ㄴ", "ㅏㅓ", "BACKSPACE"),
                listOf(",", "ㄹ", "ㅁ", "ㅗㅜ", "SPACE"),
                listOf("LANGUAGE", "ㅅ", "ㅇ", "ㅣ", "RETURN"),
                listOf("?123", "획추가", "ㅡ", "쌍자음", ".")
            )
        )
        assertCycle(full[0][2], 1_500, 'ㅏ', 'ㅓ')
        assertCycle(full[1][2], 1_500, 'ㅗ', 'ㅜ')
        assertTrue((tokens(listOf(full[0][2])).single() as MobileHangulComposer.Token.Cycle).naratgulVowelPair)
        assertTrue((tokens(listOf(full[1][2])).single() as MobileHangulComposer.Token.Cycle).naratgulVowelPair)
        assertTrue(tokens(full.flatten()).contains(MobileHangulComposer.Token.AddStroke))
        assertTrue(tokens(full.flatten()).contains(MobileHangulComposer.Token.DoubleConsonant))
    }

    @Test
    fun `Moakey keeps Samsung rows and exactly one backspace`() {
        val oneHand = MobileHangulKeyboard.layoutFor(MobileHangulLayout.MoakeyOneHand)
        val twoHand = MobileHangulKeyboard.layoutFor(MobileHangulLayout.MoakeyTwoHand)

        assertEquals(listOf("~", "ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "!"), signatures(oneHand[0]))
        assertEquals(listOf("^", "ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "?"), signatures(oneHand[1]))
        assertEquals(listOf(";", "ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "."), signatures(oneHand[2]))
        assertEquals(listOf("*", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "BACKSPACE"), signatures(oneHand[3]))

        assertEquals(listOf("~", "ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "#"), signatures(twoHand[0]))
        assertEquals(listOf("^", "ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "BACKSPACE"), signatures(twoHand[1]))
        assertEquals(listOf(";", "ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅣ"), signatures(twoHand[2]))
        assertEquals(listOf("*", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅡ", "ㆍ"), signatures(twoHand[3]))
        assertEquals(1, oneHand.flatten().count { it is BackspaceKey })
        assertEquals(1, twoHand.flatten().count { it is BackspaceKey })
    }

    @Test
    fun `every mobile row fills the surface and exposes one editing control set`() {
        MobileHangulLayout.entries.filterNot { it == MobileHangulLayout.Physical }.forEach { layout ->
            val rows = MobileHangulKeyboard.layoutFor(layout)
            val keys = rows.flatten()
            assertTrue("$layout has a non-full row", rows.all { row ->
                val widths = row.map { it.appearance.percentWidth.toDouble() }
                val flexibleKeys = widths.count { it == 0.0 }
                if (flexibleKeys == 0) {
                    kotlin.math.abs(widths.sum() - 1.0) < 0.0001
                } else {
                    flexibleKeys == 1 && widths.sum() < 1.0
                }
            })
            assertEquals("$layout backspace count", 1, keys.count { it is BackspaceKey })
            assertEquals("$layout return count", 1, keys.count { it is ReturnKey })
            assertEquals("$layout space count", 1, keys.count { it is SpaceKey })
            assertEquals("$layout language count", 1, keys.count { it is LanguageKey })
        }
    }

    @Test
    fun `Moakey consonant swipe emits consonant before vowel`() {
        val ieung = MobileHangulKeyboard.layoutFor(MobileHangulLayout.MoakeyOneHand)[2][3]
        val gesture = ieung.behaviors.filterIsInstance<KeyDef.Behavior.Gesture>().single()
        gesture.handler(event(CustomGestureView.GestureType.Down, 0f, 0f))
        gesture.handler(event(CustomGestureView.GestureType.Move, 100f, 0f))
        val action = gesture.handler(event(CustomGestureView.GestureType.Up, 100f, 0f))
            as KeyAction.MobileHangulSequenceAction

        assertEquals(
            listOf(MobileHangulComposer.Token.Jamo('ㅇ'), MobileHangulComposer.Token.Jamo('ㅏ')),
            action.tokens
        )
    }

    @Test
    fun `surface picker is available only for the Dubeolsik engine`() {
        assertTrue(MobileHangulSurfaceSwitcher.isAvailable("Dubeolsik"))
        assertTrue(MobileHangulSurfaceSwitcher.isAvailable("0"))
        assertTrue(!MobileHangulSurfaceSwitcher.isAvailable("Sebeolsik Final"))
        assertTrue(!MobileHangulSurfaceSwitcher.isAvailable(null))
    }

    @Test
    fun `surface picker routes physical and mobile choices to their live keyboards`() {
        assertEquals(
            TextKeyboard.Name,
            MobileHangulSurfaceSwitcher.target(MobileHangulLayout.Physical)
        )
        MobileHangulLayout.entries.filterNot { it == MobileHangulLayout.Physical }.forEach {
            assertEquals(MobileHangulKeyboard.name(it), MobileHangulSurfaceSwitcher.target(it))
        }
    }

    private fun assertRows(actual: List<List<KeyDef>>, expected: List<List<String>>) =
        assertEquals(expected, actual.map(::signatures))

    private fun assertCycle(key: KeyDef, timeout: Long, vararg jamo: Char) {
        val token = tokens(listOf(key)).single() as MobileHangulComposer.Token.Cycle
        assertEquals(jamo.toList(), token.jamo)
        assertEquals(timeout, token.timeoutMillis)
    }

    private fun tokens(keys: List<KeyDef>) = keys.flatMap { key ->
        key.behaviors.mapNotNull { behavior ->
            ((behavior as? KeyDef.Behavior.Press)?.action as? KeyAction.MobileHangulAction)?.token
        }
    }

    private fun signatures(row: List<KeyDef>) = row.map { key ->
        when (key) {
            is BackspaceKey -> "BACKSPACE"
            is ReturnKey -> "RETURN"
            is SpaceKey -> "SPACE"
            is LanguageKey -> "LANGUAGE"
            else -> (key.appearance as? KeyDef.Appearance.Text)?.displayText.orEmpty()
        }
    }

    private fun event(type: CustomGestureView.GestureType, x: Float, y: Float) =
        CustomGestureView.Event(type, false, x, y, 0, 0, 0, 0)
}
