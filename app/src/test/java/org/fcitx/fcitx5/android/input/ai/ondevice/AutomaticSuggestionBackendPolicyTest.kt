/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.ondevice.AutomaticSuggestionBackendPolicy.Choice
import org.fcitx.fcitx5.android.input.ai.ondevice.AutomaticSuggestionBackendPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSuggestionBackendPolicyTest {

    @Test
    fun `cold engine with visible keyboard warms up on cpu`() {
        val decision = AutomaticSuggestionBackendPolicy.choose(
            gpuEnabled = true,
            engineWarm = false,
            keyboardVisible = true
        )
        assertEquals(Choice.Cpu, decision.choice)
        assertEquals(Reason.COLD_KEYBOARD_VISIBLE, decision.reason)
    }

    @Test
    fun `cold engine with hidden keyboard uses gpu`() {
        val decision = AutomaticSuggestionBackendPolicy.choose(
            gpuEnabled = true,
            engineWarm = false,
            keyboardVisible = false
        )
        assertEquals(Choice.Gpu, decision.choice)
        assertEquals(Reason.COLD_KEYBOARD_HIDDEN, decision.reason)
    }

    @Test
    fun `warm engine keeps asking for gpu whatever the keyboard state`() {
        for (visible in listOf(true, false)) {
            val decision = AutomaticSuggestionBackendPolicy.choose(
                gpuEnabled = true,
                engineWarm = true,
                keyboardVisible = visible
            )
            assertEquals(Choice.Gpu, decision.choice)
            assertEquals(Reason.ENGINE_WARM, decision.reason)
        }
    }

    @Test
    fun `gpu setting off always chooses cpu`() {
        for (warm in listOf(true, false)) {
            for (visible in listOf(true, false)) {
                val decision = AutomaticSuggestionBackendPolicy.choose(
                    gpuEnabled = false,
                    engineWarm = warm,
                    keyboardVisible = visible
                )
                assertEquals(Choice.Cpu, decision.choice)
                assertEquals(Reason.GPU_DISABLED, decision.reason)
            }
        }
    }

    @Test
    fun `hiding the keyboard releases a held cpu use when gpu is enabled`() {
        assertTrue(
            AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = true,
                heldFlavor = OnDeviceSharedEngine.Flavor.Cpu(threadCount = AutomaticSuggestionBackendPolicy.CPU_THREAD_COUNT),
                keyboardVisible = false
            )
        )
    }

    @Test
    fun `release is skipped while the keyboard is visible`() {
        assertFalse(
            AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = true,
                heldFlavor = OnDeviceSharedEngine.Flavor.Cpu(),
                keyboardVisible = true
            )
        )
    }

    @Test
    fun `release is skipped when nothing or a gpu engine is held`() {
        assertFalse(
            AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = true,
                heldFlavor = null,
                keyboardVisible = false
            )
        )
        assertFalse(
            AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = true,
                heldFlavor = OnDeviceSharedEngine.Flavor.Gpu,
                keyboardVisible = false
            )
        )
    }

    @Test
    fun `release is skipped when the gpu setting is off`() {
        assertFalse(
            AutomaticSuggestionBackendPolicy.shouldReleaseCpuForGpu(
                gpuEnabled = false,
                heldFlavor = OnDeviceSharedEngine.Flavor.Cpu(),
                keyboardVisible = false
            )
        )
    }
}
