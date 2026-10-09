/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

/**
 * The themes a picker may offer: shop themes only once bought, every other theme as is.
 * [isOwned] is asked for shop themes only.
 */
fun selectableThemes(all: List<Theme>, isOwned: (String) -> Boolean): List<Theme> =
    all.filter { isSelectable(it, isOwned) }

/**
 * The theme saved under [name], or null when it is unknown or a shop theme that has not been
 * bought (a value an earlier build may have stored).
 */
fun resolveSelectableTheme(
    name: String,
    all: List<Theme>,
    isOwned: (String) -> Boolean
): Theme? = all.find { it.name == name }?.takeIf { isSelectable(it, isOwned) }

private fun isSelectable(theme: Theme, isOwned: (String) -> Boolean): Boolean =
    theme !is Theme.Builtin || ThemeShopCatalog.find(theme.name) == null || isOwned(theme.name)
