/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceFailureText
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry

/** "Right now" status card: engine, graph enrichment, notifications, and today's collection. */
internal class ActivityStatusSection(
    private val activity: Activity,
    onEnrichmentAction: () -> Unit
) {
    private val engineValue: TextView = activity.findViewById(R.id.tv_status_engine_value)
    private val enrichmentValue: TextView = activity.findViewById(R.id.tv_status_enrichment_value)
    private val enrichmentAction: MaterialButton = activity.findViewById(R.id.btn_status_enrichment_action)
    private val notificationRow: View = activity.findViewById(R.id.row_status_notification)
    private val collectionValue: TextView = activity.findViewById(R.id.tv_status_collection_value)
    private val collectionPending: TextView = activity.findViewById(R.id.tv_status_collection_pending)
    private val collectionToggle: TextView = activity.findViewById(R.id.tv_status_collection_toggle)
    private val collectionRecent: LinearLayout = activity.findViewById(R.id.container_status_collection_recent)
    private var collectionRecentExpanded = false

    init {
        enrichmentAction.setOnClickListener { onEnrichmentAction() }
        activity.findViewById<MaterialButton>(R.id.btn_status_notification_action).setOnClickListener {
            openNotificationSettings()
        }
        collectionToggle.setOnClickListener { toggleCollectionRecent() }
    }

    /** Row (1): the automatic-suggestion engine's latest status. */
    fun renderEngine(status: DashboardStatusText.EngineStatus, nowMs: Long) {
        engineValue.text = when (status.state) {
            DashboardStatusText.EngineState.UNSUPPORTED_RELEASE ->
                activity.getString(R.string.privacy_ai_automatic_release_summary)
            DashboardStatusText.EngineState.NOT_ATTEMPTED ->
                "${activity.getString(R.string.dashboard_status_engine_not_attempted)}\n${activity.getString(R.string.dashboard_status_engine_hint)}"
            DashboardStatusText.EngineState.READY -> {
                val relative = DateUtils.getRelativeTimeSpanString(
                    nowMs - status.ageMs, nowMs, DateUtils.SECOND_IN_MILLIS
                )
                val base = activity.getString(
                    R.string.dashboard_status_engine_ready,
                    (status.backend ?: "cpu").uppercase(),
                    relative
                )
                if (status.recoveryCount > 0) {
                    base + activity.getString(R.string.dashboard_status_engine_recovery_suffix, status.recoveryCount)
                } else base
            }
            DashboardStatusText.EngineState.FAILED -> {
                val relative = DateUtils.getRelativeTimeSpanString(
                    nowMs - status.ageMs, nowMs, DateUtils.SECOND_IN_MILLIS
                )
                val failureText = status.failureCode?.let {
                    OnDeviceFailureText.of(it, activity.resources)
                } ?: activity.getString(R.string.enrichment_status_failed)
                val base = activity.getString(R.string.dashboard_status_engine_failed, failureText, relative)
                val withRecovery = if (status.recoveryCount > 0) {
                    base + activity.getString(R.string.dashboard_status_engine_recovery_suffix, status.recoveryCount)
                } else base
                "$withRecovery\n${activity.getString(R.string.dashboard_status_engine_hint)}"
            }
        }
    }

    /** Row (2): a one-line summary of the enrichment card's state. */
    fun renderEnrichment(phaseText: CharSequence, reasonText: CharSequence?, actionVisibility: Int) {
        enrichmentValue.text = if (reasonText.isNullOrEmpty()) {
            phaseText
        } else {
            "$phaseText · $reasonText"
        }
        enrichmentAction.visibility = actionVisibility
    }

    /** Row (3): only shown while notifications are blocked. */
    fun renderNotification(blocked: Boolean) {
        notificationRow.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    /** Row (4): today's collection counters and pending queue. */
    fun renderCollection(snapshot: DashboardSnapshot) {
        val counters = snapshot.collectionToday
        val learnedText = activity.getString(R.string.dashboard_status_collection_learned, counters.emitted)
        val droppedTotal = counters.droppedByReason.values.sum()
        collectionValue.text = if (droppedTotal > 0) {
            val detail = counters.droppedByReason.entries.joinToString(" · ") { (reason, count) ->
                "${dropReasonLabel(reason)} $count"
            }
            val droppedText = activity.getString(R.string.dashboard_status_collection_dropped_count, droppedTotal)
            "$learnedText · $droppedText($detail)"
        } else {
            learnedText
        }

        val pendingSummary = PersonaRegistry.all.mapNotNull { persona ->
            val pending = snapshot.categoryPending[persona.id] ?: 0
            if (pending > 0) "${activity.getString(persona.labelRes)} $pending/${snapshot.categoryPendingThreshold}" else null
        }
        if (pendingSummary.isEmpty()) {
            collectionPending.visibility = View.GONE
        } else {
            collectionPending.visibility = View.VISIBLE
            collectionPending.text = pendingSummary.joinToString(" · ")
        }

        if (collectionRecentExpanded) {
            renderRecentCollectionEvents()
        }
    }

    private fun openNotificationSettings() {
        activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
        })
    }

    private fun toggleCollectionRecent() {
        collectionRecentExpanded = !collectionRecentExpanded
        collectionToggle.setText(
            if (collectionRecentExpanded) R.string.dashboard_status_collection_recent_hide
            else R.string.dashboard_status_collection_recent_show
        )
        collectionRecent.visibility = if (collectionRecentExpanded) View.VISIBLE else View.GONE
        if (collectionRecentExpanded) {
            renderRecentCollectionEvents()
        }
    }

    private fun renderRecentCollectionEvents() {
        collectionRecent.removeAllViews()
        val events = FcitxApplication.getInstance().collectionDiagnostics.recent()
        if (events.isEmpty()) {
            val row = activity.layoutInflater.inflate(
                R.layout.view_dashboard_collection_event_row, collectionRecent, false
            ) as TextView
            row.setText(R.string.dashboard_status_collection_recent_empty)
            collectionRecent.addView(row)
            return
        }
        events.takeLast(10).asReversed().forEach { event ->
            val time = DateUtils.formatDateTime(activity, event.atMs, DateUtils.FORMAT_SHOW_TIME)
            val kindLabel = activity.getString(collectionEventKindRes(event.kind))
            val detail = when {
                event.reason != null -> dropReasonLabel(event.reason)
                event.category != null -> PersonaRegistry.byId(event.category)?.let { activity.getString(it.labelRes) }
                    ?: event.category
                else -> null
            }
            val row = activity.layoutInflater.inflate(
                R.layout.view_dashboard_collection_event_row, collectionRecent, false
            ) as TextView
            row.text = if (detail != null) "$time · $kindLabel · $detail" else "$time · $kindLabel"
            collectionRecent.addView(row)
        }
    }

    private fun dropReasonLabel(reason: String): String = activity.getString(
        when (reason) {
            "privacy" -> R.string.collection_drop_reason_privacy
            "short" -> R.string.collection_drop_reason_short
            "backspace" -> R.string.collection_drop_reason_backspace
            "editorSwitch" -> R.string.collection_drop_reason_editor_switch
            "duplicate" -> R.string.collection_drop_reason_duplicate
            "blank" -> R.string.collection_drop_reason_blank
            else -> R.string.collection_drop_reason_unknown
        }
    )

    private fun collectionEventKindRes(kind: String): Int = when (kind) {
        "emitted" -> R.string.collection_event_kind_emitted
        "dropped" -> R.string.collection_event_kind_dropped
        "batchReady" -> R.string.collection_event_kind_batch_ready
        "compiled" -> R.string.collection_event_kind_compiled
        else -> R.string.collection_event_kind_emitted
    }
}
