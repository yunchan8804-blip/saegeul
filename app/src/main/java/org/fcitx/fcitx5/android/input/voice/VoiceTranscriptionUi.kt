/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

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
        textSize = 18f
    }
    private val provider = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = 11f
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val transcript = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(theme.keyBackgroundColor, dp(10))
        setTextIsSelectable(false)
    }
    private val transcriptScroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(transcript, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }
    private val primary = actionButton(active = true)
    private val secondary = actionButton(active = false)
    private val meeting = actionButton(active = false).apply {
        visibility = View.GONE
    }

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
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
            height = dp(40)
            bottomMargin = dp(6)
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            })
            addView(secondary, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            })
        }, matchWrap())
    }

    fun showReady(providerName: String, realtime: Boolean) {
        title.setText(
            if (realtime) R.string.voice_realtime_title else R.string.voice_precision_title
        )
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.setText(
            if (realtime) R.string.voice_realtime_ready else R.string.voice_precision_ready
        )
        transcriptScroller.visibility = View.GONE
        meeting.apply {
            visibility = View.VISIBLE
            isEnabled = true
            text = local("회의·메모 음성 파일", "Meeting audio file")
            setOnClickListener { onMeeting?.invoke() }
        }
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
        status.text = context.getString(R.string.voice_recording, elapsedSeconds)
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
        status.text = context.getString(R.string.voice_realtime_recording, elapsedSeconds)
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

    fun showError(message: String, canRetry: Boolean) {
        status.text = message
        transcriptScroller.visibility = View.GONE
        hideMeeting()
        primary.apply {
            isEnabled = canRetry
            setText(R.string.voice_retry_record)
            setOnClickListener(if (canRetry) View.OnClickListener { onStart?.invoke() } else null)
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
        action: VoiceUnavailableAction
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
        hideMeeting()
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

    private fun actionButton(active: Boolean) = Button(context).apply {
        isAllCaps = false
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setTextColor(if (active) theme.accentKeyTextColor else theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(
            if (active) theme.accentKeyBackgroundColor else theme.keyBackgroundColor
        )
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun hideMeeting() {
        meeting.visibility = View.GONE
        meeting.setOnClickListener(null)
    }

    private fun showTranscript(text: String) {
        transcript.text = text
        transcriptScroller.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun local(korean: String, english: String): String =
        if (ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "ko") {
            korean
        } else {
            english
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
