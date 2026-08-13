/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * A digit key for the row pinned above the letter surfaces. Long press exposes the same
 * superscript/fraction presets the symbol picker uses.
 */
class PinnedNumberKey(val digit: String) : KeyDef(
    Appearance.Text(
        displayText = digit,
        textSize = 20f,
        percentWidth = 0.1f
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(digit.codePointAt(0))))
    ),
    arrayOf(
        Popup.Preview(digit),
        Popup.Keyboard.Preset(digit)
    )
)

/**
 * The always-visible digit row. This is part of the keyboard layout itself, unlike the toolbar
 * number row shown for password fields, which is a transient bar surface that candidates and
 * clipboard suggestions can replace.
 *
 * Layouts are built once per keyboard instance, so a preference change must recreate the whole
 * InputView — see `recreateInputViewPrefs` in FcitxInputMethodService.
 */
object PinnedNumberRow {

    /** Ten keys wide, so it splits at the same column as the letter rows above the fold. */
    const val SPLIT_BOUNDARY = 5

    /**
     * Letter surfaces are four rows tall; pinning a fifth needs proportionally more height,
     * otherwise every key shrinks to keep the keyboard the same size.
     */
    private const val HEIGHT_NUMERATOR = 5
    private const val HEIGHT_DENOMINATOR = 4

    val Keys: List<KeyDef> = "1234567890".map { PinnedNumberKey(it.toString()) }

    fun isEnabled(): Boolean = AppPrefs.getInstance().keyboard.showNumberRow.getValue()

    fun prependTo(
        layout: List<List<KeyDef>>,
        enabled: Boolean = isEnabled()
    ): List<List<KeyDef>> = if (enabled) listOf(Keys) + layout else layout

    /**
     * Scales a keyboard height percentage to make room for the extra row, clamped to the same
     * upper bound the height preference itself allows.
     */
    fun scaleHeightPercent(percent: Int, maxPercent: Int, enabled: Boolean = isEnabled()): Int =
        if (enabled) {
            (percent * HEIGHT_NUMERATOR / HEIGHT_DENOMINATOR).coerceAtMost(maxPercent)
        } else {
            percent
        }
}
