/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import kotlin.math.roundToInt

/** One-hand keyboard docking: keeps the keyboard within thumb reach on one side of the screen. */
enum class OneHandMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Off(R.string.disabled),
    Left(R.string.one_hand_mode_left),
    Right(R.string.one_hand_mode_right)
}

/**
 * Computes the keyboard's left/right insets from the user's side padding, one-hand mode, and
 * screen shape. Pure function: no Android view or resource lookups, so it stays unit-testable.
 */
object KeyboardFrame {

    /** Left ([startPx]) and right ([endPx]) inset in pixels, applied to the keyboard, kawaii bar and preedit. */
    data class Insets(val startPx: Int, val endPx: Int)

    /** One-hand keyboard width as a fraction of the space left after the user's side padding. */
    private const val ONE_HAND_WIDTH_RATIO = 0.84f

    /** Landscape keyboard max width, matching the app's max reading-area width. */
    private const val LANDSCAPE_MAX_WIDTH_DP = 640

    /** K20: phone-keypad mobile Hangul surfaces cap out at this width once the screen is wide. */
    private const val MOBILE_HANGUL_MAX_WIDTH_DP = 480

    /** K20: below this screen width, a mobile Hangul surface uses the full width as usual. */
    private const val MOBILE_HANGUL_WIDTH_THRESHOLD_DP = 600

    fun compute(
        windowWidthPx: Int,
        density: Float,
        isLandscape: Boolean,
        userSidePaddingPx: Int,
        oneHandMode: OneHandMode,
        isSplitActive: Boolean,
        isMobileHangulLayout: Boolean = false
    ): Insets {
        // A split keyboard already claims the width between its two halves; one-hand mode and the
        // landscape max-width centering would fight it over the same space, so both are skipped.
        if (isSplitActive) {
            return Insets(userSidePaddingPx, userSidePaddingPx)
        }
        if (oneHandMode != OneHandMode.Off) {
            return oneHandInsets(windowWidthPx, userSidePaddingPx, oneHandMode)
        }
        // K20: a phone-keypad mobile Hangul surface (chunjiin/vega/naratgul/danmoum/moakey) caps
        // its own width regardless of orientation, unlike QWERTY-style surfaces below.
        if (isMobileHangulLayout) {
            mobileHangulMaxWidthInsets(windowWidthPx, density, userSidePaddingPx)?.let { return it }
        }
        if (isLandscape) {
            return landscapeMaxWidthInsets(windowWidthPx, density, userSidePaddingPx)
        }
        return Insets(userSidePaddingPx, userSidePaddingPx)
    }

    private fun landscapeMaxWidthInsets(
        windowWidthPx: Int,
        density: Float,
        userSidePaddingPx: Int
    ): Insets {
        val available = (windowWidthPx - userSidePaddingPx * 2).coerceAtLeast(0)
        val maxWidthPx = (LANDSCAPE_MAX_WIDTH_DP * density).roundToInt()
        if (available <= maxWidthPx) {
            // The user's own margin already keeps the keyboard under the max width; don't add more.
            return Insets(userSidePaddingPx, userSidePaddingPx)
        }
        val extra = (available - maxWidthPx) / 2
        return Insets(userSidePaddingPx + extra, userSidePaddingPx + extra)
    }

    private fun mobileHangulMaxWidthInsets(
        windowWidthPx: Int,
        density: Float,
        userSidePaddingPx: Int
    ): Insets? {
        val widthThresholdPx = MOBILE_HANGUL_WIDTH_THRESHOLD_DP * density
        if (windowWidthPx <= widthThresholdPx) return null
        val available = (windowWidthPx - userSidePaddingPx * 2).coerceAtLeast(0)
        val maxWidthPx = (MOBILE_HANGUL_MAX_WIDTH_DP * density).roundToInt()
        if (available <= maxWidthPx) return null
        val extra = (available - maxWidthPx) / 2
        return Insets(userSidePaddingPx + extra, userSidePaddingPx + extra)
    }

    private fun oneHandInsets(
        windowWidthPx: Int,
        userSidePaddingPx: Int,
        mode: OneHandMode
    ): Insets {
        val available = (windowWidthPx - userSidePaddingPx * 2).coerceAtLeast(0)
        val keyboardWidth = (available * ONE_HAND_WIDTH_RATIO).roundToInt()
        val empty = available - keyboardWidth
        return when (mode) {
            OneHandMode.Left -> Insets(userSidePaddingPx, userSidePaddingPx + empty)
            OneHandMode.Right -> Insets(userSidePaddingPx + empty, userSidePaddingPx)
            OneHandMode.Off -> Insets(userSidePaddingPx, userSidePaddingPx)
        }
    }
}

/** Multiplies a key's base text size (dp) by the user's [AppPrefs.Keyboard.keyTextScale] percentage. */
object KeyTextScale {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 140
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 5

    fun factor(percent: Int): Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f

    fun scale(baseSizeDp: Float, percent: Int): Float = baseSizeDp * factor(percent)
}

/**
 * Keeps key rows tappable when the keyboard height (a percentage of the screen height) is taken
 * from a short screen, such as a foldable's cover screen in landscape.
 */
object KeyboardHeightFloor {
    /** Minimum key row height in portrait, in dp. */
    const val MIN_ROW_DP_PORTRAIT = 40

    /** Minimum key row height in landscape, in dp. */
    const val MIN_ROW_DP_LANDSCAPE = 36

    /** Letter surfaces have four rows; a pinned number row adds a fifth. */
    fun rowCount(numberRowPinned: Boolean, baseRows: Int = 4): Int =
        if (numberRowPinned) baseRows + 1 else baseRows

    fun apply(percentHeightPx: Int, density: Float, isLandscape: Boolean, rows: Int): Int {
        val minRowDp = if (isLandscape) MIN_ROW_DP_LANDSCAPE else MIN_ROW_DP_PORTRAIT
        val floorPx = (rows * minRowDp * density).roundToInt()
        return maxOf(percentHeightPx, floorPx)
    }
}
