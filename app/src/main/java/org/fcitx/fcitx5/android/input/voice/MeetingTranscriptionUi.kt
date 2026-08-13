/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        visibility = View.GONE
    }
    private val segments = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(segments, matchWrap())
    }
    private val primary = context.panelButton(theme, PanelButtonKind.Primary)
    private val secondary = context.panelButton(theme, PanelButtonKind.Secondary)

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP),
            dp(PanelStyle.PANEL_PADDING_H_DP), dp(PanelStyle.PANEL_PADDING_V_DP)
        )
        setBackgroundColor(theme.keyboardColor)
        addView(TextView(context).apply {
            setText(R.string.meeting_title)
            setTextColor(theme.keyTextColor)
            textSize = PanelStyle.TEXT_TITLE
        }, matchWrap())
        addView(provider, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(PanelStyle.GAP_S_DP)
        })
        addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            3f
        ))
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

    fun showReady(providerName: String) {
        provider.text = context.getString(R.string.voice_connection_label, providerName)
        status.setText(R.string.meeting_ready)
        clearSegments()
        primary.apply {
            isEnabled = true
            setText(R.string.voice_meeting_disclosure_continue)
            setOnClickListener { onPickFile?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
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
        primary.apply {
            isEnabled = false
            setText(R.string.meeting_processing_button)
            setOnClickListener(null)
        }
        secondary.apply {
            isEnabled = true
            setText(android.R.string.cancel)
            setOnClickListener { onCancel?.invoke() }
        }
    }

    fun showPreview(items: List<MeetingSpeakerSegment>) {
        selectedIds.clear()
        status.setText(R.string.meeting_preview)
        segments.removeAllViews()
        items.forEach { segment ->
            segments.addView(CheckBox(context).apply {
                isChecked = false
                buttonTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
                setTextColor(theme.keyTextColor)
                textSize = PanelStyle.TEXT_BODY
                text = buildString {
                    append('[')
                    append(MeetingTranscriptSelection.timestamp(segment.startSeconds))
                    append("–")
                    append(MeetingTranscriptSelection.timestamp(segment.endSeconds))
                    append("] ")
                    append(segment.speaker.ifBlank { speakerPrefix() })
                    append("\n")
                    append(segment.text)
                }
                setPadding(
                    dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP),
                    dp(PanelStyle.GAP_M_DP), dp(PanelStyle.GAP_S_DP)
                )
                background = context.panelSurface(theme.keyBackgroundColor)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += segment.id else selectedIds -= segment.id
                    primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
                }
            }, matchWrap().apply { bottomMargin = context.dp(PanelStyle.GAP_S_DP) })
        }
        progress.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        primary.apply {
            isEnabled = false
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
        clearSegments()
        primary.apply {
            when {
                canRetry -> {
                    isEnabled = true
                    setText(R.string.meeting_choose_again)
                    setOnClickListener { onPickFile?.invoke() }
                }
                recovery != null -> {
                    isEnabled = true
                    setText(recovery.labelRes)
                    setOnClickListener { recovery.run() }
                }
                else -> {
                    isEnabled = false
                    setText(R.string.meeting_choose_again)
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
        clearSegments()
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

    fun speakerPrefix(): String = context.getString(R.string.meeting_speaker_prefix)

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
