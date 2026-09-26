/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetPopupLegendsTest {

    // In Hangul mode the Shift jamo entries never depend on caps state, so these cases pass a
    // caps-none casedChar; it is unused unless the layout has no jamo mapping for the key.
    private fun resolveHangul(rawChar: Char, altLegend: String?) =
        AlphabetPopupLegends.resolve(
            rawChar, rawChar.lowercaseChar(), altLegend, hangulActive = true, hangulLayout = "Dubeolsik"
        )

    /**
     * What the popup actually ends up showing: [AlphabetPopupLegends.resolve]'s keys, or — when
     * it returns null — the default preset looked up by [casedChar], exactly as
     * `TextKeyboard.resolveShowKeyboardAction` falls back.
     */
    private fun finalPopupKeys(
        rawChar: Char,
        casedChar: Char,
        altLegend: String?,
        hangulActive: Boolean = false,
        hangulLayout: String? = null
    ): Array<String> {
        val entries = AlphabetPopupLegends.resolve(rawChar, casedChar, altLegend, hangulActive, hangulLayout)
        return entries?.keys ?: PopupPreset.getValue(casedChar.toString())
    }

    @Test
    fun `hangul mode offers the alt legend and shift jamo when they differ from the base jamo`() {
        // physical q/w/e/r/t keys: base jamo ㅂ/ㅈ/ㄷ/ㄱ/ㅅ, shift jamo ㅃ/ㅉ/ㄸ/ㄲ/ㅆ
        data class Case(val rawChar: Char, val altLegend: String, val keys: Array<String>, val labels: Array<String>)
        val cases = listOf(
            Case('Q', "1", arrayOf("1", "Q"), arrayOf("1", "ㅃ")),
            Case('W', "2", arrayOf("2", "W"), arrayOf("2", "ㅉ")),
            Case('E', "3", arrayOf("3", "E"), arrayOf("3", "ㄸ")),
            Case('R', "4", arrayOf("4", "R"), arrayOf("4", "ㄲ")),
            Case('T', "5", arrayOf("5", "T"), arrayOf("5", "ㅆ")),
        )
        cases.forEach { case ->
            val entries = resolveHangul(case.rawChar, case.altLegend)
            assertArrayEquals("keys for ${case.rawChar}", case.keys, entries?.keys)
            assertArrayEquals("labels for ${case.rawChar}", case.labels, entries?.labels)
        }
    }

    @Test
    fun `hangul mode offers ae and e keys their shift jamo`() {
        // physical o key: base ㅐ, shift ㅒ. physical p key: base ㅔ, shift ㅖ.
        val o = resolveHangul('O', "9")
        assertArrayEquals(arrayOf("9", "O"), o?.keys)
        assertArrayEquals(arrayOf("9", "ㅒ"), o?.labels)

        val p = resolveHangul('P', "0")
        assertArrayEquals(arrayOf("0", "P"), p?.keys)
        assertArrayEquals(arrayOf("0", "ㅖ"), p?.labels)
    }

    @Test
    fun `hangul mode drops the shift entry when it matches the base jamo`() {
        // physical a key: both plain and Shift produce ㅁ, so the popup is just the alt legend.
        val entries = resolveHangul('A', "@")
        assertArrayEquals(arrayOf("@"), entries?.keys)
        assertArrayEquals(arrayOf("@"), entries?.labels)
    }

    @Test
    fun `hangul mode never displays latin case variants or accents`() {
        // the shift entry's key is intentionally the Latin letter fcitx composes the jamo from
        // (design rule 3); only what is displayed must stay free of Latin letters and accents.
        val entries = resolveHangul('Q', "1")
        entries!!.labels!!.forEach { assertNoLatinLetter(it) }
    }

    private fun assertNoLatinLetter(s: String) {
        assert(s.none { it in 'A'..'Z' || it in 'a'..'z' }) { "unexpected latin letter in '$s'" }
    }

    @Test
    fun `latin mode with a pinned number row corrects the first entry to match the key's alt legend`() {
        // caps none: looked up by the case-correct, lower-case "q" preset (["1", "Q"]); only
        // index 0 changes.
        val entries = AlphabetPopupLegends.resolve(
            'Q', casedChar = 'q', altLegend = "%", hangulActive = false, hangulLayout = null
        )
        assertArrayEquals(arrayOf("%", "Q"), entries?.keys)
        assertNull("labels should be null so the caller derives them via the usual pipeline", entries?.labels)
    }

    @Test
    fun `latin mode keeps the default preset untouched when the alt legend already matches`() {
        // physical e key's alt legend is already "3", matching PopupPreset["e"][0]; the caller
        // should fall back to the unmodified preset, keeping its accented entries.
        val entries = AlphabetPopupLegends.resolve(
            'E', casedChar = 'e', altLegend = "3", hangulActive = false, hangulLayout = null
        )
        assertNull(entries)
    }

    @Test
    fun `unknown or unsupported hangul layout falls back to the latin popup`() {
        val entries = AlphabetPopupLegends.resolve(
            'Q', casedChar = 'q', altLegend = "%", hangulActive = true, hangulLayout = "Romaja"
        )
        assertArrayEquals(arrayOf("%", "Q"), entries?.keys)
        assertNull(entries?.labels)
    }

    // Regression: the "E" popup label TextKeyboard receives from KeyDefPreset is always the
    // constant upper-case letter, never the currently displayed case. The Latin fallback (when
    // resolve() itself makes no change) must still be looked up by the caps-cased letter, or a
    // lower-case "e" long-press would show the upper-case set's accents (and vice versa).

    @Test
    fun `caps-none e popup uses the lower-case preset, not the upper-case one`() {
        val keys = finalPopupKeys(rawChar = 'E', casedChar = 'e', altLegend = "3")
        assertEquals("E", keys[1])
        assertTrue("expected \"ê\" (lower-case accent) among $keys", keys.contains("ê"))
        assertFalse("did not expect \"Ê\" (upper-case accent) among $keys", keys.contains("Ê"))
    }

    @Test
    fun `caps-once e popup uses the upper-case preset, not the lower-case one`() {
        val keys = finalPopupKeys(rawChar = 'E', casedChar = 'E', altLegend = "3")
        assertEquals("e", keys[1])
        assertTrue("expected \"Ê\" (upper-case accent) among $keys", keys.contains("Ê"))
        assertFalse("did not expect \"ê\" (lower-case accent) among $keys", keys.contains("ê"))
    }

    @Test
    fun `pinned number row with caps none only overrides q's first entry`() {
        // PopupPreset["q"] is ["1", "Q"]; only index 0 ("1" -> "%") should change.
        val keys = finalPopupKeys(rawChar = 'Q', casedChar = 'q', altLegend = "%")
        assertArrayEquals(arrayOf("%", "Q"), keys)
    }
}
