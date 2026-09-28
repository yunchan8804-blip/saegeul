/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.graphics.Color
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Signature Chroma palettes keyed by ambient mode id.
 */
internal object ChromaPalettes {

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

    /** Palette for [mode]; modes without a signature palette use the theme's custom colors or the rainbow. */
    fun forMode(mode: String, def: Theme.Custom.LightingEffectDef): IntArray {
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
}
