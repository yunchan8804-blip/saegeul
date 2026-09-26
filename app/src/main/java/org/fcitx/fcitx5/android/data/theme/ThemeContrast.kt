/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlin.math.max
import kotlin.math.min

/**
 * WCAG 2.x contrast ratio checks for theme colors. Pure integer/ARGB math,
 * no Android framework dependency, so it can be exercised without a device.
 */
object ThemeContrast {

    enum class Role { Key, AltKey, AccentKey, Candidate, KeyOverride }

    data class ContrastIssue(
        val role: Role,
        val fg: Int,
        val bg: Int,
        val ratio: Double,
        val keyOverrideKey: String? = null
    )

    private const val INK = 0xFF101827.toInt()
    private const val IVORY = 0xFFFFF9ED.toInt()

    /**
     * WCAG relative-luminance contrast ratio between [fg] and [bg]. If [fg] carries
     * alpha, it is first composited over [bg] (whose own alpha is ignored, treated
     * as the opaque backdrop) before computing luminance.
     */
    fun ratio(fg: Int, bg: Int): Double {
        val effectiveFg = compositeOver(fg, bg)
        val l1 = relativeLuminance(effectiveFg)
        val l2 = relativeLuminance(bg)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Finds every glyph/background pair in [theme] whose contrast ratio is below
     * [minRatio]: the four fixed roles (key, alt key, accent key, candidate/bar),
     * plus every key override (and the global key style) that customizes at least
     * one of text or background color, filling the unset side with the theme default.
     */
    fun findIssues(theme: Theme.Custom, minRatio: Double = 4.5): List<ContrastIssue> {
        val issues = mutableListOf<ContrastIssue>()

        fun check(role: Role, fg: Int, bg: Int, overrideKey: String? = null) {
            val r = ratio(fg, bg)
            if (r < minRatio) {
                issues.add(ContrastIssue(role, fg, bg, r, overrideKey))
            }
        }

        check(Role.Key, theme.keyTextColor, theme.keyBackgroundColor)
        check(Role.AltKey, theme.altKeyTextColor, theme.altKeyBackgroundColor)
        check(Role.AccentKey, theme.accentKeyTextColor, theme.accentKeyBackgroundColor)
        check(Role.Candidate, theme.candidateTextColor, theme.barColor)

        theme.globalKeyStyle?.let { style ->
            if (style.keyTextColor != null || style.keyBackgroundColor != null) {
                val fg = style.keyTextColor ?: theme.keyTextColor
                val bg = style.keyBackgroundColor ?: theme.keyBackgroundColor
                check(Role.KeyOverride, fg, bg, GLOBAL_KEY_STYLE_KEY)
            }
        }

        theme.keyOverrides?.forEach { (key, style) ->
            if (style.keyTextColor != null || style.keyBackgroundColor != null) {
                val fg = style.keyTextColor ?: theme.keyTextColor
                val bg = style.keyBackgroundColor ?: theme.keyBackgroundColor
                check(Role.KeyOverride, fg, bg, key)
            }
        }

        return issues
    }

    /**
     * Replaces the text color of every failing pair found by [findIssues] with
     * whichever of ink (`#101827`) or ivory (`#FFF9ED`) has the higher contrast
     * against that pair's background. Backgrounds, effects and every other field
     * of [theme] are left untouched.
     */
    fun autoFix(theme: Theme.Custom, minRatio: Double = 4.5): Theme.Custom {
        var result = theme
        // Fixing one role's text color can change the fallback another key override
        // inherits (an override with no explicit text color reads the theme's current
        // keyTextColor), so re-check after each pass until nothing is left to fix.
        repeat(MAX_AUTOFIX_PASSES) {
            val issues = findIssues(result, minRatio)
            if (issues.isEmpty()) return result
            for (issue in issues) {
                val newFg = bestTextColor(issue.bg)
                result = when (issue.role) {
                    Role.Key -> result.copy(keyTextColor = newFg)
                    Role.AltKey -> result.copy(altKeyTextColor = newFg)
                    Role.AccentKey -> result.copy(accentKeyTextColor = newFg)
                    Role.Candidate -> result.copy(candidateTextColor = newFg)
                    Role.KeyOverride -> applyKeyOverrideFix(result, issue.keyOverrideKey, newFg)
                }
            }
        }
        return result
    }

    private const val MAX_AUTOFIX_PASSES = 4

    private fun applyKeyOverrideFix(theme: Theme.Custom, overrideKey: String?, newFg: Int): Theme.Custom {
        return when {
            overrideKey == GLOBAL_KEY_STYLE_KEY -> {
                val style = theme.globalKeyStyle ?: Theme.Custom.KeyCustomStyle()
                theme.copy(globalKeyStyle = style.copy(keyTextColor = newFg))
            }
            overrideKey != null -> {
                val overrides = theme.keyOverrides?.toMutableMap() ?: mutableMapOf()
                val style = overrides[overrideKey] ?: Theme.Custom.KeyCustomStyle()
                overrides[overrideKey] = style.copy(keyTextColor = newFg)
                theme.copy(keyOverrides = overrides)
            }
            else -> theme
        }
    }

    private fun bestTextColor(bg: Int): Int {
        val inkRatio = ratio(INK, bg)
        val ivoryRatio = ratio(IVORY, bg)
        return if (inkRatio >= ivoryRatio) INK else IVORY
    }

    private fun compositeOver(fg: Int, bg: Int): Int {
        val a = alphaOf(fg) / 255.0
        if (a >= 1.0) return fg
        val r = (redOf(fg) * a + redOf(bg) * (1 - a)).roundToClampedInt()
        val g = (greenOf(fg) * a + greenOf(bg) * (1 - a)).roundToClampedInt()
        val b = (blueOf(fg) * a + blueOf(bg) * (1 - a)).roundToClampedInt()
        return argb(0xFF, r, g, b)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = channelLuminance(redOf(color))
        val g = channelLuminance(greenOf(color))
        val b = channelLuminance(blueOf(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channelLuminance(byteVal: Int): Double {
        val c = byteVal / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun Double.roundToClampedInt(): Int = Math.round(this).toInt().coerceIn(0, 255)

    private fun alphaOf(c: Int) = (c ushr 24) and 0xFF
    private fun redOf(c: Int) = (c ushr 16) and 0xFF
    private fun greenOf(c: Int) = (c ushr 8) and 0xFF
    private fun blueOf(c: Int) = c and 0xFF
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private const val GLOBAL_KEY_STYLE_KEY = "globalKeyStyle"
}
