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
        open(context, null)
    }

    fun openVoiceSetup(context: Context) {
        open(context, MainActivity.PRIVACY_AI_ACTION_VOICE_SETUP)
    }

    private fun open(context: Context, privacyAction: String?) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                this.action = Intent.ACTION_RUN
                putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.PrivacyAi)
                privacyAction?.let { putExtra(MainActivity.EXTRA_PRIVACY_AI_ACTION, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }
}
