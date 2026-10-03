/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelButtonPair
import org.fcitx.fcitx5.android.input.panel.panelColumn
import org.fcitx.fcitx5.android.input.panel.panelStatusText
import org.fcitx.fcitx5.android.input.panel.panelSurface
import org.fcitx.fcitx5.android.input.panel.showDisabledPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelAction
import org.fcitx.fcitx5.android.input.panel.showPanelActionOrRecovery
import splitties.dimensions.dp

/** Visibility contract for the separate, network-backed meeting transcription entry. */
internal object VoiceTranscriptionUiPolicy {
    fun showMeetingButton(
        hasStoredSttProfile: Boolean,
        allowsTextInspection: Boolean,
        allowsNetworkInput: Boolean
    ): Boolean = hasStoredSttProfile && allowsTextInspection && allowsNetworkInput
}

class VoiceTranscriptionUi(
    private val context: Context,
    private val theme: Theme
) {
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onInsert: (() -> Unit)? = null
    var onPermission: (() -> Unit)? = null
    var onDeviceDictation: (() -> Unit)? = null
    var onMeeting: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onSetupRequested: (() -> Unit)? = null

    private val title = TextView(context).apply {
        setText(R.string.voice_precision_title)
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_TITLE
    }
    private val provider = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
    }
    private val status = context.panelStatusText(theme)
    private val transcript = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        setPadding(
            dp(PanelStyle.CARD_PADDING_H_DP), dp(PanelStyle.CARD_PADDING_V_DP),
            dp(PanelStyle.CARD_PADDING_H_DP), dp(PanelStyle.CARD_PADDING_V_DP)
        )
        background = context.panelSurface(theme.keyBackgroundColor)
        setTextIsSelectable(false)
    }
    private val transcriptScroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(transcript, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }
    private val levelBar = View(context).apply {
        pivotX = 0f
        scaleX = 0f
        background = context.panelSurface(theme.accentKeyBackgroundColor)
    }
    private val level = FrameLayout(context).apply {
        visibility = View.GONE
        background = context.panelSurface(theme.keyBackgroundColor)
        addView(levelBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)
    private val meeting = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        visibility = View.GONE
    }

    val root: View = context.panelColumn().apply {
        setBackgroundColor(theme.keyboardColor)
        addView(title, matchWrap())
        addView(provider, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(level, matchWrap().apply {
            height = dp(LEVEL_HEIGHT_DP)
            bottomMargin = dp(PanelStyle.GAP_M_DP)
        })
        addView(transcriptScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            2f
        ))
        addView(meeting, matchWrap().apply {
            height = dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
            bottomMargin = dp(PanelStyle.GAP_M_DP)
        })
        addView(context.panelButtonPair(primary, secondary), matchWrap())
    }

    fun showReady(providerName: String, realtime: Boolean, showMeeting: Boolean) {
        level.visibility = View.GONE
        title.setText(
            if (realtime) R.string.voice_realtime_title else R.string.voice_precision_title
        )
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.setText(
            if (realtime) R.string.voice_realtime_ready else R.string.voice_precision_ready
        )
        transcriptScroller.visibility = View.GONE
        renderMeeting(showMeeting)
        primary.showPanelAction(R.string.voice_record_start) { onStart?.invoke() }
        showBack()
    }

    fun showPermissionRequired(denied: Boolean = false) {
        level.visibility = View.GONE
        status.setText(
            if (denied) R.string.voice_permission_denied else R.string.voice_permission_required
        )
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showPanelAction(R.string.voice_permission_allow) { onPermission?.invoke() }
        showBack()
    }

    fun showRecording(elapsedSeconds: Int) {
        level.visibility = View.GONE
        status.text = recordingStatus(context.getString(R.string.voice_recording, elapsedSeconds))
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showPanelAction(R.string.voice_record_stop) { onStop?.invoke() }
        showCancel()
    }

    fun showRealtimeConnecting() {
        level.visibility = View.GONE
        title.setText(R.string.voice_realtime_title)
        status.setText(R.string.voice_realtime_connecting)
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showDisabledPanelAction(R.string.voice_realtime_connecting_button)
        showCancel()
    }

    fun showRealtimeRecording(elapsedSeconds: Int, partial: String) {
        level.visibility = View.GONE
        title.setText(R.string.voice_realtime_title)
        status.text = recordingStatus(
            context.getString(R.string.voice_realtime_recording, elapsedSeconds)
        )
        showTranscript(partial)
        hideMeeting()
        primary.showPanelAction(R.string.voice_record_stop) { onStop?.invoke() }
        showCancel()
    }

    fun showRealtimeFinalizing(partial: String) {
        level.visibility = View.GONE
        title.setText(R.string.voice_realtime_title)
        status.setText(R.string.voice_realtime_finalizing)
        showTranscript(partial)
        hideMeeting()
        primary.showDisabledPanelAction(R.string.voice_transcribing_button)
        showCancel()
    }

    fun showTranscribing() {
        level.visibility = View.GONE
        status.setText(R.string.voice_transcribing_segment)
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showDisabledPanelAction(R.string.voice_transcribing_button)
        showCancel()
    }

    fun showPreview(text: String) {
        level.visibility = View.GONE
        status.setText(R.string.voice_preview_instruction)
        transcript.text = text
        transcriptScroller.visibility = View.VISIBLE
        hideMeeting()
        primary.showPanelAction(R.string.voice_insert) { onInsert?.invoke() }
        showCancel()
    }

    /**
     * [recovery] takes over the primary slot when there is nothing to retry, so a blocked
     * panel still offers the setting that unblocks it rather than a dead button.
     */
    fun showError(message: String, canRetry: Boolean, recovery: PanelRecovery? = null) {
        level.visibility = View.GONE
        status.text = message
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showPanelActionOrRecovery(canRetry, R.string.voice_retry_record, recovery) {
            onStart?.invoke()
        }
        showBack()
    }

    fun showSetupRequired(message: String) {
        level.visibility = View.GONE
        provider.text = ""
        status.text = message
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.showPanelAction(R.string.ai_setup_action) { onSetupRequested?.invoke() }
        showBack()
    }

    fun showDeviceReady(notice: String?, showMeeting: Boolean) {
        showDeviceFrame(notice)
        status.setText(R.string.voice_device_ready)
        transcriptScroller.visibility = View.GONE
        level.visibility = View.GONE
        renderMeeting(showMeeting)
        primary.showPanelAction(R.string.voice_device_start) { onStart?.invoke() }
        showBack()
    }

    fun showDeviceStarting(notice: String?) {
        showDeviceFrame(notice)
        status.setText(R.string.voice_device_starting)
        transcriptScroller.visibility = View.GONE
        level.visibility = View.GONE
        hideMeeting()
        primary.showDisabledPanelAction(R.string.voice_device_starting_button)
        showCancel()
    }

    fun showDeviceListening(notice: String?, partial: String) {
        showDeviceFrame(notice)
        status.text = recordingStatus(context.getString(R.string.voice_device_listening))
        showTranscript(partial)
        level.visibility = View.VISIBLE
        hideMeeting()
        primary.showPanelAction(R.string.voice_device_stop) { onStop?.invoke() }
        showCancel()
    }

    fun showDeviceFinishing(notice: String?, partial: String) {
        showDeviceFrame(notice)
        status.setText(R.string.voice_device_finishing)
        showTranscript(partial)
        setDeviceLevel(0f)
        level.visibility = View.GONE
        hideMeeting()
        primary.showDisabledPanelAction(R.string.voice_device_finishing_button)
        showCancel()
    }

    fun showDeviceNoSpeech(notice: String?) {
        showDeviceFrame(notice)
        status.setText(R.string.voice_device_no_speech)
        transcriptScroller.visibility = View.GONE
        level.visibility = View.GONE
        hideMeeting()
        primary.showPanelAction(R.string.voice_device_speak_again) { onStart?.invoke() }
        showBack()
    }

    /**
     * The device cannot listen inside the keyboard. Another voice keyboard is only an extra way
     * out; the primary action opens the setting that picks a different dictation method.
     */
    fun showDeviceUnavailable(message: String, canSwitchKeyboard: Boolean) {
        showDeviceFrame(null)
        status.text = message
        transcriptScroller.visibility = View.GONE
        level.visibility = View.GONE
        if (canSwitchKeyboard) {
            showExtra(R.string.voice_use_other_voice_keyboard) { onDeviceDictation?.invoke() }
        } else {
            hideMeeting()
        }
        primary.showPanelAction(R.string.ai_setup_action) { onSetupRequested?.invoke() }
        showBack()
    }

    fun setDeviceLevel(fraction: Float) {
        levelBar.scaleX = fraction
    }

    private fun showDeviceFrame(notice: String?) {
        title.setText(R.string.voice_device_dictation_title)
        provider.text = notice.orEmpty()
    }

    /** Recording states carry a leading dot in the recording-red convention. */
    private fun recordingStatus(text: String): CharSequence =
        SpannableString("● $text").apply {
            setSpan(
                ForegroundColorSpan(PanelStyle.errorTextColor(theme)),
                0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

    private fun showBack() = secondary.showPanelAction(R.string.ai_back) { onClose?.invoke() }

    private fun showCancel() = secondary.showPanelAction(android.R.string.cancel) { onCancel?.invoke() }

    private fun hideMeeting() {
        meeting.visibility = View.GONE
        meeting.setOnClickListener(null)
    }

    private fun renderMeeting(show: Boolean) {
        if (!show) {
            hideMeeting()
            return
        }
        showExtra(R.string.meeting_entry_button) { onMeeting?.invoke() }
    }

    private fun showExtra(@StringRes label: Int, action: () -> Unit) {
        meeting.apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(label)
            setOnClickListener { action() }
        }
    }

    private fun showTranscript(text: String) {
        transcript.text = text
        transcriptScroller.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val LEVEL_HEIGHT_DP = 4
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
