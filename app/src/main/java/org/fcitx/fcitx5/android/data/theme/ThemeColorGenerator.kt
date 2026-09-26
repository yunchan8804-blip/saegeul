/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

/**
 * Derives a full [Theme.Custom] palette from a single seed color, using OKLCH:
 * the seed's hue is kept for every role, lightness is a fixed per-role target
 * (except the accent role, which clamps the seed's own lightness into a range),
 * and chroma is capped per role (the accent role keeps the seed's chroma).
 * Values that would fall outside the sRGB gamut are pulled back in by
 * reducing chroma alone via binary search. See `app/design.md` round 2 decision
 * ("테마") for the exact per-role L/C table this mirrors.
 */
object ThemeColorGenerator {

    private const val INK = 0xFF101827.toInt()

    private const val ACCENT_LIGHT_L_MIN = 0.40
    private const val ACCENT_LIGHT_L_MAX = 0.55
    private const val ACCENT_DARK_L_MIN = 0.72
    private const val ACCENT_DARK_L_MAX = 0.85

    fun generate(seedColor: Int, isDark: Boolean, name: String): Theme.Custom {
        val seedOklch = Oklch.colorToOklch(seedColor)
        val hue = seedOklch.h
        val seedChroma = seedOklch.C

        fun role(lightL: Double, lightC: Double, darkL: Double, darkC: Double): Int {
            val targetL = if (isDark) darkL else lightL
            val cap = if (isDark) darkC else lightC
            val chroma = minOf(seedChroma, cap)
            return fitToSrgb(targetL, chroma, hue)
        }

        val keyboardBg = role(0.93, 0.020, 0.20, 0.040)
        val keyBg = role(0.985, 0.010, 0.28, 0.040)
        val keyText = role(0.20, 0.030, 0.97, 0.015)
        val altKeyBg = role(0.88, 0.025, 0.24, 0.040)
        val altKeyText = role(0.35, 0.030, 0.82, 0.020)
        val candidateLabel = role(0.45, 0.030, 0.78, 0.020)
        val popupBg = role(0.99, 0.010, 0.33, 0.040)
        val divider = role(0.85, 0.020, 0.32, 0.040)
        val shadow = role(0.80, 0.020, 0.12, 0.030)

        val accentLMin = if (isDark) ACCENT_DARK_L_MIN else ACCENT_LIGHT_L_MIN
        val accentLMax = if (isDark) ACCENT_DARK_L_MAX else ACCENT_LIGHT_L_MAX
        val accentL = seedOklch.L.coerceIn(accentLMin, accentLMax)
        val accentBg = fitToSrgb(accentL, seedChroma, hue)
        val accentText = bestOnAccent(accentBg)

        val pressAlphaFraction = if (isDark) 0.18 else 0.12
        val pressColor = withAlpha(keyText, pressAlphaFraction)

        return Theme.Custom(
            name = name,
            isDark = isDark,
            backgroundImage = null,
            backgroundColor = keyboardBg,
            barColor = keyboardBg,
            keyboardColor = keyboardBg,
            keyBackgroundColor = keyBg,
            keyTextColor = keyText,
            candidateTextColor = keyText,
            candidateLabelColor = candidateLabel,
            candidateCommentColor = candidateLabel,
            altKeyBackgroundColor = altKeyBg,
            altKeyTextColor = altKeyText,
            accentKeyBackgroundColor = accentBg,
            accentKeyTextColor = accentText,
            keyPressHighlightColor = pressColor,
            keyShadowColor = shadow,
            popupBackgroundColor = popupBg,
            popupTextColor = keyText,
            spaceBarColor = keyBg,
            dividerColor = divider,
            clipboardEntryColor = keyBg,
            genericActiveBackgroundColor = accentBg,
            genericActiveForegroundColor = accentText,
            keyOverrides = null,
            globalKeyStyle = null,
            lightingEffect = null,
            particleEffect = null,
            keyGlowEffect = null
        )
    }

    private fun bestOnAccent(bg: Int): Int {
        val whiteRatio = ThemeContrast.ratio(0xFFFFFFFF.toInt(), bg)
        val inkRatio = ThemeContrast.ratio(INK, bg)
        return if (whiteRatio >= inkRatio) 0xFFFFFFFF.toInt() else INK
    }

    private fun withAlpha(color: Int, alphaFraction: Double): Int {
        val alphaByte = Math.round(alphaFraction * 255).toInt().coerceIn(0, 255)
        val rgb = color and 0x00FFFFFF
        return (alphaByte shl 24) or rgb
    }

    /** Keeps [L] and [h] fixed, reducing [c] via binary search until the color is in-gamut. */
    private fun fitToSrgb(L: Double, c: Double, h: Double): Int {
        if (isInGamut(L, c, h)) return Oklch.oklchToColor(Oklch.OklchColor(L, c, h))
        var lo = 0.0
        var hi = c
        repeat(BINARY_SEARCH_STEPS) {
            val mid = (lo + hi) / 2.0
            if (isInGamut(L, mid, h)) lo = mid else hi = mid
        }
        return Oklch.oklchToColor(Oklch.OklchColor(L, lo, h))
    }

    private fun isInGamut(L: Double, c: Double, h: Double): Boolean {
        val lab = Oklch.oklchToOklab(Oklch.OklchColor(L, c, h))
        val (r, g, b) = Oklch.oklabToLinearRgb(lab)
        return r in GAMUT_RANGE && g in GAMUT_RANGE && b in GAMUT_RANGE
    }

    private const val BINARY_SEARCH_STEPS = 24
    private val GAMUT_RANGE = -1e-4..(1.0 + 1e-4)
}
