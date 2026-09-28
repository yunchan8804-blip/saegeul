/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

/** Privacy guarantees category: a read-only statement of what stays on the device. */
internal object PrivacyGuaranteesSection {
    fun addTo(screen: PreferenceScreen) {
        screen.addCategory(R.string.privacy_guarantees) {
            addPreference(
                R.string.privacy_guarantees,
                R.string.privacy_guarantees_summary
            )
        }
    }
}
