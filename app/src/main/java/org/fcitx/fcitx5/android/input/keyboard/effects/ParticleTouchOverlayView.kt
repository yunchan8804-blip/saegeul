/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.View
import org.fcitx.fcitx5.android.data.theme.Theme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Interactive touch particle overlay view rendering sparkling stars, glowing dust, and cosmic bursts.
 */
class ParticleTouchOverlayView(
    context: Context,
    var effectDef: Theme.Custom.ParticleEffectDef? = null
) : View(context), Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val starPath = Path()

    private val particles = CopyOnWriteArrayList<Particle>()
    private var isAnimating = false

    private val particleRainbowPalette = intArrayOf(
        Color.parseColor("#FFE600"), // Gold Star
        Color.parseColor("#00FFFF"), // Cyan Sparkle
        Color.parseColor("#FF007F"), // Neon Pink
        Color.parseColor("#79F1C2"), // Mint Jade
        Color.parseColor("#FFFFFF"), // Pure White
        Color.parseColor("#A855F7")  // Electric Purple
    )

    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var alpha: Float,
        val maxLifetime: Long,
        var currentAge: Long = 0L,
        val color: Int,
        val type: String
    )

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    fun spawnTouchBurst(x: Float, y: Float) {
        val def = effectDef ?: return
        val type = def.type
        if (type == "off") return

        val count = def.particleCount.coerceIn(3, 24)
        val lifetime = def.lifetimeMs.coerceIn(200L, 1000L)
        val speedMult = def.speed.coerceIn(0.3f, 3.0f)
        val customColor = def.color

        for (i in 0 until count) {
            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val speed = (Random.nextFloat() * 6f + 3f) * speedMult
            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat()
            val size = (Random.nextFloat() * 10f + 6f) * density()
            val color = customColor ?: particleRainbowPalette[Random.nextInt(particleRainbowPalette.size)]

            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    size = size,
                    rotation = Random.nextFloat() * 360f,
                    rotationSpeed = (Random.nextFloat() - 0.5f) * 20f,
                    alpha = 1.0f,
                    maxLifetime = lifetime,
                    color = color,
                    type = type
                )
            )
        }

        if (!isAnimating) {
            isAnimating = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    override fun doFrame(frameTimeNanos: Long) {
        if (particles.isEmpty()) {
            isAnimating = false
            invalidate()
            return
        }

        val iterator = particles.iterator()
        val dt = 16L // ~60fps step
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.currentAge += dt
            if (p.currentAge >= p.maxLifetime) {
                particles.remove(p)
                continue
            }
            p.x += p.vx
            p.y += p.vy
            p.vy += 0.25f // slight gravity
            p.rotation += p.rotationSpeed
            val progress = p.currentAge.toFloat() / p.maxLifetime.toFloat()
            p.alpha = (1.0f - progress).coerceIn(0f, 1f)
        }

        invalidate()

        if (particles.isNotEmpty()) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            isAnimating = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (particles.isEmpty()) return

        for (p in particles) {
            val alphaInt = (p.alpha * 255).toInt().coerceIn(0, 255)
            if (alphaInt <= 0) continue

            when (p.type) {
                "star_sparkle" -> {
                    paint.color = p.color
                    paint.alpha = alphaInt
                    drawStar(canvas, p.x, p.y, p.size * (0.8f + p.alpha * 0.4f), p.rotation)
                }
                "glowing_dust" -> {
                    paint.color = p.color
                    paint.alpha = alphaInt
                    canvas.drawCircle(p.x, p.y, p.size * p.alpha, paint)
                }
                "neon_burst" -> {
                    strokePaint.color = p.color
                    strokePaint.alpha = alphaInt
                    strokePaint.strokeWidth = 3f * density()
                    canvas.drawLine(p.x, p.y, p.x - p.vx * 2f, p.y - p.vy * 2f, strokePaint)
                }
                "cosmic_ripple" -> {
                    strokePaint.color = p.color
                    strokePaint.alpha = (alphaInt * 0.8f).toInt()
                    strokePaint.strokeWidth = 2f * density()
                    val rippleRadius = p.size * (1f + (1f - p.alpha) * 4f)
                    canvas.drawCircle(p.x, p.y, rippleRadius, strokePaint)
                }
                else -> {
                    paint.color = p.color
                    paint.alpha = alphaInt
                    drawStar(canvas, p.x, p.y, p.size, p.rotation)
                }
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        starPath.reset()
        val innerRadius = radius * 0.42f
        val points = 5
        val step = Math.PI / points
        val rotRad = Math.toRadians(rotation.toDouble())

        for (i in 0 until (points * 2)) {
            val r = if (i % 2 == 0) radius else innerRadius
            val a = i * step - Math.PI / 2.0 + rotRad
            val x = (cx + cos(a) * r).toFloat()
            val y = (cy + sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }
}
