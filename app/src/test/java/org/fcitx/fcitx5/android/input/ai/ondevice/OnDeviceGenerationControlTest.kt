/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceGenerationControlTest {

    @Test
    fun `keyboard visibility cancels running generation and blocks restart until hidden`() {
        var cancelled = 0
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

        assertTrue(OnDeviceGenerationControl.begin { cancelled += 1 })
        assertFalse(OnDeviceGenerationControl.begin { cancelled += 1 })

        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)

        assertEquals(1, cancelled)
        assertFalse(OnDeviceGenerationControl.begin { cancelled += 1 })

        OnDeviceGenerationControl.end()
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

        assertTrue(OnDeviceGenerationControl.begin { cancelled += 1 })
        OnDeviceGenerationControl.end()
    }
}
