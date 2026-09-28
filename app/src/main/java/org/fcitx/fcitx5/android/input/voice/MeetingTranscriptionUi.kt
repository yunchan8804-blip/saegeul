/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.addStatusWithPreview
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelButtonPair
import org.fcitx.fcitx5.android.input.panel.panelCheckRow
import org.fcitx.fcitx5.android.input.panel.panelColumn
import org.fcitx.fcitx5.android.input.panel.panelProgress
import org.fcitx.fcitx5.android.input.panel.panelStatusText
import org.fcitx.fcitx5.android.input.panel.showDisabledPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelActionOrRecovery
import splitties.dimensions.dp

class MeetingTranscriptionUi(
    private val context: Context,
    private val theme: Theme
) {
    var onPickFile: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onSelectionChanged: ((Set<String>) -> Boolean)? = null
    var onSetupRequested: (() -> Unit)? = null

    private val selectedIds = linkedSetOf<String>()
    private val provider = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
    }
    private val status = context.panelStatusText(theme)
    private val progress = context.panelProgress(theme)
    private val segments = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(segments, matchWrap())
    }
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)

    val root: View = context.panelColumn().apply {
        setBackgroundColor(theme.keyboardColor)
        addView(TextView(context).apply {
            setText(R.string.meeting_title)
            setTextColor(theme.keyTextColor)
            textSize = PanelStyle.TEXT_TITLE
        }, matchWrap())
        addView(provider, matchWrap())
        addStatusWithPreview(status, progress, scroller)
        addView(context.panelButtonPair(primary, secondary), matchWrap())
    }

    fun showReady(providerName: String) {
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.setText(R.string.meeting_ready)
        clearSegments()
        primary.showPanelAction(R.string.voice_meeting_disclosure_continue) { onPickFile?.invoke() }
        showBack()
    }

    fun showLoading(durationMillis: Long? = null) {
        if (durationMillis == null) {
            status.setText(R.string.meeting_checking_file)
        } else {
            status.text = context.getString(
                R.string.meeting_transcribing,
                formatDuration(durationMillis)
            )
        }
        clearSegments()
        progress.visibility = View.VISIBLE
        primary.showDisabledPanelAction(R.string.meeting_processing_button)
        showCancel()
    }

    fun showPreview(items: List<MeetingSpeakerSegment>) {
        selectedIds.clear()
        status.setText(R.string.meeting_preview)
        segments.removeAllViews()
        items.forEach { segment ->
            val text = buildString {
                append('[')
                append(MeetingTranscriptSelection.timestamp(segment.startSeconds))
                append("–")
                append(MeetingTranscriptSelection.timestamp(segment.endSeconds))
                append("] ")
                append(segment.speaker.ifBlank { speakerPrefix() })
                append("\n")
                append(segment.text)
            }
            segments.addView(context.panelCheckRow(theme, text) { checked ->
                if (checked) selectedIds += segment.id else selectedIds -= segment.id
                primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
            }, matchWrap().apply { bottomMargin = context.dp(PanelStyle.GAP_S_DP) })
        }
        progress.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        primary.showPanelAction(R.string.voice_insert, enabled = false) { onInsert?.invoke() }
        showCancel()
    }

    /**
     * [recovery] takes over the primary slot when there is nothing to retry, so a blocked
     * panel still offers the setting that unblocks it rather than a dead button.
     */
    fun showError(message: String, canRetry: Boolean, recovery: PanelRecovery? = null) {
        status.text = message
        clearSegments()
        primary.showPanelActionOrRecovery(canRetry, R.string.meeting_choose_again, recovery) {
            onPickFile?.invoke()
        }
        showBack()
    }

    fun showSetupRequired(message: String) {
        provider.text = ""
        status.text = message
        clearSegments()
        primary.showPanelAction(R.string.ai_setup_action) { onSetupRequested?.invoke() }
        showBack()
    }

    fun speakerPrefix(): String = context.getString(R.string.meeting_speaker_prefix)

    private fun showBack() = secondary.showPanelAction(R.string.ai_back) { onClose?.invoke() }

    private fun showCancel() = secondary.showPanelAction(android.R.string.cancel) { onCancel?.invoke() }

    private fun clearSegments() {
        selectedIds.clear()
        segments.removeAllViews()
        scroller.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun formatDuration(durationMillis: Long): String {
        val seconds = durationMillis / 1_000L
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return context.getString(R.string.meeting_duration_format, minutes, remainder)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
