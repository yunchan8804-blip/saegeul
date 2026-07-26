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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

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
        textSize = 11f
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val segments = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        visibility = View.GONE
        addView(segments, matchWrap())
    }
    private val primary = actionButton(active = true)
    private val secondary = actionButton(active = false)

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(theme.keyboardColor)
        addView(TextView(context).apply {
            text = local("회의·메모 화자 분리", "Meeting speaker transcription")
            setTextColor(theme.keyTextColor)
            textSize = 18f
        }, matchWrap())
        addView(provider, matchWrap())
        addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            3f
        ))
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

    fun showReady(providerName: String) {
        provider.text = context.getString(R.string.ai_provider, providerName)
        status.text = local(
            "직접 고른 60분·24MB 이하 음성만 전송해. 화자별 구간을 고른 뒤 입력할 수 있어.",
            "Only your selected audio up to 60 minutes and 24 MB is sent. Review and choose speaker segments before inserting."
        )
        clearSegments()
        primary.apply {
            isEnabled = true
            text = local("음성 파일 선택", "Choose audio file")
            setOnClickListener { onPickFile?.invoke() }
        }
        secondary.apply {
            isEnabled = true
            setText(R.string.ai_back)
            setOnClickListener { onClose?.invoke() }
        }
    }

    fun showLoading(durationMillis: Long? = null) {
        status.text = if (durationMillis == null) {
            local("선택한 음성 파일을 안전하게 확인 중…", "Checking the selected audio…")
        } else {
            local(
                "${formatDuration(durationMillis)} 음성의 화자를 분리해 전사 중…",
                "Separating speakers in ${formatDuration(durationMillis)} of audio…"
            )
        }
        clearSegments()
        primary.apply {
            isEnabled = false
            text = local("처리 중…", "Processing…")
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
        status.text = local(
            "넣을 화자 구간을 직접 선택해",
            "Select the speaker segments to insert"
        )
        segments.removeAllViews()
        items.forEach { segment ->
            segments.addView(CheckBox(context).apply {
                isChecked = false
                buttonTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
                setTextColor(theme.keyTextColor)
                textSize = 13f
                text = buildString {
                    append('[')
                    append(MeetingTranscriptSelection.timestamp(segment.startSeconds))
                    append("–")
                    append(MeetingTranscriptSelection.timestamp(segment.endSeconds))
                    append("] ")
                    append(segment.speaker.ifBlank { local("화자", "Speaker") })
                    append("\n")
                    append(segment.text)
                }
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = rounded(theme.keyBackgroundColor, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += segment.id else selectedIds -= segment.id
                    primary.isEnabled = onSelectionChanged?.invoke(selectedIds.toSet()) == true
                }
            }, matchWrap().apply { bottomMargin = dp(5) })
        }
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

    fun showError(message: String, canRetry: Boolean) {
        status.text = message
        clearSegments()
        primary.apply {
            isEnabled = canRetry
            text = local("다시 선택", "Choose again")
            setOnClickListener(if (canRetry) View.OnClickListener { onPickFile?.invoke() } else null)
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

    fun speakerPrefix(): String = local("화자", "Speaker")

    private fun clearSegments() {
        selectedIds.clear()
        segments.removeAllViews()
        scroller.visibility = View.GONE
    }

    private fun formatDuration(durationMillis: Long): String {
        val seconds = durationMillis / 1_000L
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return if (isKorean()) "${minutes}분 ${remainder}초" else "${minutes}m ${remainder}s"
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

    private fun local(korean: String, english: String): String = if (isKorean()) korean else english

    private fun isKorean(): Boolean =
        ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "ko"

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
