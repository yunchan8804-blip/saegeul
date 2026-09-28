/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.widget.Toast
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.gif.GifCache
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/** Local data category: clearing media cached on this device. */
internal object LocalDataSection {
    fun addTo(screen: PreferenceScreen) {
        val ctx = screen.context
        screen.addCategory(R.string.privacy_local_data) {
            addPreference(R.string.gif_cache_clear, onClick = {
                GifCache(ctx).clear()
                Toast.makeText(ctx, R.string.gif_cache_cleared, Toast.LENGTH_SHORT).show()
            })
        }
    }
}
