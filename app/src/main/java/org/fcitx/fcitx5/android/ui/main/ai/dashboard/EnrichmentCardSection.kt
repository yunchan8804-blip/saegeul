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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.rag.GraphEnrichmentUiState
import org.fcitx.fcitx5.android.input.ai.rag.actionLabel
import org.fcitx.fcitx5.android.input.ai.rag.detail
import org.fcitx.fcitx5.android.input.ai.rag.guidance
import org.fcitx.fcitx5.android.input.ai.rag.secondaryGuidance
import org.fcitx.fcitx5.android.input.ai.rag.title

/** Relationship-graph card: enrichment phase, detail, guidance, progress, and its single action. */
internal class EnrichmentCardSection(
    private val activity: Activity,
    onSyncNow: () -> Unit
) {
    private val syncLevel: TextView = activity.findViewById(R.id.tv_dash_sync_level)
    private val graphStats: TextView = activity.findViewById(R.id.tv_dash_graph_stats)
    private val availability: TextView = activity.findViewById(R.id.tv_dash_enrichment_availability)
    private val setupButton: MaterialButton = activity.findViewById(R.id.btn_enrichment_setup)
    private val progress: LinearProgressIndicator = activity.findViewById(R.id.progress_enrichment)
    private val syncNowButton: MaterialButton = activity.findViewById(R.id.btn_sync_now)

    init {
        syncNowButton.setOnClickListener { onSyncNow() }
    }

    val phaseText: CharSequence
        get() = syncLevel.text

    /** The guidance line when it is showing; the status card appends it to the phase. */
    val reasonText: CharSequence?
        get() = if (availability.visibility == View.VISIBLE) availability.text else null

    val actionVisibility: Int
        get() = setupButton.visibility

    /** With on-device AI the Gemma card's own button replaces the plain sync button. */
    fun hideSyncNow() {
        syncNowButton.visibility = View.GONE
    }

    fun setSyncNowEnabled(enabled: Boolean) {
        syncNowButton.isEnabled = enabled
    }

    fun renderUnavailable() {
        syncLevel.setText(R.string.enrichment_unavailable_release_build)
        graphStats.text = ""
        availability.visibility = View.GONE
        progress.visibility = View.GONE
        setupButton.visibility = View.GONE
    }

    fun render(
        uiState: GraphEnrichmentUiState,
        graphActionInFlight: Boolean,
        onAction: (GraphEnrichmentUiState.Action?) -> Unit
    ) {
        syncLevel.text = uiState.title(activity)
        graphStats.text = uiState.detail(activity, appliedAtText = appliedAtText(uiState.lastAppliedMs))
            ?: ""
        val guidanceText = uiState.guidance(activity) ?: uiState.secondaryGuidance(activity)
        if (guidanceText != null) {
            availability.visibility = View.VISIBLE
            availability.text = guidanceText
        } else {
            availability.visibility = View.GONE
        }

        val showsDeterminateProgress = uiState.kind == GraphEnrichmentUiState.Kind.RUNNING
        progress.visibility = if (showsDeterminateProgress) View.VISIBLE else View.GONE
        if (showsDeterminateProgress) {
            progress.isIndeterminate = false
            progress.max = uiState.progressTotal.coerceAtLeast(1)
            progress.setProgressCompat(uiState.progressCurrent, true)
        }

        // A running action keeps its disabled button until the next refresh after it finishes.
        if (uiState.action != null && !graphActionInFlight) {
            setupButton.visibility = View.VISIBLE
            setupButton.isEnabled = true
            setupButton.text = uiState.actionLabel(activity)
            setupButton.setOnClickListener { onAction(uiState.action) }
        } else if (uiState.action == null) {
            setupButton.visibility = View.GONE
        }
    }

    fun disableAction() {
        setupButton.isEnabled = false
    }

    fun performActionClick() {
        setupButton.performClick()
    }

    /** "" when [ms] is 0 (not yet applied). */
    private fun appliedAtText(ms: Long): String =
        if (ms <= 0L) "" else activity.formatAppliedAt(ms, APPLIED_AT)

    private companion object {
        val APPLIED_AT = AppliedAtStrings(
            today = R.string.enrichment_applied_today,
            yesterday = R.string.enrichment_applied_yesterday,
            date = R.string.enrichment_applied_date
        )
    }
}
