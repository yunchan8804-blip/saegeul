/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Applies a color palette to a [Theme.Custom] without touching anything else.
 *
 * Presets, brightness/key-tone switches and seed-color generation all need to
 * replace only the theme's 21 color fields (plus the overall [Theme.isDark]
 * flag, which describes that same palette). They must never reset the user's
 * lighting/particle/glow effects, per-key overrides, global key style or
 * background image — see `app/design.md` round 2 decision ("테마") and the
 * before-audit item 5 this fixes.
 */
object ThemeColorApply {

    /**
     * Returns a copy of [target] whose 21 color fields (and [Theme.isDark])
     * come from [colorsFrom], while [target]'s name, background image, key
     * overrides, global key style and effects are preserved untouched.
     */
    fun replaceColors(target: Theme.Custom, colorsFrom: Theme): Theme.Custom = target.copy(
        isDark = colorsFrom.isDark,
        backgroundColor = colorsFrom.backgroundColor,
        barColor = colorsFrom.barColor,
        keyboardColor = colorsFrom.keyboardColor,
        keyBackgroundColor = colorsFrom.keyBackgroundColor,
        keyTextColor = colorsFrom.keyTextColor,
        candidateTextColor = colorsFrom.candidateTextColor,
        candidateLabelColor = colorsFrom.candidateLabelColor,
        candidateCommentColor = colorsFrom.candidateCommentColor,
        altKeyBackgroundColor = colorsFrom.altKeyBackgroundColor,
        altKeyTextColor = colorsFrom.altKeyTextColor,
        accentKeyBackgroundColor = colorsFrom.accentKeyBackgroundColor,
        accentKeyTextColor = colorsFrom.accentKeyTextColor,
        keyPressHighlightColor = colorsFrom.keyPressHighlightColor,
        keyShadowColor = colorsFrom.keyShadowColor,
        popupBackgroundColor = colorsFrom.popupBackgroundColor,
        popupTextColor = colorsFrom.popupTextColor,
        spaceBarColor = colorsFrom.spaceBarColor,
        dividerColor = colorsFrom.dividerColor,
        clipboardEntryColor = colorsFrom.clipboardEntryColor,
        genericActiveBackgroundColor = colorsFrom.genericActiveBackgroundColor,
        genericActiveForegroundColor = colorsFrom.genericActiveForegroundColor
    )
}
