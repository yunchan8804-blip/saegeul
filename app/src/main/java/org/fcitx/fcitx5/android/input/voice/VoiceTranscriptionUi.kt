/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelSurface
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
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
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
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)
    private val meeting = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        visibility = View.GONE
    }

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP),
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP)
        )
        setBackgroundColor(theme.keyboardColor)
        addView(title, matchWrap())
        addView(provider, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(transcriptScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            2f
        ))
        addView(meeting, matchWrap().apply {
            height = dp(PanelStyle.COMPACT_BUTTON_HEIGHT_DP)
            bottomMargin = dp(PanelStyle.GAP_M_DP)
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                marginEnd = dp(PanelStyle.GAP_S_DP)
            })
            addView(secondary, LinearLayout.LayoutParams(0, dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
                marginStart = dp(PanelStyle.GAP_S_DP)
            })
        }, matchWrap())
    }

    fun showReady(providerName: String, realtime: Boolean, showMeeting: Boolean) {
        title.setText(
            if (realtime) R.string.voice_realtime_title else R.string.voice_precision_title
        )
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.setText(
            if (realtime) R.string.voice_realtime_ready else R.string.voice_precision_ready
        )
        transcriptScroller.visibility = View.GONE
        renderMeeting(showMeeting)
        primary.apply {
            isEnabled = true
            setText(R.string.voice_record_start)
            setOnClickListener { onStart?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    fun showPermissionRequired(denied: Boolean = false) {
        status.setText(
            if (denied) R.string.voice_permission_denied else R.string.voice_permission_required
        )
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = true
            setText(R.string.voice_permission_allow)
            setOnClickListener { onPermission?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    fun showRecording(elapsedSeconds: Int) {
        status.text = recordingStatus(context.getString(R.string.voice_recording, elapsedSeconds))
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = true
            setText(R.string.voice_record_stop)
            setOnClickListener { onStop?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showRealtimeConnecting() {
        title.setText(R.string.voice_realtime_title)
        status.setText(R.string.voice_realtime_connecting)
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = false
            setText(R.string.voice_realtime_connecting_button)
            setOnClickListener(null)
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showRealtimeRecording(elapsedSeconds: Int, partial: String) {
        title.setText(R.string.voice_realtime_title)
        status.text = recordingStatus(
            context.getString(R.string.voice_realtime_recording, elapsedSeconds)
        )
        showTranscript(partial)
        hideMeeting()
        primary.apply {
            isEnabled = true
            setText(R.string.voice_record_stop)
            setOnClickListener { onStop?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showRealtimeFinalizing(partial: String) {
        title.setText(R.string.voice_realtime_title)
        status.setText(R.string.voice_realtime_finalizing)
        showTranscript(partial)
        hideMeeting()
        primary.apply {
            isEnabled = false
            setText(R.string.voice_transcribing_button)
            setOnClickListener(null)
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showTranscribing() {
        status.setText(R.string.voice_transcribing_segment)
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = false
            setText(R.string.voice_transcribing_button)
            setOnClickListener(null)
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showPreview(text: String) {
        status.setText(R.string.voice_preview_instruction)
        transcript.text = text
        transcriptScroller.visibility = View.VISIBLE
        hideMeeting()
        primary.apply {
            isEnabled = true
            setText(R.string.voice_insert)
            setOnClickListener { onInsert?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    /**
     * [recovery] takes over the primary slot when there is nothing to retry, so a blocked
     * panel still offers the setting that unblocks it rather than a dead button.
     */
    fun showError(message: String, canRetry: Boolean, recovery: PanelRecovery? = null) {
        status.text = message
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            when {
                canRetry -> {
                    isEnabled = true
                    setText(R.string.voice_retry_record)
                    setOnClickListener { onStart?.invoke() }
                }
                recovery != null -> {
                    isEnabled = true
                    setText(recovery.labelRes)
                    setOnClickListener { recovery.run() }
                }
                else -> {
                    isEnabled = false
                    setText(R.string.voice_retry_record)
                    setOnClickListener(null)
                }
            }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    fun showSetupRequired(message: String) {
        provider.text = ""
        status.text = message
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = true
            setText(R.string.ai_setup_action)
            setOnClickListener { onSetupRequested?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    fun showDeviceDictation(
        providerName: String,
        message: String,
        action: VoiceUnavailableAction,
        showMeeting: Boolean
    ) {
        title.setText(
            if (action == VoiceUnavailableAction.DeviceDictation) {
                R.string.voice_device_dictation_title
            } else {
                R.string.voice_precision_title
            }
        )
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.text = message
        transcriptScroller.visibility = View.GONE
        renderMeeting(showMeeting)
        primary.apply {
            isEnabled = true
            setText(
                if (action == VoiceUnavailableAction.DeviceDictation) {
                    R.string.voice_use_device_dictation
                } else {
                    R.string.ai_setup_action
                }
            )
            setOnClickListener {
                if (action == VoiceUnavailableAction.DeviceDictation) {
                    onDeviceDictation?.invoke()
                } else {
                    onSetupRequested?.invoke()
                }
            }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
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

    private fun hideMeeting() {
        meeting.visibility = View.GONE
        meeting.setOnClickListener(null)
    }

    private fun renderMeeting(show: Boolean) {
        if (!show) {
            hideMeeting()
            return
        }
        meeting.apply {
            visibility = View.VISIBLE
            isEnabled = true
            setText(R.string.meeting_entry_button)
            setOnClickListener { onMeeting?.invoke() }
        }
    }

    private fun showTranscript(text: String) {
        transcript.text = text
        transcriptScroller.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
