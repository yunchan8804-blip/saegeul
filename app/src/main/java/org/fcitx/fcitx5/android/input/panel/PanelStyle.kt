/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.panel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.annotation.ColorInt
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarLayoutPolicy
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp

/**
 * Design SSOT for the fork's custom IME panels (AI, search, GIF, voice, OCR,
 * phrases, clipboard and friends).
 *
 * Color semantics:
 * - Committing actions (insert, replace, send, unlock) use the accentKey* tokens,
 *   the same meaning as the enter key.
 * - Selection or "active state" emphasis (selected action, featured chip) uses the
 *   genericActive* tokens.
 * - Secondary buttons use keyBackgroundColor/keyTextColor; card and field surfaces
 *   use altKeyBackgroundColor.
 */
internal object PanelStyle {

    // Spacing (4dp grid)
    const val PANEL_PADDING_H_DP = 12
    const val PANEL_PADDING_V_DP = 8
    const val CARD_PADDING_H_DP = 12
    const val CARD_PADDING_V_DP = 8
    const val GAP_XS_DP = 2
    const val GAP_S_DP = 4
    const val GAP_M_DP = 8

    // Shape: one radius for cards, fields and buttons; chips are pills (height / 2)
    const val RADIUS_DP = 12

    // Control heights, three steps only
    const val CHIP_HEIGHT_DP = 36
    const val COMPACT_BUTTON_HEIGHT_DP = 40
    const val BUTTON_HEIGHT_DP = ToolbarLayoutPolicy.TOUCH_TARGET_DP

    // Type scale
    const val TEXT_CAPTION = 11f
    const val TEXT_BODY = 13f
    const val TEXT_EMPHASIS = 15f
    const val TEXT_TITLE = 18f

    // Slots that render glyphs (emoji results) rather than prose
    const val TEXT_GLYPH = 27f

    // State presentation
    const val DISABLED_ALPHA = 0.45f
    const val HINT_ALPHA = 0.65f

    // Transient status banner scrim floating over content. Shared between light and
    // dark themes, following the toast convention.
    const val STATUS_SCRIM_TEXT = 0xFFFFFFFF.toInt()
    const val STATUS_SCRIM_NEUTRAL = 0xCC202124.toInt()
    const val STATUS_SCRIM_ERROR = 0xDD8B1E1E.toInt()

    /** Error text color matched to theme brightness so contrast holds on both. */
    @ColorInt
    fun errorTextColor(theme: Theme): Int =
        if (theme.isDark) ERROR_TEXT_ON_DARK else ERROR_TEXT_ON_LIGHT

    // Shared with non-IME screens that follow the app's day/night theme instead of
    // a keyboard Theme; those pick between the two values themselves.
    const val ERROR_TEXT_ON_DARK = 0xFFE57373.toInt()
    const val ERROR_TEXT_ON_LIGHT = 0xFFC62828.toInt()

    /** Single-color state list that fades to [DISABLED_ALPHA] when disabled. */
    fun stateColors(@ColorInt normal: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(normal.alpha(DISABLED_ALPHA), normal)
    )

    /** Rounded solid surface; radius is in px. */
    fun surface(@ColorInt color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
}

/** Panel button semantics; see [PanelStyle] color rules. */
internal enum class PanelButtonKind {
    /** Committing action: insert, replace, send, unlock. accentKey* tokens. */
    Primary,

    /** Supporting action: cancel, back, retry paths that leave state alone. */
    Secondary
}

/** Rounded surface with the standard panel radius. */
internal fun Context.panelSurface(@ColorInt color: Int): GradientDrawable =
    PanelStyle.surface(color, dp(PanelStyle.RADIUS_DP).toFloat())

/** Pill surface for chips. */
internal fun Context.chipSurface(@ColorInt color: Int): GradientDrawable =
    PanelStyle.surface(color, dp(PanelStyle.CHIP_HEIGHT_DP) / 2f)

/** Clickable surface whose press ripple stays inside the surface shape. */
internal fun Context.pressableSurface(theme: Theme, surface: GradientDrawable): Drawable =
    RippleDrawable(
        ColorStateList.valueOf(theme.keyPressHighlightColor),
        surface,
        surface.constantState?.newDrawable()?.mutate() ?: surface
    )

/** Clickable rounded surface with the standard panel radius. */
internal fun Context.pressablePanelSurface(theme: Theme, @ColorInt color: Int): Drawable =
    pressableSurface(theme, panelSurface(color))

/** Clickable pill surface for chips. */
internal fun Context.pressableChipSurface(theme: Theme, @ColorInt color: Int): Drawable =
    pressableSurface(theme, chipSurface(color))

/**
 * Shared panel button. Keeps the platform button background so the press ripple
 * survives, and carries the disabled state in both text and background tints.
 */
internal fun Context.panelButton(
    theme: Theme,
    kind: PanelButtonKind,
    textSize: Float = PanelStyle.TEXT_BODY,
    horizontalPaddingDp: Int = PanelStyle.CARD_PADDING_H_DP
): Button = Button(this).apply {
    isAllCaps = false
    this.textSize = textSize
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
    applyPanelButtonColors(theme, kind)
}

/** Applies the shared color rules to an existing button. */
internal fun Button.applyPanelButtonColors(theme: Theme, kind: PanelButtonKind) {
    val (background, foreground) = when (kind) {
        PanelButtonKind.Primary -> theme.accentKeyBackgroundColor to theme.accentKeyTextColor
        PanelButtonKind.Secondary -> theme.keyBackgroundColor to theme.keyTextColor
    }
    setTextColor(PanelStyle.stateColors(foreground))
    backgroundTintList = PanelStyle.stateColors(background)
}
