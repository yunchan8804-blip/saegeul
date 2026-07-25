/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Visible key legends backed by the same built-in tables as libhangul. */
object HangulKeyLegends {

    const val Addon = "hangul"

    val supportedLayouts: Set<String> = HangulKeyboardTables.byLayout.keys - "Romaja"

    val fullSurfaceLayouts = setOf(
        "Sebeolsik 390",
        "Sebeolsik Final",
        "Sebeolsik Noshift",
        "Sebeolsik Yetgeul",
        "Sebeolsik Dubeol Layout",
        "Ahnmatae"
    )

    private val shiftedAscii = mapOf(
        '`' to '~', '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')', '-' to '_',
        '=' to '+', '[' to '{', ']' to '}', '\\' to '|', ';' to ':', '\'' to '"',
        ',' to '<', '.' to '>', '/' to '?'
    )

    // Unicode conjoining jamo do not render well as isolated Android key labels.
    private val compatibilityJamo = mapOf(
        0x1100 to "ㄱ", 0x1101 to "ㄲ", 0x1102 to "ㄴ", 0x1103 to "ㄷ",
        0x1104 to "ㄸ", 0x1105 to "ㄹ", 0x1106 to "ㅁ", 0x1107 to "ㅂ",
        0x1108 to "ㅃ", 0x1109 to "ㅅ", 0x110a to "ㅆ", 0x110b to "ㅇ",
        0x110c to "ㅈ", 0x110d to "ㅉ", 0x110e to "ㅊ", 0x110f to "ㅋ",
        0x1110 to "ㅌ", 0x1111 to "ㅍ", 0x1112 to "ㅎ",
        0x1161 to "ㅏ", 0x1162 to "ㅐ", 0x1163 to "ㅑ", 0x1164 to "ㅒ",
        0x1165 to "ㅓ", 0x1166 to "ㅔ", 0x1167 to "ㅕ", 0x1168 to "ㅖ",
        0x1169 to "ㅗ", 0x116a to "ㅘ", 0x116b to "ㅙ", 0x116c to "ㅚ",
        0x116d to "ㅛ", 0x116e to "ㅜ", 0x116f to "ㅝ", 0x1170 to "ㅞ",
        0x1171 to "ㅟ", 0x1172 to "ㅠ", 0x1173 to "ㅡ", 0x1174 to "ㅢ",
        0x1175 to "ㅣ",
        0x11a8 to "ㄱ", 0x11a9 to "ㄲ", 0x11aa to "ㄳ", 0x11ab to "ㄴ",
        0x11ac to "ㄵ", 0x11ad to "ㄶ", 0x11ae to "ㄷ", 0x11af to "ㄹ",
        0x11b0 to "ㄺ", 0x11b1 to "ㄻ", 0x11b2 to "ㄼ", 0x11b3 to "ㄽ",
        0x11b4 to "ㄾ", 0x11b5 to "ㄿ", 0x11b6 to "ㅀ", 0x11b7 to "ㅁ",
        0x11b8 to "ㅂ", 0x11b9 to "ㅄ", 0x11ba to "ㅅ", 0x11bb to "ㅆ",
        0x11bc to "ㅇ", 0x11bd to "ㅈ", 0x11be to "ㅊ", 0x11bf to "ㅋ",
        0x11c0 to "ㅌ", 0x11c1 to "ㅍ", 0x11c2 to "ㅎ"
    )

    fun isHangulInputMethod(addon: String, languageCode: String): Boolean =
        addon == Addon && languageCode.substringBefore('-').substringBefore('_') == "ko"

    fun legend(key: String, shifted: Boolean, layout: String?): String? {
        if (layout !in supportedLayouts || key.length != 1) return null
        val ascii = actionCharacter(key[0], shifted)
        val codepoint = HangulKeyboardTables.byLayout[layout]?.getOrNull(ascii.code) ?: return null
        if (codepoint == 0) return null
        return compatibilityJamo[codepoint] ?: String(Character.toChars(codepoint))
    }

    internal fun actionCharacter(key: Char, shifted: Boolean): Char = when {
        !shifted -> key.lowercaseChar()
        key.isLetter() -> key.uppercaseChar()
        else -> shiftedAscii[key] ?: key
    }
}
