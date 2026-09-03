/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * High-performance, Hardware-accelerated RGB Chroma Backlight & Reactive Mechanical Keyboard Animation Engine.
 * Supports 16+ signature ambient and reactive lighting modes synchronized via Choreographer.
 */
class RgbChromaEffectView(
    context: Context,
    var effectDef: Theme.Custom.LightingEffectDef? = null
) : View(context), Choreographer.FrameCallback {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shaderMatrix = Matrix()
    private val tempRect = RectF()
    private val starPath = Path()

    private var phase = 0f
    private var lastFrameTimeMs = 0L
    private var isFrameCallbackPosted = false

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
        Color.parseColor("#002200"),
        Color.parseColor("#00AA44"),
        Color.parseColor("#00FF66"),
        Color.parseColor("#66FFAA"),
        Color.parseColor("#00FF66"),
        Color.parseColor("#002200")
    )

    private val auroraColors = intArrayOf(
        Color.parseColor("#00FF88"), // Emerald Glow
        Color.parseColor("#00E5FF"), // Cyan Sky
        Color.parseColor("#7C3AED"), // Royal Violet
        Color.parseColor("#EC4899"), // Magenta Pink
        Color.parseColor("#00FF88")
    )

    private val oceanColors = intArrayOf(
        Color.parseColor("#0A192F"), // Deep Abyss
        Color.parseColor("#0284C7"), // Ocean Blue
        Color.parseColor("#00E5FF"), // Bright Cyan
        Color.parseColor("#10B981"), // Emerald Foam
        Color.parseColor("#0A192F")
    )

    private val fireColors = intArrayOf(
        Color.parseColor("#7F1D1D"), // Deep Crimson
        Color.parseColor("#DC2626"), // Bright Red
        Color.parseColor("#EA580C"), // Fiery Orange
        Color.parseColor("#FBBF24"), // Gold Ember
        Color.parseColor("#DC2626"),
        Color.parseColor("#7F1D1D")
    )

    private val sakuraColors = intArrayOf(
        Color.parseColor("#FDA4AF"), // Soft Rose
        Color.parseColor("#F472B6"), // Sakura Pink
        Color.parseColor("#DDD6FE"), // Pastel Lavender
        Color.parseColor("#FED7AA"), // Warm Peach
        Color.parseColor("#FDA4AF")
    )

    private val frostColors = intArrayOf(
        Color.parseColor("#0EA5E9"), // Frost Blue
        Color.parseColor("#67E8F9"), // Ice Cyan
        Color.parseColor("#E0F2FE"), // Glaze White
        Color.parseColor("#A5B4FC"), // Crystal Indigo
        Color.parseColor("#0EA5E9")
    )

    private val supernovaColors = intArrayOf(
        Color.parseColor("#6D28D9"), // Galactic Violet
        Color.parseColor("#EC4899"), // Nebula Pink
        Color.parseColor("#38BDF8"), // Starburst Cyan
        Color.parseColor("#FDE047"), // Solar Gold
        Color.parseColor("#6D28D9")
    )

    // Starlight fixed star points
    private class StarPoint(val xRatio: Float, val yRatio: Float, val size: Float, val phaseOffset: Float, val color: Int)
    private val starPoints = List(36) { index ->
        val rand = Random(index * 1337 + 42)
        StarPoint(
            xRatio = rand.nextFloat(),
            yRatio = rand.nextFloat(),
            size = rand.nextFloat() * 3.5f + 1.5f,
            phaseOffset = rand.nextFloat() * (PI * 2).toFloat(),
            color = when (index % 4) {
                0 -> Color.parseColor("#FFFFFF")
                1 -> Color.parseColor("#67E8F9")
                2 -> Color.parseColor("#FDE047")
                else -> Color.parseColor("#F472B6")
            }
        )
    }

    // Matrix Rain column state
    private class MatrixColumn(var xRatio: Float, var yProgress: Float, var speed: Float, var length: Float)
    private val matrixColumns = List(28) { index ->
        val rand = Random(index * 997 + 17)
        MatrixColumn(
            xRatio = (index + 0.5f) / 28f,
            yProgress = rand.nextFloat(),
            speed = rand.nextFloat() * 0.4f + 0.3f,
            length = rand.nextFloat() * 0.4f + 0.25f
        )
    }

    // Reactive Touch Events (Mechanical Keyboard RGB Pulse / Ripple / Trace)
    data class ReactiveEvent(
        val x: Float,
        val y: Float,
        val startTimeMs: Long,
        val durationMs: Long,
        val type: String,
        val color: Int
    )

    private val reactiveEvents = CopyOnWriteArrayList<ReactiveEvent>()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        updateEffect(effectDef)
    }

    fun updateEffect(newDef: Theme.Custom.LightingEffectDef?) {
        effectDef = newDef
        val mode = newDef?.mode ?: "off"
        if (mode == "off" || newDef == null) {
            stopLoop()
            visibility = GONE
            invalidate()
            return
        }

        visibility = VISIBLE
        startLoop()
        invalidate()
    }

    /**
     * Trigger reactive per-key mechanical keyboard RGB animations upon touch/keypress.
     */
    fun spawnReactiveKeyEffect(x: Float, y: Float) {
        val def = effectDef ?: return
        val mode = def.mode
        if (mode == "off") return

        val now = SystemClock.uptimeMillis()
        val palette = getActivePalette(mode, def)
        val colorIndex = ((phase * palette.size).toInt()) % palette.size
        val dynamicColor = palette[colorIndex]

        val effectType = when (mode) {
            "reactive_ripple" -> "ripple"
            "reactive_fade" -> "fade"
            "reactive_firework" -> "firework"
            "reactive_laser" -> "laser"
            else -> "ripple" // General subtle ripple for ambient modes
        }

        val duration = when (effectType) {
            "ripple" -> 600L
            "fade" -> 500L
            "firework" -> 450L
            "laser" -> 400L
            else -> 500L
        }

        reactiveEvents.add(
            ReactiveEvent(
                x = x,
                y = y,
                startTimeMs = now,
                durationMs = duration,
                type = effectType,
                color = dynamicColor
            )
        )

        startLoop()
        invalidate()
    }

    private fun startLoop() {
        if (!isFrameCallbackPosted && isAttachedToWindow && visibility == VISIBLE) {
            lastFrameTimeMs = SystemClock.uptimeMillis()
            isFrameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopLoop() {
        if (isFrameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            isFrameCallbackPosted = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (effectDef != null && effectDef?.mode != "off") {
            startLoop()
        }
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && effectDef != null && effectDef?.mode != "off") {
            startLoop()
        } else {
            stopLoop()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        isFrameCallbackPosted = false
        val def = effectDef
        if (def == null || def.mode == "off" || visibility != VISIBLE || !isAttachedToWindow) {
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTimeMs > 0L) {
            ((now - lastFrameTimeMs).coerceIn(1L, 100L)) / 1000f
        } else {
            0.016f
        }
        lastFrameTimeMs = now

        val speed = def.speed.coerceIn(0.2f, 4.0f)
        val phaseDelta = (dt * speed * 0.4f)
        phase = (phase + phaseDelta) % 1.0f

        // Advance Matrix rain columns
        if (def.mode == "matrix_flow") {
            for (col in matrixColumns) {
                col.yProgress = (col.yProgress + dt * col.speed * speed * 0.6f) % 1.2f
            }
        }

        // Clean up expired reactive events
        if (reactiveEvents.isNotEmpty()) {
            val iterator = reactiveEvents.iterator()
            while (iterator.hasNext()) {
                val ev = iterator.next()
                if (now - ev.startTimeMs >= ev.durationMs) {
                    reactiveEvents.remove(ev)
                }
            }
        }

        invalidate()

        // Continue animation loop
        if (isAttachedToWindow && visibility == VISIBLE) {
            isFrameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun getActivePalette(mode: String, def: Theme.Custom.LightingEffectDef): IntArray {
        return when (mode) {
            "cyberpunk" -> cyberpunkColors
            "matrix_flow" -> matrixColors
            "aurora" -> auroraColors
            "ocean_tide" -> oceanColors
            "fire_ember" -> fireColors
            "sakura_breeze" -> sakuraColors
            "frost_crystal" -> frostColors
            "supernova" -> supernovaColors
            else -> def.customColors?.toIntArray() ?: rainbowColors
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
        val palette = getActivePalette(mode, def)
        val isRightToLeft = def.direction == "right_to_left"

        // 1. Render Ambient Base Lighting Layer
        when (mode) {
            "rgb_wave" -> renderRainbowWave(canvas, w, h, palette, intensity, isRightToLeft)
            "rgb_breathe" -> renderBreathing(canvas, w, h, palette, intensity)
            "cyberpunk" -> renderCyberpunk(canvas, w, h, palette, intensity, isRightToLeft)
            "matrix_flow" -> renderMatrixFlow(canvas, w, h, intensity)
            "neon_pulse" -> renderNeonPulse(canvas, w, h, palette, intensity, isRightToLeft)
            "aurora" -> renderAurora(canvas, w, h, palette, intensity)
            "starlight" -> renderStarlight(canvas, w, h, intensity)
            "ocean_tide" -> renderOceanTide(canvas, w, h, palette, intensity)
            "fire_ember" -> renderFireEmber(canvas, w, h, palette, intensity)
            "supernova" -> renderSupernova(canvas, w, h, palette, intensity)
            "sakura_breeze" -> renderSakuraBreeze(canvas, w, h, palette, intensity)
            "frost_crystal" -> renderFrostCrystal(canvas, w, h, palette, intensity)
            "reactive_ripple", "reactive_fade", "reactive_firework", "reactive_laser" -> {
                // Subtle glowing ambient base for reactive modes
                renderSubtleReactiveBase(canvas, w, h, palette, (intensity * 0.35f).toInt())
            }
            else -> renderRainbowWave(canvas, w, h, palette, intensity, isRightToLeft)
        }

        // 2. Render Reactive Mechanical Keypress Pulses
        renderReactiveEvents(canvas, w, h, palette)
    }

    /**
     * 🌈 Rainbow Wave: 360-degree seamless linear spectrum flow.
     */
    private fun renderRainbowWave(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int, rtl: Boolean) {
        val shift = if (rtl) (1f - phase) * w else phase * w
        val x0 = shift - w
        val x1 = shift + w

        val shader = LinearGradient(
            x0, 0f, x1, 0f,
            palette,
            null,
            Shader.TileMode.REPEAT
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 💓 Breathing: Luxury S-curve breath flow with 100% smooth color interpolation and dual radial ambiance.
     */
    private fun renderBreathing(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        // Smooth Cosine S-Curve breath envelope: [0.15 .. 1.0]
        val breathEnvelope = ((1.0 - cos(phase * PI * 2.0)) / 2.0).toFloat()
        val currentAlpha = (breathEnvelope * intensity).toInt().coerceIn(30, 255)

        // Seamless interpolated color transition between palette points
        val floatIndex = phase * (palette.size - 1)
        val idx1 = floatIndex.toInt() % palette.size
        val idx2 = (idx1 + 1) % palette.size
        val fraction = (floatIndex - idx1.toFloat()).coerceIn(0f, 1f)
        val blendedColor = ColorUtils.blendARGB(palette[idx1], palette[idx2], fraction)

        // Center primary radiant glow
        val cx = w / 2f
        val cy = h / 2f
        val radius = max(w, h) * 0.75f

        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(blendedColor, currentAlpha),
                ColorUtils.setAlphaComponent(blendedColor, (currentAlpha * 0.5f).toInt()),
                ColorUtils.setAlphaComponent(blendedColor, 0)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        fillPaint.shader = shader
        fillPaint.alpha = 255
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * ⚡ Cyberpunk Neon: Diagonal dual-wave neon pulse.
     */
    private fun renderCyberpunk(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int, rtl: Boolean) {
        val shift = if (rtl) (1f - phase) * (w + h) else phase * (w + h)
        val shader = LinearGradient(
            shift - (w + h), 0f, shift + (w + h), h,
            palette,
            null,
            Shader.TileMode.REPEAT
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🟢 Matrix Flow: Vertical cyber code rain droplets and glowing streams.
     */
    private fun renderMatrixFlow(canvas: Canvas, w: Float, h: Float, intensity: Int) {
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#021208")
        fillPaint.alpha = (intensity * 0.5f).toInt()
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        strokePaint.strokeCap = Paint.Cap.ROUND
        for (col in matrixColumns) {
            val cx = col.xRatio * w
            val headY = col.yProgress * h
            val tailY = max(0f, headY - col.length * h)

            val grad = LinearGradient(
                cx, tailY, cx, headY,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#00FF66"), Color.parseColor("#FFFFFF")),
                floatArrayOf(0f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
            strokePaint.shader = grad
            strokePaint.strokeWidth = 3f * resources.displayMetrics.density
            strokePaint.alpha = intensity
            canvas.drawLine(cx, tailY, cx, headY, strokePaint)
        }
    }

    /**
     * 💫 Neon Pulse: High-luminance horizontal scanning laser line.
     */
    private fun renderNeonPulse(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int, rtl: Boolean) {
        val pulseX = if (rtl) (1f - phase) * w else phase * w
        val beamWidth = w * 0.35f
        val color1 = palette[0]
        val color2 = palette[min(1, palette.size - 1)]

        val shader = LinearGradient(
            pulseX - beamWidth, 0f, pulseX + beamWidth, 0f,
            intArrayOf(Color.TRANSPARENT, color1, color2, Color.WHITE, color2, color1, Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.45f, 0.5f, 0.55f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🌌 Aurora Borealis: Organic flowing wave of emerald and royal violet curtains.
     */
    private fun renderAurora(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val waveOffset1 = sin(phase * PI * 2.0).toFloat() * w * 0.25f
        val waveOffset2 = cos(phase * PI * 2.0).toFloat() * h * 0.3f

        val shader = LinearGradient(
            w * 0.2f + waveOffset1, 0f,
            w * 0.8f - waveOffset1, h + waveOffset2,
            palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * ⭐ Starlight Twinkle: Deep midnight sky with glittering constellation stars.
     */
    private fun renderStarlight(canvas: Canvas, w: Float, h: Float, intensity: Int) {
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#050814")
        fillPaint.alpha = (intensity * 0.6f).toInt()
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        fillPaint.shader = null
        for (star in starPoints) {
            val starX = star.xRatio * w
            val starY = star.yRatio * h
            val twinkle = ((sin(phase * PI * 4.0 + star.phaseOffset) + 1.0) / 2.0).toFloat()
            val starAlpha = (twinkle * intensity).toInt().coerceIn(0, 255)
            if (starAlpha <= 0) continue

            fillPaint.color = star.color
            fillPaint.alpha = starAlpha
            val curRadius = star.size * (0.6f + twinkle * 0.6f) * resources.displayMetrics.density
            drawStar(canvas, starX, starY, curRadius, phase * 360f + star.phaseOffset)
        }
    }

    /**
     * 🌊 Ocean Tide: Deep abyss wave surging with emerald tide and white surf.
     */
    private fun renderOceanTide(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val tideProgress = sin(phase * PI * 2.0).toFloat() * 0.3f + 0.5f
        val shader = LinearGradient(
            0f, h * (1f - tideProgress),
            w, h * tideProgress,
            palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🔥 Fire Ember: Rising fiery furnace flame from bottom to top.
     */
    private fun renderFireEmber(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val flameShift = (1f - phase) * h
        val shader = LinearGradient(
            0f, flameShift + h,
            0f, flameShift - h,
            palette,
            null,
            Shader.TileMode.REPEAT
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🪐 Cosmic Supernova: Rotating spiral galaxy nebula.
     */
    private fun renderSupernova(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val sweep = SweepGradient(cx, cy, palette, null)
        shaderMatrix.reset()
        shaderMatrix.postRotate(phase * 360f, cx, cy)
        sweep.setLocalMatrix(shaderMatrix)

        fillPaint.shader = sweep
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🌸 Pastel Sakura: Calming soft spring breeze gradient.
     */
    private fun renderSakuraBreeze(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val shift = phase * w
        val shader = LinearGradient(
            shift - w, 0f, shift + w, h,
            palette,
            null,
            Shader.TileMode.REPEAT
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🧊 Frost Crystal: Shimmering diamond glaze reflections.
     */
    private fun renderFrostCrystal(canvas: Canvas, w: Float, h: Float, palette: IntArray, intensity: Int) {
        val shift = (sin(phase * PI * 2.0).toFloat() * 0.5f + 0.5f) * w
        val shader = LinearGradient(
            shift - w * 0.5f, 0f, shift + w * 0.5f, h,
            palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = intensity
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * Subtle Ambient Glow for Reactive Modes.
     */
    private fun renderSubtleReactiveBase(canvas: Canvas, w: Float, h: Float, palette: IntArray, alpha: Int) {
        val shader = LinearGradient(
            0f, 0f, w, h,
            palette,
            null,
            Shader.TileMode.MIRROR
        )
        fillPaint.shader = shader
        fillPaint.alpha = alpha
        canvas.drawRect(0f, 0f, w, h, fillPaint)
    }

    /**
     * 🎯 Render Reactive Per-Key Mechanical Keyboard Pulses (Ripple, Fade, Firework, Laser).
     */
    private fun renderReactiveEvents(canvas: Canvas, w: Float, h: Float, palette: IntArray) {
        if (reactiveEvents.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density

        for (ev in reactiveEvents) {
            val elapsed = now - ev.startTimeMs
            if (elapsed >= ev.durationMs || elapsed < 0L) continue
            val progress = (elapsed.toFloat() / ev.durationMs.toFloat()).coerceIn(0f, 1f)
            val alpha = ((1.0f - progress) * 255).toInt().coerceIn(0, 255)

            when (ev.type) {
                "ripple" -> {
                    // Circular expanding ripple wave
                    val maxRadius = max(w, h) * 0.85f
                    val curRadius = progress * maxRadius
                    val strokeWidth = (6f + (1f - progress) * 14f) * density

                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = strokeWidth
                    canvas.drawCircle(ev.x, ev.y, curRadius, strokePaint)

                    // Secondary inner radiant flash
                    if (progress < 0.4f) {
                        val innerProgress = progress / 0.4f
                        val innerAlpha = ((1.0f - innerProgress) * 180).toInt()
                        fillPaint.shader = null
                        fillPaint.color = Color.WHITE
                        fillPaint.alpha = innerAlpha
                        canvas.drawCircle(ev.x, ev.y, 25f * density * (1f + innerProgress), fillPaint)
                    }
                }
                "fade" -> {
                    // Key glow spot that gently fades out
                    val spotRadius = (32f + progress * 24f) * density
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
                    // Starburst sparks in 8 radial rays
                    val sparkDist = progress * 70f * density
                    val rayCount = 8
                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = 2.5f * density

                    for (i in 0 until rayCount) {
                        val angle = (i * PI * 2.0 / rayCount).toFloat()
                        val rx1 = ev.x + cos(angle) * (sparkDist * 0.2f)
                        val ry1 = ev.y + sin(angle) * (sparkDist * 0.2f)
                        val rx2 = ev.x + cos(angle) * sparkDist
                        val ry2 = ev.y + sin(angle) * sparkDist
                        canvas.drawLine(rx1, ry1, rx2, ry2, strokePaint)
                    }
                }
                "laser" -> {
                    // Horizontal & vertical cross laser line
                    val laserAlpha = ((1.0f - progress) * 230).toInt()
                    strokePaint.shader = null
                    strokePaint.color = ev.color
                    strokePaint.alpha = laserAlpha
                    strokePaint.strokeWidth = 3f * density

                    // Horizontal laser beam
                    canvas.drawLine(0f, ev.y, w, ev.y, strokePaint)
                    // Vertical laser beam
                    canvas.drawLine(ev.x, 0f, ev.x, h, strokePaint)

                    // Bright core at intersection
                    fillPaint.shader = null
                    fillPaint.color = Color.WHITE
                    fillPaint.alpha = laserAlpha
                    canvas.drawCircle(ev.x, ev.y, 8f * density, fillPaint)
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
        canvas.drawPath(starPath, fillPaint)
    }
}
