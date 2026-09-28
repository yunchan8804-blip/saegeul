/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallFlow
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallStatusView
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState

/** "새글 AI" card: Gemma install status, hidden once installed (see [GemmaInstallUiState.shouldShowVaultCard]). */
internal class GemmaInstallCardSection(private val activity: AppCompatActivity) {
    private val card: MaterialCardView = activity.findViewById(R.id.card_gemma_install)
    private val statusView = GemmaInstallStatusView.bind(activity.findViewById(R.id.gemma_install_card_status))

    init {
        statusView.onAction = { button ->
            GemmaInstallFlow.handle(activity, button)
        }
    }

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GemmaModelInstaller.state(activity.applicationContext)
                    .collect { state ->
                        val show = GemmaInstallUiState.shouldShowVaultCard(state)
                        card.visibility = if (show) View.VISIBLE else View.GONE
                        if (show) {
                            statusView.render(activity, GemmaInstallUiState.from(state))
                        }
                    }
            }
        }
    }
}
