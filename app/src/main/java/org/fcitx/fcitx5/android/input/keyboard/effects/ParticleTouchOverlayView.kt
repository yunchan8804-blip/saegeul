/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-fidelity interactive touch particle overlay view rendering sparkling stars, glowing dust, and cosmic bursts.
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
        Color.parseColor("#A855F7"), // Electric Purple
        Color.parseColor("#38BDF8")  // Sky Blue
    )

    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var initialSize: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var alpha: Float,
        val maxLifetime: Long,
        var currentAge: Long = 0L,
        val color: Int,
        val type: String,
        val isCross: Boolean = false
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

        val count = def.particleCount.coerceIn(3, 28)
        val lifetime = def.lifetimeMs.coerceIn(200L, 1200L)
        val speedMult = def.speed.coerceIn(0.3f, 3.0f)
        val customColor = def.color
        val density = resources.displayMetrics.density

        for (i in 0 until count) {
            val angle = Random.nextDouble(0.0, PI * 2.0)
            val speed = (Random.nextFloat() * 5.5f + 2.5f) * speedMult * density
            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat()
            val size = (Random.nextFloat() * 8f + 5f) * density
            val color = customColor ?: particleRainbowPalette[Random.nextInt(particleRainbowPalette.size)]

            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    initialSize = size,
                    rotation = Random.nextFloat() * 360f,
                    rotationSpeed = (Random.nextFloat() - 0.5f) * 16f,
                    alpha = 1.0f,
                    maxLifetime = lifetime,
                    color = color,
                    type = type,
                    isCross = i % 2 == 0
                )
            )
        }

        if (!isAnimating && isAttachedToWindow) {
            isAnimating = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (particles.isEmpty() || !isAttachedToWindow) {
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

            // Air drag friction physics
            p.vx *= 0.94f
            p.vy *= 0.94f
            p.vy += 0.05f // very subtle drift
            p.x += p.vx
            p.y += p.vy
            p.rotation += p.rotationSpeed
            p.rotationSpeed *= 0.96f

            val progress = (p.currentAge.toFloat() / p.maxLifetime.toFloat()).coerceIn(0f, 1f)
            // Smooth ease-out alpha curve: (1 - progress)^1.4
            p.alpha = Math.pow((1.0 - progress.toDouble()), 1.4).toFloat().coerceIn(0f, 1f)
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
        val density = resources.displayMetrics.density

        for (p in particles) {
            val alphaInt = (p.alpha * 255).toInt().coerceIn(0, 255)
            if (alphaInt <= 0) continue

            val curSize = p.initialSize * (0.6f + p.alpha * 0.4f)

            when (p.type) {
                "star_sparkle" -> {
                    paint.shader = null
                    paint.color = p.color
                    paint.alpha = alphaInt
                    if (p.isCross) {
                        drawCrossStar(canvas, p.x, p.y, curSize, p.rotation)
                    } else {
                        drawStar(canvas, p.x, p.y, curSize, p.rotation)
                    }
                    // Central white core
                    if (p.alpha > 0.4f) {
                        paint.color = Color.WHITE
                        paint.alpha = ((p.alpha - 0.4f) / 0.6f * 200).toInt()
                        canvas.drawCircle(p.x, p.y, curSize * 0.25f, paint)
                    }
                }
                "glowing_dust" -> {
                    val shader = RadialGradient(
                        p.x, p.y, curSize * 1.5f,
                        intArrayOf(ColorUtils.setAlphaComponent(p.color, alphaInt), ColorUtils.setAlphaComponent(p.color, (alphaInt * 0.3f).toInt()), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = shader
                    paint.alpha = 255
                    canvas.drawCircle(p.x, p.y, curSize * 1.5f, paint)
                }
                "neon_burst" -> {
                    val tailX = p.x - p.vx * 2.8f
                    val tailY = p.y - p.vy * 2.8f
                    val grad = LinearGradient(
                        p.x, p.y, tailX, tailY,
                        intArrayOf(ColorUtils.setAlphaComponent(p.color, alphaInt), Color.TRANSPARENT),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    strokePaint.shader = grad
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.strokeWidth = (2.2f + p.alpha * 1.5f) * density
                    canvas.drawLine(p.x, p.y, tailX, tailY, strokePaint)

                    // Head point
                    paint.shader = null
                    paint.color = Color.WHITE
                    paint.alpha = alphaInt
                    canvas.drawCircle(p.x, p.y, 1.8f * density, paint)
                }
                "cosmic_ripple" -> {
                    val progress = (p.currentAge.toFloat() / p.maxLifetime.toFloat()).coerceIn(0f, 1f)
                    val pEase = 1.0f - (1.0f - progress) * (1.0f - progress)
                    val rippleRadius = curSize * (1f + pEase * 4.5f)

                    strokePaint.shader = null
                    strokePaint.color = p.color
                    strokePaint.alpha = (alphaInt * 0.85f).toInt()
                    strokePaint.strokeWidth = (1.5f + (1f - progress) * 2f) * density
                    canvas.drawCircle(p.x, p.y, rippleRadius, strokePaint)
                }
                else -> {
                    paint.shader = null
                    paint.color = p.color
                    paint.alpha = alphaInt
                    drawStar(canvas, p.x, p.y, curSize, p.rotation)
                }
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        starPath.reset()
        val innerRadius = radius * 0.42f
        val points = 5
        val step = PI / points
        val rotRad = Math.toRadians(rotation.toDouble())

        for (i in 0 until (points * 2)) {
            val r = if (i % 2 == 0) radius else innerRadius
            val a = i * step - PI / 2.0 + rotRad
            val x = (cx + cos(a) * r).toFloat()
            val y = (cy + sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }

    private fun drawCrossStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotation: Float) {
        starPath.reset()
        val innerRadius = radius * 0.22f
        val points = 4
        val step = PI / points
        val rotRad = Math.toRadians(rotation.toDouble())

        for (i in 0 until (points * 2)) {
            val r = if (i % 2 == 0) radius else innerRadius
            val a = i * step - PI / 2.0 + rotRad
            val x = (cx + cos(a) * r).toFloat()
            val y = (cy + sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }
}

