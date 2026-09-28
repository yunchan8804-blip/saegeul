/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.IntPreferenceSpec
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.data.prefs.TwinIntPreferenceSpec
import org.fcitx.fcitx5.android.data.prefs.TwinIntSide

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    val keyBorder = switch(R.string.key_border, "key_border", false)

    val keyBorderStroke = switch(
        R.string.key_border_stroke, "key_border_stroke", false,
        enableUiOn = { keyBorder.getValue() }
    )

    val keyRippleEffect = switch(R.string.key_ripple_effect, "key_ripple_effect", false)

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            TwinIntPreferenceSpec(
                title = R.string.key_horizontal_margin,
                primary = TwinIntSide(R.string.portrait, "key_horizontal_margin", 3),
                secondary = TwinIntSide(R.string.landscape, "key_horizontal_margin_landscape", 3),
                min = 0,
                max = 24,
                unit = "dp"
            )
        )
        keyHorizontalMargin = primary
        keyHorizontalMarginLandscape = secondary
    }

    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            TwinIntPreferenceSpec(
                title = R.string.key_vertical_margin,
                primary = TwinIntSide(R.string.portrait, "key_vertical_margin", 7),
                secondary = TwinIntSide(R.string.landscape, "key_vertical_margin_landscape", 4),
                min = 0,
                max = 24,
                unit = "dp"
            )
        )
        keyVerticalMargin = primary
        keyVerticalMarginLandscape = secondary
    }

    val keyRadius = int(
        IntPreferenceSpec(
            title = R.string.key_radius,
            key = "key_radius",
            defaultValue = 4,
            min = 0,
            max = 48,
            unit = "dp"
        )
    )

    val textEditingButtonRadius = int(
        IntPreferenceSpec(
            title = R.string.text_editing_button_radius,
            key = "text_editing_button_radius",
            defaultValue = 8,
            min = 0,
            max = 48,
            unit = "dp"
        )
    )

    val clipboardEntryRadius = int(
        IntPreferenceSpec(
            title = R.string.clipboard_entry_radius,
            key = "clipboard_entry_radius",
            defaultValue = 2,
            min = 0,
            max = 48,
            unit = "dp"
        )
    )

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.Bottom
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
        // 35+ forces edge to edge
        enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        ThemePreset.SaegeulIvory,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        ThemePreset.SaegeulNavy,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )
}
