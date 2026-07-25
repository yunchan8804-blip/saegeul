/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Visible legends for libhangul's standard two-set layouts.
 *
 * Key actions intentionally remain Latin keysyms: fcitx5-hangul/libhangul consumes the
 * physical QWERTY positions and performs the actual Hangul composition.
 */
object HangulKeyLegends {

    const val Addon = "hangul"

    // Yetgeul and every three-set variant have different shifted/physical key maps.
    // Keep an honest Latin fallback until each full layout is implemented.
    private val supportedLayouts = setOf("Dubeolsik")

    private data class Legend(val normal: String, val shifted: String = normal)

    private val dubeolsik = mapOf(
        "Q" to Legend("ㅂ", "ㅃ"),
        "W" to Legend("ㅈ", "ㅉ"),
        "E" to Legend("ㄷ", "ㄸ"),
        "R" to Legend("ㄱ", "ㄲ"),
        "T" to Legend("ㅅ", "ㅆ"),
        "Y" to Legend("ㅛ"),
        "U" to Legend("ㅕ"),
        "I" to Legend("ㅑ"),
        "O" to Legend("ㅐ", "ㅒ"),
        "P" to Legend("ㅔ", "ㅖ"),
        "A" to Legend("ㅁ"),
        "S" to Legend("ㄴ"),
        "D" to Legend("ㅇ"),
        "F" to Legend("ㄹ"),
        "G" to Legend("ㅎ"),
        "H" to Legend("ㅗ"),
        "J" to Legend("ㅓ"),
        "K" to Legend("ㅏ"),
        "L" to Legend("ㅣ"),
        "Z" to Legend("ㅋ"),
        "X" to Legend("ㅌ"),
        "C" to Legend("ㅊ"),
        "V" to Legend("ㅍ"),
        "B" to Legend("ㅠ"),
        "N" to Legend("ㅜ"),
        "M" to Legend("ㅡ")
    )

    fun isHangulInputMethod(addon: String, languageCode: String): Boolean =
        addon == Addon && languageCode.substringBefore('-').substringBefore('_') == "ko"

    fun legend(key: String, shifted: Boolean, layout: String?): String? {
        if (layout !in supportedLayouts) return null
        val legend = dubeolsik[key.uppercase()] ?: return null
        return if (shifted) legend.shifted else legend.normal
    }
}
