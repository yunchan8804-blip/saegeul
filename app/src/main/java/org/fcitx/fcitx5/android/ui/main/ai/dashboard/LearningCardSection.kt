/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.ui.main.ai.LearningStatusUiState

/** "새글이 배우는 중" card: one status line, its progress, a single action, and the automatic switch. */
internal class LearningCardSection(
    private val activity: Activity,
    private val onAction: (GraphEnrichmentUiState.Action) -> Unit,
    onAutomaticChanged: (Boolean) -> Unit
) {
    private val body: TextView = activity.findViewById(R.id.tv_learning_body)
    private val progress: LinearProgressIndicator = activity.findViewById(R.id.progress_learning)
    private val actionButton: MaterialButton = activity.findViewById(R.id.btn_learning_action)
    private val automaticSwitch: SwitchMaterial = activity.findViewById(R.id.switch_learning_automatic)
    private val notificationHint: TextView = activity.findViewById(R.id.tv_learning_notification_hint)
    private var rendering = false

    init {
        automaticSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                onAutomaticChanged(enabled)
            }
        }
    }

    fun render(state: LearningStatusUiState, graphActionInFlight: Boolean, dashboardBusy: Boolean) {
        body.text = when {
            state.reasonRes != null -> activity.getString(state.bodyRes, activity.getString(state.reasonRes))
            state.bodyIntArg != null -> activity.getString(state.bodyRes, state.bodyIntArg)
            state.tier == LearningStatusUiState.Tier.UP_TO_DATE && state.appliedAtMs > 0L ->
                activity.getString(state.bodyRes, activity.formatAppliedAt(state.appliedAtMs, APPLIED_AT_PLAIN))
            else -> activity.getString(state.bodyRes)
        }

        progress.visibility = if (state.showProgress) View.VISIBLE else View.GONE
        if (state.showProgress) {
            progress.isIndeterminate = false
            progress.max = state.progressTotal.coerceAtLeast(1)
            progress.setProgressCompat(state.progressCurrent, true)
        }

        if (state.buttonLabelRes != null && state.buttonAction != null && !graphActionInFlight) {
            actionButton.visibility = View.VISIBLE
            actionButton.isEnabled = !dashboardBusy
            actionButton.text = activity.getString(state.buttonLabelRes)
            val action = state.buttonAction
            actionButton.setOnClickListener { onAction(action) }
        } else {
            actionButton.visibility = View.GONE
        }

        automaticSwitch.visibility = if (state.showAutomaticSwitch) View.VISIBLE else View.GONE
        if (state.showAutomaticSwitch) {
            rendering = true
            automaticSwitch.isChecked = state.automaticChecked
            rendering = false
        }

        notificationHint.visibility = if (state.showNotificationHint) View.VISIBLE else View.GONE
    }

    fun setActionEnabled(enabled: Boolean) {
        actionButton.isEnabled = enabled
    }

    fun setAutomaticSwitchEnabled(enabled: Boolean) {
        automaticSwitch.isEnabled = enabled
    }

    private companion object {
        /**
         * Same day math as the enrichment card's line, but without its "applied" suffix, for the
         * "모두 반영됐어요 · {오늘 13:06 / 어제 / 9월 21일}" line, which already says "반영됐어요" once.
         */
        val APPLIED_AT_PLAIN = AppliedAtStrings(
            today = R.string.vault_applied_at_today,
            yesterday = R.string.vault_applied_at_yesterday,
            date = R.string.vault_applied_at_date
        )
    }
}
