/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

/**
 * Parses and formats theme colors as `#RRGGBB` / `#AARRGGBB` hex strings for
 * the "세부 색" color picker's text field.
 */
object ThemeHexColor {

    private val HEX_DIGITS = ('0'..'9') + ('a'..'f') + ('A'..'F')

    /**
     * Parses [input] as `#RRGGBB` or `#AARRGGBB` (the leading `#` is
     * optional, case is ignored). A 6-digit code is treated as fully opaque.
     * Returns null for anything else, including wrong length or non-hex
     * characters.
     */
    fun parse(input: String): Int? {
        val hex = input.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        if (hex.any { it !in HEX_DIGITS }) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) {
            (0xFF000000L or value).toInt()
        } else {
            value.toInt()
        }
    }

    /** Formats [color] as `#AARRGGBB`. */
    fun format(color: Int): String = "#%08X".format(color)
}
