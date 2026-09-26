/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaModelActivity

/**
 * The product model-management screen ("새글 AI"): download progress, storage, delete and import.
 * Single implementation for every build variant - debug's experiment screen is now reached only
 * from this screen's own dev-section link, not from here.
 */
object GemmaModelManagementLauncher {
    fun open(context: Context) {
        val intent = Intent(context, GemmaModelActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
