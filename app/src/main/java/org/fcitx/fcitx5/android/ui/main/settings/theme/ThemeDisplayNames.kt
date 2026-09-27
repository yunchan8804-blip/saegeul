/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeShopCatalog

/**
 * The single place that maps a theme's internal identity to the short,
 * human-readable name shown in the theme list and the "시작 테마" chips.
 * See `app/design.md` round 2 decision ("테마") item 7.
 */
object ThemeDisplayNames {

    /** Every builtin theme's internal [Theme.name] to its display string resource. */
    val builtinNameRes: Map<String, Int> = mapOf(
        "SaegeulIvory" to R.string.theme_name_saegeul_ivory,
        "SaegeulNavy" to R.string.theme_name_saegeul_navy,
        "HanjiLight" to R.string.theme_name_hanji,
        "DancheongDark" to R.string.theme_name_dancheong,
        "BaegjaLight" to R.string.theme_name_baegja,
        "CheongjaDark" to R.string.theme_name_cheongja,
        "MidnightOLED" to R.string.theme_name_midnight_oled,
        "SeoulMistGlass" to R.string.theme_name_seoul_mist,
        "MaterialLight" to R.string.theme_name_material_light,
        "MaterialDark" to R.string.theme_name_material_dark,
        "PixelLight" to R.string.theme_name_pixel_light,
        "PixelDark" to R.string.theme_name_pixel_dark,
        "NordLight" to R.string.theme_name_nord_light,
        "NordDark" to R.string.theme_name_nord_dark,
        "DeepBlue" to R.string.theme_name_deep_blue,
        "Monokai" to R.string.theme_name_monokai,
        "AMOLEDBlack" to R.string.theme_name_amoled_black,
    )

    @StringRes
    fun resolveBuiltinNameRes(name: String): Int? = builtinNameRes[name]

    /**
     * The display name for any theme the app can show: a builtin/shop theme
     * gets its short Korean/English name, a Monet theme is labelled by
     * brightness, and a user-made [Theme.Custom] (whose [Theme.name] is a
     * UUID with no human meaning) is always "내 테마".
     */
    fun displayName(ctx: Context, theme: Theme): String = when (theme) {
        is Theme.Custom -> ctx.getString(R.string.theme_name_my_theme)
        is Theme.Monet -> ctx.getString(
            if (theme.isDark) R.string.theme_name_monet_dark else R.string.theme_name_monet_light
        )
        is Theme.Builtin -> resolveBuiltinNameRes(theme.name)?.let { ctx.getString(it) }
            ?: ThemeShopCatalog.find(theme.name)?.displayName
            ?: theme.name
    }
}
