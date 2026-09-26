/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareTierProfilerTest {

    @Test
    fun testHighEndTierCategorization() {
        val ram16gb = 16L * 1024 * 1024 * 1024
        val profile16 = HardwareTierProfiler.profile(ram16gb, cpuCores = 8)
        assertEquals(HardwareTier.HIGH_END, profile16.tier)
        assertEquals(HardwareTierProfiler.MODEL_HIGH_END, profile16.recommendedModel)
        assertEquals(8, profile16.cpuCores)
        assertEquals(ram16gb, profile16.totalRamBytes)

        // 10GB exact boundary condition
        val ram10gb = 10L * 1024 * 1024 * 1024
        val profile10 = HardwareTierProfiler.profile(ram10gb, cpuCores = 8)
        assertEquals(HardwareTier.HIGH_END, profile10.tier)
        assertEquals(HardwareTierProfiler.MODEL_HIGH_END, profile10.recommendedModel)
    }

    @Test
    fun testMidRangeTierCategorization() {
        val ram8gb = 8L * 1024 * 1024 * 1024
        val profile8 = HardwareTierProfiler.profile(ram8gb, cpuCores = 6)
        assertEquals(HardwareTier.MID_RANGE, profile8.tier)
        assertEquals(HardwareTierProfiler.MODEL_MID_RANGE, profile8.recommendedModel)
        assertEquals(6, profile8.cpuCores)

        // 6GB exact boundary condition
        val ram6gb = 6L * 1024 * 1024 * 1024
        val profile6 = HardwareTierProfiler.profile(ram6gb, cpuCores = 4)
        assertEquals(HardwareTier.MID_RANGE, profile6.tier)
        assertEquals(HardwareTierProfiler.MODEL_MID_RANGE, profile6.recommendedModel)

        // Just below 10GB boundary
        val ram9_9gb = (10L * 1024 * 1024 * 1024) - 1
        val profile9_9 = HardwareTierProfiler.profile(ram9_9gb)
        assertEquals(HardwareTier.MID_RANGE, profile9_9.tier)
    }

    @Test
    fun testLowEndTierCategorization() {
        val ram4gb = 4L * 1024 * 1024 * 1024
        val profile4 = HardwareTierProfiler.profile(ram4gb, cpuCores = 4)
        assertEquals(HardwareTier.LOW_END, profile4.tier)
        assertEquals(HardwareTierProfiler.MODEL_LOW_END, profile4.recommendedModel)
        assertTrue(profile4.totalRamBytes < HardwareTierProfiler.SIX_GB_BYTES)

        val ram2gb = 2L * 1024 * 1024 * 1024
        val profile2 = HardwareTierProfiler.profile(ram2gb, cpuCores = 2)
        assertEquals(HardwareTier.LOW_END, profile2.tier)
        assertEquals(HardwareTierProfiler.MODEL_LOW_END, profile2.recommendedModel)

        // Just below 6GB boundary
        val ram5_9gb = (6L * 1024 * 1024 * 1024) - 1
        val profile5_9 = HardwareTierProfiler.profile(ram5_9gb)
        assertEquals(HardwareTier.LOW_END, profile5_9.tier)
    }

    @Test
    fun testCpuCoresDetection() {
        val profile = HardwareTierProfiler.profile(8L * 1024 * 1024 * 1024)
        assertTrue("Available cores must be at least 1", profile.cpuCores >= 1)
    }
}
