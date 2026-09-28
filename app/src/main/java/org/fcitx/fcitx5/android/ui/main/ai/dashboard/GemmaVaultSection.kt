/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaSyncStatus

/** Gemma material card: automatic switch, last run, stored count, and the manual run controls. */
internal class GemmaVaultSection(private val activity: Activity) {
    private val card: MaterialCardView = activity.findViewById(R.id.gemma_vault_card)
    private val automatic: SwitchMaterial = activity.findViewById(R.id.gemma_vault_auto)
    private val status: TextView = activity.findViewById(R.id.gemma_vault_status)
    private val error: TextView = activity.findViewById(R.id.gemma_vault_error)
    private val generate: MaterialButton = activity.findViewById(R.id.gemma_vault_generate)
    private val personalResult: TextView = activity.findViewById(R.id.gemma_vault_personal_result)
    private val stop: MaterialButton = activity.findViewById(R.id.gemma_vault_stop)
    private val stopNotice: TextView = activity.findViewById(R.id.gemma_vault_stop_notice)
    private val model: MaterialButton = activity.findViewById(R.id.gemma_vault_model)
    private val count: TextView = activity.findViewById(R.id.gemma_vault_count)
    private val lastRun: TextView = activity.findViewById(R.id.gemma_vault_last_run)
    private var rendering = false

    /** Release builds without on-device AI never show the card. */
    fun hide() {
        card.visibility = View.GONE
    }

    /** [onGenerate] receives the card's own result line so the sync outcome is shown in place. */
    fun bind(
        onAutomaticChanged: (Boolean) -> Unit,
        onGenerate: (TextView) -> Unit,
        onOpenModelManagement: () -> Unit
    ) {
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                onAutomaticChanged(enabled)
            }
        }
        generate.setOnClickListener {
            // Material generation's own manual request is no longer fired from here: the graph
            // worker requests it (via the existing, unmodified GemmaAccumulationScheduler API) once
            // the graph run it starts (inside startSync -> continueWithEnrichment) actually ends, so
            // the two do not race for the same on-device generation lease.
            onGenerate(personalResult)
        }
        stop.setOnClickListener {
            onAutomaticChanged(false)
        }
        model.setOnClickListener {
            onOpenModelManagement()
        }
    }

    fun render(snapshot: GemmaPreparationSnapshot, transientError: String?) {
        rendering = true
        automatic.isChecked = snapshot.automaticEnabled
        rendering = false
        count.text = activity.getString(R.string.gemma_vault_count, snapshot.stored)
        lastRun.text = if (snapshot.lastRunEpochMs > 0L) {
            activity.getString(
                R.string.gemma_vault_last_run,
                TypingDnaSyncStatus.formatTime(snapshot.lastRunEpochMs, System.currentTimeMillis())
            )
        } else {
            activity.getString(R.string.gemma_vault_last_run_none)
        }
        status.text = if (snapshot.manualRequested && !snapshot.running && snapshot.error == null) {
            activity.getString(R.string.gemma_vault_manual_status, snapshot.status)
        } else {
            snapshot.status
        }
        val shownError = transientError ?: snapshot.error
        error.text = shownError
        error.visibility = if (shownError == null) View.GONE else View.VISIBLE
        generate.setText(R.string.gemma_vault_generate)
        val canStop = snapshot.manualRequested || snapshot.running
        stop.visibility = if (canStop) View.VISIBLE else View.GONE
        stopNotice.visibility = if (canStop) View.VISIBLE else View.GONE
    }

    fun showReadFailure() {
        error.setText(R.string.gemma_vault_state_read_failed)
        error.visibility = View.VISIBLE
    }

    /**
     * [hasSnapshot] gates the controls that act on the current state; [canGenerate] also accounts
     * for a running sync or clear.
     */
    fun renderAvailability(hasSnapshot: Boolean, mutationInProgress: Boolean, canGenerate: Boolean) {
        automatic.isEnabled = !mutationInProgress && hasSnapshot
        generate.isEnabled = canGenerate
        stop.isEnabled = !mutationInProgress
        model.isEnabled = !mutationInProgress
    }
}
