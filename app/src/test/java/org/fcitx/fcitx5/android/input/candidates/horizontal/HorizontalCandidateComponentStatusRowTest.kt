/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.Companion.shouldShowStatusSpinner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HorizontalCandidateComponent.shouldShowStatusSpinner] decides whether the status row's
 * spinner is shown, given the logical state's spinner flag (WARMUP/GENERATING -> true,
 * NO_CANDIDATE/ERROR/WARMUP_FAILED -> false; see computeStatusRowContent) and the user's
 * disable-animation preference. This module has no Robolectric dependency, so the real
 * ProgressBar view/visibility cannot be exercised here — this covers the pure decision logic
 * that drives it instead.
 */
class HorizontalCandidateComponentStatusRowTest {

    @Test
    fun `warmup and generating show the spinner when animation is enabled`() {
        // WARMUP
        assertTrue(shouldShowStatusSpinner(spinner = true, disableAnimation = false))
        // GENERATING (same spinner flag as WARMUP per computeStatusRowContent)
        assertTrue(shouldShowStatusSpinner(spinner = true, disableAnimation = false))
    }

    @Test
    fun `no_candidate error and warmup_failed never show the spinner`() {
        // NO_CANDIDATE / ERROR / WARMUP_FAILED all set spinner = false
        assertFalse(shouldShowStatusSpinner(spinner = false, disableAnimation = false))
        assertFalse(shouldShowStatusSpinner(spinner = false, disableAnimation = true))
    }

    @Test
    fun `disable-animation preference suppresses the spinner even while generating`() {
        assertFalse(shouldShowStatusSpinner(spinner = true, disableAnimation = true))
    }
}
