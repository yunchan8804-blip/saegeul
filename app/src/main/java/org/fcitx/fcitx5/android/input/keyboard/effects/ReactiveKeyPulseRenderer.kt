/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 🎯 Render Reactive Per-Key Mechanical Keyboard Pulses (Ripple, Fade, Firework, Laser).
 */
internal class ReactiveKeyPulseRenderer(paints: ChromaPaints) {

    private val fillPaint = paints.fillPaint
    private val strokePaint = paints.strokePaint

    fun draw(canvas: Canvas, frame: EffectFrame, reactiveEvents: List<RgbChromaEffectView.ReactiveEvent>) {
        if (reactiveEvents.isEmpty()) return
        val w = frame.width
        val h = frame.height
        val now = SystemClock.uptimeMillis()
        val density = frame.density

        for (ev in reactiveEvents) {
            val elapsed = now - ev.startTimeMs
            if (elapsed >= ev.durationMs || elapsed < 0L) continue
            val rawProgress = (elapsed.toFloat() / ev.durationMs.toFloat()).coerceIn(0f, 1f)
            val alpha = ((1.0f - rawProgress) * 255).toInt().coerceIn(0, 255)

            when (ev.type) {
                "ripple" -> {
                    // Ease-out expansion curve: 1 - (1 - p)^3
                    val pEase = 1.0f - (1.0f - rawProgress) * (1.0f - rawProgress) * (1.0f - rawProgress)
                    val maxRadius = max(w, h) * 0.85f
                    val curRadius = pEase * maxRadius
                    val strokeWidth = (4f + (1f - rawProgress) * 10f) * density

                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = strokeWidth
                    canvas.drawCircle(ev.x, ev.y, curRadius, strokePaint)

                    // Secondary inner radiant flash
                    if (rawProgress < 0.35f) {
                        val innerProgress = rawProgress / 0.35f
                        val innerAlpha = ((1.0f - innerProgress) * 200).toInt()
                        fillPaint.shader = null
                        fillPaint.color = Color.WHITE
                        fillPaint.alpha = innerAlpha
                        canvas.drawCircle(ev.x, ev.y, 22f * density * (1f + innerProgress), fillPaint)
                    }
                }
                "fade" -> {
                    // Soft Gaussian-like Key glow spot that gently fades out
                    val spotRadius = (36f + rawProgress * 30f) * density
                    val shader = RadialGradient(
                        ev.x, ev.y, spotRadius,
                        intArrayOf(ColorUtils.setAlphaComponent(ev.color, alpha), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    fillPaint.shader = shader
                    fillPaint.alpha = 255
                    canvas.drawCircle(ev.x, ev.y, spotRadius, fillPaint)
                }
                "firework" -> {
                    // Starburst sparks in 10 radial rays with air drag friction
                    val pEase = 1.0f - (1.0f - rawProgress) * (1.0f - rawProgress)
                    val sparkDist = pEase * 80f * density
                    val rayCount = 10
                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = 2.8f * density

                    for (i in 0 until rayCount) {
                        val angle = (i * PI * 2.0 / rayCount).toFloat()
                        val rx1 = ev.x + cos(angle) * (sparkDist * 0.25f)
                        val ry1 = ev.y + sin(angle) * (sparkDist * 0.25f)
                        val rx2 = ev.x + cos(angle) * sparkDist
                        val ry2 = ev.y + sin(angle) * sparkDist
                        canvas.drawLine(rx1, ry1, rx2, ry2, strokePaint)
                    }
                }
                "laser" -> {
                    // High-luminance horizontal & vertical cross laser line
                    val laserAlpha = ((1.0f - rawProgress) * 240).toInt()
                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = laserAlpha
                    strokePaint.strokeWidth = 3.2f * density

                    // Horizontal laser beam
                    canvas.drawLine(0f, ev.y, w, ev.y, strokePaint)
                    // Vertical laser beam
                    canvas.drawLine(ev.x, 0f, ev.x, h, strokePaint)

                    // Bright core at intersection
                    fillPaint.shader = null
                    fillPaint.color = Color.WHITE
                    fillPaint.alpha = laserAlpha
                    canvas.drawCircle(ev.x, ev.y, 9f * density, fillPaint)
                }
            }
        }
    }
}
