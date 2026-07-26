/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute

/** Opens the exact AI settings destination from a user-initiated IME action. */
object AiSettingsNavigator {
    fun open(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_RUN
                putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.PrivacyAi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }
}
