/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.voice.DictationPauseReason
import org.fcitx.fcitx5.android.input.voice.DictationPhase
import org.fcitx.fcitx5.android.input.voice.DictationStripState
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

/**
 * One line above the keyboard while dictation runs: microphone, sound level, the sentence being
 * heard, erase and done. The keyboard below stays usable.
 */
internal class DictationStripUi(override val ctx: Context, private val theme: Theme) : Ui {

    var onMic: (() -> Unit)? = null
    var onErase: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val micButton = ToolButton(ctx, R.drawable.ic_baseline_keyboard_voice_24, theme).apply {
        setOnClickListener { onMic?.invoke() }
    }

    private val levelView = DictationLevelView(
        ctx,
        activeColor = theme.accentKeyBackgroundColor,
        idleColor = ColorUtils.setAlphaComponent(theme.altKeyTextColor, IDLE_ALPHA)
    )

    private val sentence = TextView(ctx).apply {
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.START
    }

    private val eraseButton = ToolButton(ctx, R.drawable.ic_baseline_backspace_24, theme).apply {
        contentDescription = ctx.getString(R.string.voice_strip_erase)
        setOnClickListener { onErase?.invoke() }
    }

    private val doneButton = ToolButton(ctx, R.drawable.ic_baseline_check_24, theme).apply {
        contentDescription = ctx.getString(R.string.voice_strip_done)
        setOnClickListener { onDone?.invoke() }
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val side = dp(KawaiiBarComponent.HEIGHT)
        addView(micButton, LinearLayout.LayoutParams(side, side))
        addView(levelView, LinearLayout.LayoutParams(dp(LEVEL_WIDTH_DP), side))
        addView(sentence, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(8)
        })
        addView(eraseButton, LinearLayout.LayoutParams(side, side))
        addView(doneButton, LinearLayout.LayoutParams(side, side))
    }

    fun render(state: DictationStripState) {
        val paused = state.phase == DictationPhase.Paused
        micButton.setIconTint(if (paused) theme.altKeyTextColor else theme.accentKeyBackgroundColor)
        micButton.contentDescription = ctx.getString(
            if (paused) R.string.voice_strip_mic_resume else R.string.voice_strip_mic_pause
        )
        levelView.update(state.level, active = state.phase == DictationPhase.Listening)
        val heard = state.partial.isNotEmpty() && !paused
        sentence.setTextColor(
            if (heard) theme.candidateTextColor
            else ColorUtils.setAlphaComponent(theme.altKeyTextColor, HINT_ALPHA)
        )
        sentence.text = if (heard) state.partial else ctx.getString(hintFor(state))
    }

    @StringRes
    private fun hintFor(state: DictationStripState): Int = when (state.phase) {
        DictationPhase.Paused -> when (state.pauseReason) {
            DictationPauseReason.Silence -> R.string.voice_strip_paused_silence
            DictationPauseReason.Language -> R.string.voice_strip_paused_language
            DictationPauseReason.Busy -> R.string.voice_strip_paused_busy
            DictationPauseReason.Network -> R.string.voice_strip_paused_network
            DictationPauseReason.Other -> R.string.voice_strip_paused_other
            DictationPauseReason.InsertFailed -> R.string.voice_strip_paused_insert
            DictationPauseReason.PermissionDenied -> R.string.voice_strip_permission
            DictationPauseReason.User, null -> R.string.voice_strip_paused_user
        }
        DictationPhase.Finishing -> R.string.voice_strip_finishing
        DictationPhase.Starting, DictationPhase.Listening -> when {
            state.serviceNotice -> R.string.voice_strip_service_notice
            state.phase == DictationPhase.Starting -> R.string.voice_strip_starting
            else -> R.string.voice_strip_prompt
        }
        DictationPhase.Closed -> R.string.voice_strip_prompt
    }

    private companion object {
        const val LEVEL_WIDTH_DP = 32
        const val IDLE_ALPHA = 110
        const val HINT_ALPHA = 190
    }
}

/** A few small bars whose height follows the microphone level. */
private class DictationLevelView(
    context: Context,
    private val activeColor: Int,
    private val idleColor: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var level = 0f
    private var active = false

    fun update(level: Float, active: Boolean) {
        if (this.level == level && this.active == active) return
        this.level = level
        this.active = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val barWidth = BAR_WIDTH_DP * density
        val gap = BAR_GAP_DP * density
        val minHeight = MIN_HEIGHT_DP * density
        val maxHeight = height - 2 * VERTICAL_MARGIN_DP * density
        val totalWidth = SHAPE.size * barWidth + (SHAPE.size - 1) * gap
        var x = (width - totalWidth) / 2f
        paint.color = if (active) activeColor else idleColor
        for (weight in SHAPE) {
            val barHeight = minHeight + (maxHeight - minHeight) * level * weight
            val top = (height - barHeight) / 2f
            bar.set(x, top, x + barWidth, top + barHeight)
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
            x += barWidth + gap
        }
    }

    private companion object {
        const val BAR_WIDTH_DP = 3f
        const val BAR_GAP_DP = 2f
        const val MIN_HEIGHT_DP = 4f
        const val VERTICAL_MARGIN_DP = 12f
        val SHAPE = floatArrayOf(0.45f, 0.8f, 1f, 0.7f, 0.9f, 0.55f)
    }
}
