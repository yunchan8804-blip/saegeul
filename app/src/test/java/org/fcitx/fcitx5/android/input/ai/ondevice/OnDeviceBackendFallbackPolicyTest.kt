/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceBackendFallbackPolicyTest {

    @Test
    fun `gpu engine initialization failure falls back when not yet used`() {
        assertTrue(
            OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
                errorCode = "ENGINE_INITIALIZATION_FAILED",
                useGpu = true,
                fallbackAlreadyUsed = false
            )
        )
    }

    @Test
    fun `cpu failure never falls back`() {
        assertFalse(
            OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
                errorCode = "ENGINE_INITIALIZATION_FAILED",
                useGpu = false,
                fallbackAlreadyUsed = false
            )
        )
    }

    @Test
    fun `a second occurrence does not fall back again`() {
        assertFalse(
            OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
                errorCode = "ENGINE_INITIALIZATION_FAILED",
                useGpu = true,
                fallbackAlreadyUsed = true
            )
        )
    }
}
