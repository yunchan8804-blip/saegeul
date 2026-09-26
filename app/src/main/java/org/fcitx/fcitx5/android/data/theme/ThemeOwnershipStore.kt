/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context

/**
 * On-device record of shop themes bought with points.
 * Non-transferable by design (see docs/ad-monetization-avenue-operations.md 14.2).
 */
class ThemeOwnershipStore(context: Context) {
    private val prefs = context.getSharedPreferences("theme_shop", Context.MODE_PRIVATE)

    fun isOwned(name: String): Boolean = owned().contains(name)

    fun markOwned(name: String) {
        prefs.edit().putStringSet(KEY_OWNED, owned() + name).apply()
    }

    private fun owned(): Set<String> {
        return prefs.getStringSet(KEY_OWNED, emptySet()) ?: emptySet()
    }

    private companion object {
        const val KEY_OWNED = "owned_shop_themes"
    }
}
