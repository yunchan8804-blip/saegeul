/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.keyboard.HangulKeyLegends

/**
 * Decides what a [org.fcitx.fcitx5.android.input.keyboard.TextKeyboard] alphabet key's
 * long-press popup shows and commits.
 *
 * The popup's first entry always matches the alt legend already printed under the key (the
 * swipe/long-press hint). In Hangul input it also offers the key's Shift jamo, when that jamo
 * differs from the unshifted one fcitx5-hangul would otherwise produce for this physical key.
 * Latin case variants and accented letters never appear while typing Hangul.
 */
object AlphabetPopupLegends {

    class Entries(val keys: Array<String>, val labels: Array<String>?)

    /**
     * @param rawChar the key's constant Latin letter, as stored on
     * [org.fcitx.fcitx5.android.input.keyboard.KeyDefPreset.AlphabetKey] (always upper-case).
     * Identifies the physical key for the Hangul jamo lookup and is what the Shift jamo entry
     * sends, independent of the keyboard's current caps state.
     * @param casedChar [rawChar] with the keyboard's current caps state applied (lower-case when
     * not shifted, upper-case for Shift-once/Caps-lock) — the [PopupPreset] table has separate
     * entries per case (accents differ), so the Latin popup must look itself up by this, not by
     * [rawChar].
     * @param altLegend the alt legend currently printed under this key (its swipe symbol, or a
     * pinned number-row digit/symbol), or null when it could not be determined
     * @param hangulActive whether the active input method is fcitx5-hangul
     * @param hangulLayout the active Hangul keyboard layout name, or null
     * @return the popup's keys/labels, or null when the caller should fall back to the default
     * preset popup looked up by [casedChar]
     */
    fun resolve(
        rawChar: Char,
        casedChar: Char,
        altLegend: String?,
        hangulActive: Boolean,
        hangulLayout: String?
    ): Entries? {
        if (hangulActive) {
            val baseJamo = HangulKeyLegends.legend(rawChar.toString(), false, hangulLayout)
            if (baseJamo != null) {
                val shiftJamo = HangulKeyLegends.legend(rawChar.toString(), true, hangulLayout)
                val keys = ArrayList<String>(2)
                val labels = ArrayList<String>(2)
                if (altLegend != null) {
                    keys.add(altLegend)
                    labels.add(altLegend)
                }
                if (shiftJamo != null && shiftJamo != baseJamo) {
                    // fcitx5-hangul composes the Shift jamo from the same Latin key press it
                    // already handles for the letter itself, so send the upper-case letter and
                    // only override how it is displayed.
                    keys.add(rawChar.uppercaseChar().toString())
                    labels.add(shiftJamo)
                }
                if (keys.isEmpty()) return null
                return Entries(keys.toTypedArray(), labels.toTypedArray())
            }
            // this key has no jamo mapping in the current layout: fall back to the Latin popup
        }
        if (altLegend == null) return null
        val preset = PopupPreset[casedChar.toString()] ?: return null
        if (preset.isEmpty() || preset[0] == altLegend) return null
        val keys = preset.copyOf()
        keys[0] = altLegend
        // leave labels null: let the caller derive them from `keys` through the usual
        // punctuation-transform pipeline, preserving that behavior for the accented entries.
        return Entries(keys, null)
    }
}
