/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAutomaticLearningPolicyTest {

    @Test
    fun forcesDisableWhenEnabledButTheModelIsMissing() {
        assertTrue(GemmaAutomaticLearningPolicy.shouldForceDisableAutomaticLearning(modelInstalled = false, currentlyEnabled = true))
    }

    @Test
    fun doesNotDisableWhenAlreadyDisabled() {
        assertFalse(GemmaAutomaticLearningPolicy.shouldForceDisableAutomaticLearning(modelInstalled = false, currentlyEnabled = false))
    }

    @Test
    fun doesNotDisableWhenTheModelIsPresent() {
        assertFalse(GemmaAutomaticLearningPolicy.shouldForceDisableAutomaticLearning(modelInstalled = true, currentlyEnabled = true))
    }

    @Test
    fun autoEnablesAfterInstallWhenNotAlreadyEnabledAndNotOptedOut() {
        assertTrue(GemmaAutomaticLearningPolicy.shouldAutoEnableAfterInstall(currentlyEnabled = false, userOptedOut = false))
    }

    @Test
    fun doesNotAutoEnableWhenTheUserPreviouslyOptedOut() {
        assertFalse(GemmaAutomaticLearningPolicy.shouldAutoEnableAfterInstall(currentlyEnabled = false, userOptedOut = true))
    }

    @Test
    fun doesNotAutoEnableWhenAlreadyEnabled() {
        assertFalse(GemmaAutomaticLearningPolicy.shouldAutoEnableAfterInstall(currentlyEnabled = true, userOptedOut = false))
    }
}
