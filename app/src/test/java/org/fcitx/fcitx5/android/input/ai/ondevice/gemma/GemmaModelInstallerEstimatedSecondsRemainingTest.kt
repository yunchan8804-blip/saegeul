/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaModelInstallerEstimatedSecondsRemainingTest {

    @Test
    fun nullWhenSpeedIsZero() {
        val state = GemmaInstallState.Downloading(500L, 1000L, bytesPerSecond = 0L, allowMobileData = false)
        assertNull(GemmaModelInstaller.estimatedSecondsRemaining(state))
    }

    @Test
    fun dividesRemainingBytesBySpeed() {
        val state = GemmaInstallState.Downloading(400L, 1000L, bytesPerSecond = 100L, allowMobileData = false)
        assertEquals(6L, GemmaModelInstaller.estimatedSecondsRemaining(state))
    }

    @Test
    fun zeroWhenNothingRemains() {
        val state = GemmaInstallState.Downloading(1000L, 1000L, bytesPerSecond = 100L, allowMobileData = false)
        assertEquals(0L, GemmaModelInstaller.estimatedSecondsRemaining(state))
    }
}
