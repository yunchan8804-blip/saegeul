/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Self-painted indeterminate ring for the candidate bar's 16dp spinners (the status row spinner
 * and the generating spinners next to the sentence rows in [HorizontalCandidateComponent]).
 *
 * The platform's `?android:attr/progressBarStyleSmall` `ProgressBar`, constructed directly in
 * code (not inflated from XML) on this `InputMethodService`'s raw `Context`, does not reliably
 * resolve to a drawable that paints anything at 16dp: it previously clipped down to a barely
 * visible sliver (the original "tiny dot" report), and after switching to the small style it
 * stopped painting at all even though the 16dp layout box was still reserved. Painting our own
 * ring removes that dependency entirely: [draw] always paints a full frame the moment the
 * drawable is attached, so the spinner is visible on frame one regardless of whether [start]
 * has run yet.
 *
 * Animation is driven by [scheduleSelf]/[SystemClock.uptimeMillis] rather than
 * `ValueAnimator`/`ObjectAnimator`, so it is unaffected by
 * `Settings.Global.ANIMATOR_DURATION_SCALE` being set to 0 (common on automated/emulator
 * screenshot capture, and a documented gotcha elsewhere in this codebase, see
 * [org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate]); the ring stays visible
 * and animates either way. [android.widget.ProgressBar] itself calls [start]/[stop] on an
 * `indeterminateDrawable` that implements [Animatable] as it attaches/detaches or becomes
 * visible/invisible, so no extra lifecycle wiring is needed here beyond the existing
 * `statusProgressBar.visibility` toggle in `HorizontalCandidateComponent.setStatusRow`.
 */
internal class IndeterminateRingDrawable(
    private val ringColor: Int,
    private val sizePx: Int
) : Drawable(), Animatable {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (sizePx / 8f).coerceAtLeast(1f)
        strokeCap = Paint.Cap.ROUND
        color = ringColor
    }
    private val arcRect = RectF()
    private var degrees = 0f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            degrees = (degrees + TICK_DEGREES) % 360f
            invalidateSelf()
            if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_INTERVAL_MS)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = paint.strokeWidth / 2f
        arcRect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(degrees, arcRect.centerX(), arcRect.centerY())
        canvas.drawArc(arcRect, 0f, SWEEP_DEGREES, false, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (running) return
        running = true
        unscheduleSelf(tick)
        scheduleSelf(tick, SystemClock.uptimeMillis())
    }

    override fun stop() {
        running = false
        unscheduleSelf(tick)
    }

    override fun isRunning(): Boolean = running

    private companion object {
        const val SWEEP_DEGREES = 300f
        const val TICK_DEGREES = 10f
        const val FRAME_INTERVAL_MS = 40L
    }
}
