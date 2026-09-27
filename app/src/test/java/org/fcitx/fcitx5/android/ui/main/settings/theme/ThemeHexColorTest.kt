/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeHexColorTest {

    @Test
    fun sixDigitHexParsesAsFullyOpaque() {
        assertEquals(0xFF101827.toInt(), ThemeHexColor.parse("#101827"))
        assertEquals(0xFF101827.toInt(), ThemeHexColor.parse("101827"))
    }

    @Test
    fun eightDigitHexParsesWithItsOwnAlpha() {
        assertEquals(0x80101827.toInt(), ThemeHexColor.parse("#80101827"))
    }

    @Test
    fun parseIsCaseInsensitive() {
        assertEquals(ThemeHexColor.parse("#AaBbCc"), ThemeHexColor.parse("#aabbcc"))
    }

    @Test
    fun parseRejectsWrongLength() {
        assertNull(ThemeHexColor.parse("#FFF"))
        assertNull(ThemeHexColor.parse("#1234567"))
        assertNull(ThemeHexColor.parse(""))
    }

    @Test
    fun parseRejectsNonHexCharacters() {
        assertNull(ThemeHexColor.parse("#GGGGGG"))
        assertNull(ThemeHexColor.parse("#10182Z"))
    }

    @Test
    fun formatRoundTripsThroughParse() {
        val color = 0x80101827.toInt()
        assertEquals(color, ThemeHexColor.parse(ThemeHexColor.format(color)))
    }
}
