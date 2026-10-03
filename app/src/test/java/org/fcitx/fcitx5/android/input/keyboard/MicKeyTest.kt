/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicKeyTest {
    private val tap = KeyAction.VoiceInputAction(KeyAction.VoiceInputAction.Phase.Tap)
    private val holdStart = KeyAction.VoiceInputAction(KeyAction.VoiceInputAction.Phase.HoldStart)
    private val holdEnd = KeyAction.VoiceInputAction(KeyAction.VoiceInputAction.Phase.HoldEnd)

    private val eighth = 0.125f
    private val quarter = 0.25f
    private val side = 0.16f

    @Test
    fun `mic key taps into dictation and holds into push to talk`() {
        val key = MicKey()

        val press = key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        val hold = key.behaviors.filterIsInstance<KeyDef.Behavior.Hold>().single()

        assertEquals(tap, press.action)
        assertEquals(holdStart, hold.start)
        assertEquals(holdEnd, hold.end)
        assertEquals(2, key.behaviors.size)
    }

    @Test
    fun `mic key opens no preview and no long press menu`() {
        assertNull(MicKey().popup)
    }

    @Test
    fun `mic key looks like the language key beside it`() {
        val mic = MicKey().appearance as KeyDef.Appearance.Image
        val lang = LanguageKey().appearance as KeyDef.Appearance.Image

        assertEquals(R.drawable.ic_baseline_keyboard_voice_24, mic.src)
        assertEquals(R.id.button_mic, mic.viewId)
        assertEquals(lang.variant, mic.variant)
        assertEquals(lang.percentWidth, mic.percentWidth, 0f)
        assertEquals(0.1f, mic.percentWidth, 0f)
    }

    @Test
    fun `hold ends only for a hold that started`() {
        val hold = HoldGesture(MicKey().behaviors.filterIsInstance<KeyDef.Behavior.Hold>().single())

        assertNull("a tap never started a hold", hold.onRelease())
        assertEquals(holdStart, hold.onLongPress())
        assertEquals(holdEnd, hold.onRelease())
        assertNull("the end is sent once", hold.onRelease())
    }

    @Test
    fun `every hold starts fresh`() {
        val hold = HoldGesture(MicKey().behaviors.filterIsInstance<KeyDef.Behavior.Hold>().single())

        hold.onLongPress()
        hold.onRelease()

        assertNull(hold.onRelease())
        hold.onLongPress()
        assertEquals(holdEnd, hold.onRelease())
    }

    @Test
    fun `period comma key taps a period and swipes up into a comma`() {
        val key = PeriodCommaKey()
        val appearance = key.appearance as KeyDef.Appearance.AltText

        assertEquals(".", appearance.displayText)
        assertEquals(",", appearance.altText)
        assertEquals(
            KeyAction.FcitxKeyAction("."),
            key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        )
        assertEquals(
            KeyAction.FcitxKeyAction(","),
            key.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action
        )
        assertEquals(2, key.behaviors.size)
    }

    @Test
    fun `period comma key long press offers comma question exclamation tilde and ellipsis`() {
        val popups = PeriodCommaKey().popup!!.toList()

        val preview = popups.filterIsInstance<KeyDef.Popup.AltPreview>().single()
        assertEquals(".", preview.content)
        assertEquals(",", preview.alternative)
        val keyboard = popups.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        assertEquals(listOf(",", "?", "!", "~", "…"), keyboard.items.toList())
    }

    @Test
    fun `period comma key sends the plain key action the ordinary period key sends`() {
        val ordinaryPeriod = MobileHangulKeyboard.layoutFor(MobileHangulLayout.Danmoum, showMicKey = false)
            .last().first { label(it) == "." }
        val ordinary = ordinaryPeriod.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action

        assertEquals(
            ordinary,
            PeriodCommaKey().behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        )
        assertTrue(ordinary is KeyAction.FcitxKeyAction)
    }

    @Test
    fun `question 123 opens the editor tools menu the comma key used to open`() {
        val on = TextKeyboard.bottomRow(showMicKey = true).filterIsInstance<LayoutSwitchKey>().single()
        val menu = on.popup!!.filterIsInstance<KeyDef.Popup.Menu>().single()

        assertEquals(
            listOf(
                KeyAction.PickerSwitchAction(),
                KeyAction.QuickPhraseAction,
                KeyAction.UnicodeAction
            ),
            menu.items.map { it.action }
        )
        assertEquals(
            KeyAction.LayoutSwitchAction(""),
            on.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        )

        val off = TextKeyboard.bottomRow(showMicKey = false)
        assertNull((off.first() as LayoutSwitchKey).popup)
        val comma = off.filterIsInstance<CommaKey>().single()
        assertEquals(
            menu.items.map { it.label to it.action },
            comma.popup!!.filterIsInstance<KeyDef.Popup.Menu>().single().items
                .map { it.label to it.action }
        )
    }

    private fun sig(key: KeyDef): String = when (key) {
        is LayoutSwitchKey -> "?123"
        is CommaKey -> "COMMA"
        is LanguageKey -> "LANG"
        is MicKey -> "MIC"
        is SpaceKey -> "SPACE"
        is PeriodCommaKey -> "PC"
        is ReturnKey -> "RETURN"
        is BackspaceKey -> "BKSP"
        else -> label(key).orEmpty()
    }

    private fun label(key: KeyDef) = (key.appearance as? KeyDef.Appearance.Text)?.displayText

    /** Kind and width per key; the flexible space has width 0. */
    private fun assertRow(row: List<KeyDef>, vararg expected: Pair<String, Float>) {
        assertEquals(expected.map { it.first }, row.map(::sig))
        assertEquals(expected.map { it.second }, row.map { it.appearance.percentWidth })
    }

    private fun spaceWidth(row: List<KeyDef>): Double =
        1.0 - row.sumOf { it.appearance.percentWidth.toDouble() }

    private fun bottom(layout: MobileHangulLayout, showMicKey: Boolean) =
        MobileHangulKeyboard.layoutFor(layout, showMicKey = showMicKey).last()

    @Test
    fun `text keyboard bottom row with the mic key keeps the space as wide as before`() {
        val on = TextKeyboard.bottomRow(true)
        val off = TextKeyboard.bottomRow(false)

        assertRow(
            on,
            "MIC" to 0.1f, "?123" to 0.15f, "LANG" to 0.1f, "SPACE" to 0f, "PC" to 0.1f,
            "RETURN" to 0.15f
        )
        assertRow(
            off,
            "?123" to 0.15f, "COMMA" to 0.1f, "LANG" to 0.1f, "SPACE" to 0f, "." to 0.1f,
            "RETURN" to 0.15f
        )
        assertEquals(spaceWidth(off), spaceWidth(on), 0.0001)
        assertEquals(0.4, spaceWidth(on), 0.0001)
        assertEquals("the split falls after mic, ?123 and language", 3, on.indexOfFirst { it is SpaceKey })
        assertEquals("and after the comma and language keys without it", 3, off.indexOfFirst { it is SpaceKey })
    }

    @Test
    fun `full hangul bottom row`() {
        val on = HangulKeyboard.layoutFor(true).last()
        val off = HangulKeyboard.layoutFor(false).last()

        assertRow(on, "MIC" to 0.1f, "?123" to 0.15f, "LANG" to 0.1f, "SPACE" to 0f, "RETURN" to 0.15f)
        assertRow(off, "?123" to 0.15f, "LANG" to 0.1f, "SPACE" to 0f, "RETURN" to 0.15f)
        assertEquals(0.5, spaceWidth(on), 0.0001)
        assertEquals(0.6, spaceWidth(off), 0.0001)
        assertEquals(3, HangulKeyboard.bottomRowSplitBoundary(on))
        assertTrue(on[3] is SpaceKey)
        assertNull("without the key the default split applies", HangulKeyboard.bottomRowSplitBoundary(off))
        assertEquals(
            HangulKeyboard.layoutFor(true).dropLast(1),
            HangulKeyboard.layoutFor(false).dropLast(1)
        )
    }

    @Test
    fun `danmoum bottom row`() {
        val on = bottom(MobileHangulLayout.Danmoum, true)
        val off = bottom(MobileHangulLayout.Danmoum, false)

        assertRow(
            on,
            "MIC" to 0.1f, "?123" to 0.13f, "LANG" to 0.1f, "SPACE" to 0f, "PC" to 0.1f,
            "RETURN" to 0.15f
        )
        assertRow(
            off,
            "?123" to 0.13f, "LANG" to 0.1f, "," to 0.1f, "SPACE" to 0f, "." to 0.1f,
            "RETURN" to 0.15f
        )
        assertEquals(spaceWidth(off), spaceWidth(on), 0.0001)
    }

    @Test
    fun `moakey two hand merges comma and question period exclamation into the period comma key`() {
        val on = bottom(MobileHangulLayout.MoakeyTwoHand, true)
        val off = bottom(MobileHangulLayout.MoakeyTwoHand, false)

        assertRow(
            on,
            "MIC" to 0.1f, "?123" to 0.13f, "LANG" to 0.1f, "SPACE" to 0f, "PC" to 0.1f,
            "RETURN" to 0.15f
        )
        assertRow(
            off,
            "?123" to 0.13f, "LANG" to 0.1f, "," to 0.08f, "SPACE" to 0f, "?.!" to 0.1f,
            "RETURN" to 0.15f
        )
        assertEquals(0.42, spaceWidth(on), 0.0001)
        assertEquals(0.44, spaceWidth(off), 0.0001)
    }

    @Test
    fun `moakey one hand turns the comma key into the period comma key before the space`() {
        val on = bottom(MobileHangulLayout.MoakeyOneHand, true)
        val off = bottom(MobileHangulLayout.MoakeyOneHand, false)

        assertRow(
            on,
            "MIC" to 0.1f, "?123" to 0.13f, "LANG" to 0.1f, "PC" to 0.1f, "SPACE" to 0f,
            "ㆍ ㅣ ㅡ" to 0.16f, "RETURN" to 0.15f
        )
        assertRow(
            off,
            "?123" to 0.13f, "LANG" to 0.1f, "," to 0.08f, "SPACE" to 0f, "ㆍ ㅣ ㅡ" to 0.16f,
            "RETURN" to 0.15f
        )
        assertEquals(0.26, spaceWidth(on), 0.0001)
        assertEquals(0.38, spaceWidth(off), 0.0001)
    }

    @Test
    fun `chunjiin gives the comma keys place to the mic key`() {
        assertRow(
            bottom(MobileHangulLayout.Chunjiin, true),
            "?123" to eighth, "LANG" to eighth, "ㅇㅁ" to quarter, "SPACE" to quarter, "MIC" to quarter
        )
        assertRow(
            bottom(MobileHangulLayout.Chunjiin, false),
            "?123" to eighth, "LANG" to eighth, "ㅇㅁ" to quarter, "SPACE" to quarter, "," to quarter
        )
        assertRow(
            bottom(MobileHangulLayout.ChunjiinPlus, true),
            "?123" to eighth, "LANG" to eighth, "ㅇ" to eighth, "ㅁ" to eighth, "SPACE" to quarter,
            "MIC" to quarter
        )
        assertRow(
            bottom(MobileHangulLayout.ChunjiinPlus, false),
            "?123" to eighth, "LANG" to eighth, "ㅇ" to eighth, "ㅁ" to eighth, "SPACE" to quarter,
            "," to quarter
        )
    }

    @Test
    fun `chunjiin keeps a key that types the comma once its comma key is gone`() {
        listOf(MobileHangulLayout.Chunjiin, MobileHangulLayout.ChunjiinPlus).forEach { layout ->
            val rows = MobileHangulKeyboard.layoutFor(layout, showMicKey = true)
            val cycles = rows.flatten().flatMap { it.behaviors }
                .filterIsInstance<KeyDef.Behavior.Press>()
                .mapNotNull { (it.action as? KeyAction.MobileHangulAction)?.token }
                .filterIsInstance<MobileHangulComposer.Token.SymbolCycle>()
            assertTrue("$layout", cycles.any { ',' in it.symbols })
        }
    }

    @Test
    fun `vega and naratgul full rows put the mic beside a halved backspace`() {
        listOf(MobileHangulLayout.Vega, MobileHangulLayout.Naratgul).forEach { layout ->
            val on = MobileHangulKeyboard.layoutFor(layout, showMicKey = true).first()
            val off = MobileHangulKeyboard.layoutFor(layout, showMicKey = false).first()

            assertEquals(
                "$layout",
                listOf(quarter, quarter, quarter, eighth, eighth),
                on.map { it.appearance.percentWidth }
            )
            assertTrue("$layout", on[3] is MicKey)
            assertTrue("$layout", on[4] is BackspaceKey)
            assertEquals(
                "$layout",
                listOf(quarter, quarter, quarter, quarter),
                off.map { it.appearance.percentWidth }
            )
            assertTrue("$layout", off[3] is BackspaceKey)
        }
    }

    @Test
    fun `vega and naratgul center rows put the mic on the comma and the period comma key at the corner`() {
        listOf(MobileHangulLayout.VegaCenter, MobileHangulLayout.NaratgulCenter).forEach { layout ->
            val on = MobileHangulKeyboard.layoutFor(layout, showMicKey = true)
            val off = MobileHangulKeyboard.layoutFor(layout, showMicKey = false)

            assertTrue("$layout", on[1].first() is MicKey)
            assertEquals("$layout", side, on[1].first().appearance.percentWidth, 0f)
            assertTrue("$layout", on[3].last() is PeriodCommaKey)
            assertEquals("$layout", side, on[3].last().appearance.percentWidth, 0f)

            assertEquals("$layout", ",", label(off[1].first()))
            assertEquals("$layout", ".", label(off[3].last()))
            assertFalse("$layout", off.flatten().any { it is MicKey || it is PeriodCommaKey })
        }
    }

    @Test
    fun `every mobile surface fills its rows in both modes`() {
        MobileHangulLayout.entries.filterNot { it == MobileHangulLayout.Physical }.forEach { layout ->
            listOf(true, false).forEach { show ->
                MobileHangulKeyboard.layoutFor(layout, showMicKey = show).forEach { row ->
                    val widths = row.map { it.appearance.percentWidth.toDouble() }
                    val flexible = widths.count { it == 0.0 }
                    if (flexible == 0) {
                        assertEquals("$layout $show", 1.0, widths.sum(), 0.0001)
                    } else {
                        assertEquals("$layout $show", 1, flexible)
                        assertTrue("$layout $show", widths.sum() < 1.0)
                    }
                }
            }
        }
    }

    @Test
    fun `mic key sits on each of the eleven text surfaces once and on none without the setting`() {
        val mobile = MobileHangulLayout.entries.filterNot { it == MobileHangulLayout.Physical }
        val with = listOf(listOf(TextKeyboard.bottomRow(true)), HangulKeyboard.layoutFor(true)) +
            mobile.map { MobileHangulKeyboard.layoutFor(it, showMicKey = true) }
        assertEquals(11, with.size)
        with.forEach { rows -> assertEquals(1, rows.flatten().count { it is MicKey }) }

        val without = listOf(listOf(TextKeyboard.bottomRow(false)), HangulKeyboard.layoutFor(false)) +
            mobile.map { MobileHangulKeyboard.layoutFor(it, showMicKey = false) }
        without.forEach { rows ->
            assertFalse(rows.flatten().any { it is MicKey || it is PeriodCommaKey })
        }
    }
}
