/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

enum class TabId(@StringRes val titleRes: Int, @DrawableRes val iconRes: Int) {
    CLIPBOARD(R.string.clipboard, R.drawable.ic_clipboard),
    QUICK_PHRASE(R.string.quickphrase, R.drawable.ic_baseline_format_quote_24),
    SEARCH(R.string.korean_search, R.drawable.ic_baseline_search_24),
    MEDIA(R.string.gif_search, R.drawable.ic_baseline_image_24),
    FAVORITES(R.string.theme, R.drawable.ic_baseline_auto_awesome_24),
    SETTINGS(R.string.open_input_method_settings, R.drawable.ic_baseline_settings_24),
    SYNC(R.string.reload_config, R.drawable.ic_baseline_sync_24)
}

data class TabItem(
    val id: TabId,
    @StringRes val titleRes: Int = id.titleRes,
    @DrawableRes val iconRes: Int = id.iconRes,
    var isVisible: Boolean = true,
    var isPinned: Boolean = false,
    var badgeCount: Int = 0
)

data class TabConfigItem(
    val id: TabId,
    val visible: Boolean = true,
    val pinned: Boolean = false
)

data class TabConfiguration(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val tabs: List<TabConfigItem>,
    val checksum: String = ""
)
