/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.sin

/**
 * Animated RGB Chroma Backlight & Neon Flow Effect View for Saegeul Keyboard.
 */
class RgbChromaEffectView(
    context: Context,
    var effectDef: Theme.Custom.LightingEffectDef? = null
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    // Signature Chroma Palettes
    private val rainbowColors = intArrayOf(
        Color.parseColor("#FF0055"), // Red/Pink
        Color.parseColor("#FF5500"), // Orange
        Color.parseColor("#FFE600"), // Yellow
        Color.parseColor("#00FF66"), // Neon Green
        Color.parseColor("#00CCFF"), // Cyan
        Color.parseColor("#7700FF"), // Purple
        Color.parseColor("#FF0055")  // Loop back
    )

    private val cyberpunkColors = intArrayOf(
        Color.parseColor("#00F0FF"), // Electric Blue
        Color.parseColor("#FF0077"), // Hot Pink
        Color.parseColor("#7700FF"), // Deep Violet
        Color.parseColor("#FFE600"), // Cyber Yellow
        Color.parseColor("#00F0FF")
    )

    private val matrixColors = intArrayOf(
        Color.parseColor("#003300"),
        Color.parseColor("#00FF66"),
        Color.parseColor("#66FFAA"),
        Color.parseColor("#00FF66"),
        Color.parseColor("#003300")
    )

    init {
        setWillNotDraw(false)
        updateEffect(effectDef)
    }

    fun updateEffect(newDef: Theme.Custom.LightingEffectDef?) {
        effectDef = newDef
        animator?.cancel()
        animator = null

        val mode = newDef?.mode ?: "off"
        if (mode == "off" || newDef == null) {
            visibility = GONE
            return
        }

        visibility = VISIBLE
        val speed = (newDef.speed.coerceIn(0.2f, 4.0f))
        val duration = (2500L / speed).toLong()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (effectDef != null && effectDef?.mode != "off") {
            animator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (animator?.isStarted == false && effectDef?.mode != "off") {
                animator?.start()
            }
        } else {
            animator?.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val def = effectDef ?: return
        val mode = def.mode
        if (mode == "off") return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val intensity = (def.intensity.coerceIn(0.1f, 1.0f) * 255).toInt()
        paint.alpha = intensity

        val palette = when (mode) {
            "cyberpunk" -> cyberpunkColors
            "matrix_flow" -> matrixColors
            else -> def.customColors?.toIntArray() ?: rainbowColors
        }

        when (mode) {
            "rgb_wave", "cyberpunk", "matrix_flow" -> {
                val isRightToLeft = def.direction == "right_to_left"
                val shift = if (isRightToLeft) (1f - phase) * w else phase * w

                val x0 = shift - w
                val x1 = shift + w

                val shader = LinearGradient(
                    x0, 0f, x1, 0f,
                    palette,
                    null,
                    Shader.TileMode.REPEAT
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            "rgb_breathe" -> {
                val breatheAlpha = ((sin(phase * Math.PI * 2.0) + 1.0) / 2.0 * intensity).toInt()
                paint.alpha = breatheAlpha.coerceIn(20, 255)
                val colorIdx = ((phase * palette.size).toInt()) % palette.size
                val nextColorIdx = (colorIdx + 1) % palette.size
                val curColor = palette[colorIdx]

                val shader = RadialGradient(
                    w / 2f, h / 2f, w / 1.4f,
                    curColor, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            "neon_pulse" -> {
                val pulseX = w * phase
                val shader = LinearGradient(
                    pulseX - w * 0.3f, 0f, pulseX + w * 0.3f, 0f,
                    intArrayOf(Color.TRANSPARENT, palette[0], palette[1 % palette.size], Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            else -> {
                val shader = LinearGradient(
                    0f, 0f, w, 0f,
                    palette, null, Shader.TileMode.REPEAT
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
    }
}
