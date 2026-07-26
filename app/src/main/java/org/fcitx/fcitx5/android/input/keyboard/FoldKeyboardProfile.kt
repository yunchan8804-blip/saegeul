/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

enum class KeyboardScreenProfile {
    Compact,
    Expanded,
    Unknown
}

data class KeyboardViewport(
    val widthDp: Int,
    val heightDp: Int,
    val orientation: Int
)

data class ThumbSplitPreferences(
    val compactEnabled: Boolean,
    val expandedEnabled: Boolean,
    val compactPortraitGapDp: Int,
    val compactLandscapeGapDp: Int,
    val expandedPortraitGapDp: Int,
    val expandedLandscapeGapDp: Int
)

data class ThumbSplitProfile(
    val screenProfile: KeyboardScreenProfile,
    val enabled: Boolean,
    val centerGapDp: Int
)

/**
 * A Fold posture is not always exposed to an IME. Use the official window/configuration sizes
 * and fail closed when they are unavailable. Requiring both dimensions to reach 600dp also keeps
 * an ordinary phone in landscape from being mistaken for an unfolded Fold or tablet.
 */
object FoldKeyboardProfileResolver {
    const val EXPANDED_MIN_SMALLEST_WIDTH_DP = 600

    fun resolve(
        viewport: KeyboardViewport,
        preferences: ThumbSplitPreferences
    ): ThumbSplitProfile {
        val screenProfile = classify(viewport.widthDp, viewport.heightDp)
        val landscape = viewport.orientation == Configuration.ORIENTATION_LANDSCAPE
        val enabled = when (screenProfile) {
            KeyboardScreenProfile.Compact -> preferences.compactEnabled
            KeyboardScreenProfile.Expanded -> preferences.expandedEnabled
            KeyboardScreenProfile.Unknown -> false
        }
        val requestedGap = when (screenProfile) {
            KeyboardScreenProfile.Compact -> if (landscape) {
                preferences.compactLandscapeGapDp
            } else {
                preferences.compactPortraitGapDp
            }
            KeyboardScreenProfile.Expanded -> if (landscape) {
                preferences.expandedLandscapeGapDp
            } else {
                preferences.expandedPortraitGapDp
            }
            KeyboardScreenProfile.Unknown -> 0
        }
        return ThumbSplitProfile(
            screenProfile = screenProfile,
            enabled = enabled,
            centerGapDp = if (enabled) {
                requestedGap.coerceIn(0, viewport.widthDp.coerceAtLeast(0) / 3)
            } else {
                0
            }
        )
    }

    fun classify(widthDp: Int, heightDp: Int): KeyboardScreenProfile = when {
        widthDp <= 0 || heightDp <= 0 -> KeyboardScreenProfile.Unknown
        minOf(widthDp, heightDp) >= EXPANDED_MIN_SMALLEST_WIDTH_DP ->
            KeyboardScreenProfile.Expanded
        else -> KeyboardScreenProfile.Compact
    }
}

/** Android-facing adapter kept separate from the pure resolver for deterministic tests. */
object KeyboardViewportReader {
    fun read(context: Context): KeyboardViewport {
        val configuration = context.resources.configuration
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val metricsSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(WindowManager::class.java)
                    ?.currentWindowMetrics
                    ?.bounds
                    ?.let { bounds ->
                        (bounds.width() / density).roundToInt() to
                            (bounds.height() / density).roundToInt()
                    }
            }.getOrNull()
        } else {
            null
        }
        // Configuration excludes system decorations and is safer for multi-window. WindowMetrics
        // is a second source when a vendor reports either configuration dimension as zero.
        val widthDp = positiveMinimum(configuration.screenWidthDp, metricsSize?.first ?: 0)
        val heightDp = positiveMinimum(configuration.screenHeightDp, metricsSize?.second ?: 0)
        return KeyboardViewport(widthDp, heightDp, configuration.orientation)
    }

    private fun positiveMinimum(first: Int, second: Int): Int = when {
        first > 0 && second > 0 -> minOf(first, second)
        first > 0 -> first
        second > 0 -> second
        else -> 0
    }
}

data class ThumbSplitRowLayout(
    val percentWidths: List<Float>,
    /** Index of the first key on the right side, or -1 when no split can be applied. */
    val boundaryIndex: Int,
    val gapPx: Int
)

/** Pure row geometry used by [BaseKeyboard] and unit tests. */
object ThumbSplitLayoutCalculator {
    fun calculate(
        originalPercentWidths: List<Float>,
        parentWidthPx: Int,
        requestedGapPx: Int
    ): ThumbSplitRowLayout {
        if (originalPercentWidths.size < 2 || parentWidthPx <= 0 || requestedGapPx <= 0) {
            return ThumbSplitRowLayout(originalPercentWidths, -1, 0)
        }
        val gapPx = requestedGapPx.coerceAtMost(parentWidthPx / 3)
        val positiveTotal = originalPercentWidths.filter { it > 0f }.sum()
        val gapFraction = gapPx.toFloat() / parentWidthPx.toFloat()
        val scale = if (positiveTotal > 0f) {
            ((positiveTotal - gapFraction).coerceAtLeast(positiveTotal * 0.55f) / positiveTotal)
                .coerceAtMost(1f)
        } else {
            1f
        }
        return ThumbSplitRowLayout(
            percentWidths = originalPercentWidths.map { width ->
                if (width > 0f) width * scale else width
            },
            boundaryIndex = originalPercentWidths.size / 2,
            gapPx = gapPx
        )
    }
}
