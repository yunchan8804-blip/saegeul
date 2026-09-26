/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * sRGB <-> OKLab <-> OKLCH color space conversion, following Björn Ottosson's
 * OKLab reference implementation. No external dependency; used to derive a
 * full theme palette from a single seed color (see [ThemeColorGenerator]).
 */
object Oklch {

    data class Oklab(val L: Double, val a: Double, val b: Double)

    data class OklchColor(val L: Double, val C: Double, val h: Double)

    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    fun linearToSrgb(c: Double): Double =
        if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055

    fun rgbToOklab(r: Double, g: Double, b: Double): Oklab {
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

        val l_ = Math.cbrt(l)
        val m_ = Math.cbrt(m)
        val s_ = Math.cbrt(s)

        val L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
        val a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
        val bOut = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
        return Oklab(L, a, bOut)
    }

    /** Inverse of [rgbToOklab]. Returns *linear* (not gamma-encoded) sRGB, unclamped. */
    fun oklabToLinearRgb(lab: Oklab): Triple<Double, Double, Double> {
        val l_ = lab.L + 0.3963377774 * lab.a + 0.2158037573 * lab.b
        val m_ = lab.L - 0.1055613458 * lab.a - 0.0638541728 * lab.b
        val s_ = lab.L - 0.0894841775 * lab.a - 1.2914855480 * lab.b

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return Triple(r, g, b)
    }

    fun oklabToOklch(lab: Oklab): OklchColor {
        val c = sqrt(lab.a * lab.a + lab.b * lab.b)
        var h = Math.toDegrees(atan2(lab.b, lab.a))
        if (h < 0.0) h += 360.0
        return OklchColor(lab.L, c, h)
    }

    fun oklchToOklab(c: OklchColor): Oklab {
        val hRad = Math.toRadians(c.h)
        return Oklab(c.L, c.C * cos(hRad), c.C * sin(hRad))
    }

    /** Converts an opaque sRGB color (alpha channel of [argb] is ignored) to OKLCH. */
    fun colorToOklch(argb: Int): OklchColor {
        val r = ((argb ushr 16) and 0xFF) / 255.0
        val g = ((argb ushr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val lab = rgbToOklab(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
        return oklabToOklch(lab)
    }

    /** Converts OKLCH to an opaque ARGB int, clamping each channel to valid sRGB range. */
    fun oklchToColor(c: OklchColor, alpha: Int = 0xFF): Int {
        val lab = oklchToOklab(c)
        val (rl, gl, bl) = oklabToLinearRgb(lab)
        val r = clampChannel(linearToSrgb(rl))
        val g = clampChannel(linearToSrgb(gl))
        val b = clampChannel(linearToSrgb(bl))
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clampChannel(v: Double): Int =
        Math.round(v.coerceIn(0.0, 1.0) * 255).toInt().coerceIn(0, 255)
}
