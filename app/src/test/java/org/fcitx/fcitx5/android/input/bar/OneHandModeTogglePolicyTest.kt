/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.OneHandMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneHandModeTogglePolicyTest {

    @Test
    fun turningOnRestoresTheRememberedSide() {
        assertEquals(OneHandMode.Left, OneHandModeTogglePolicy.next(OneHandMode.Off, OneHandMode.Left))
        assertEquals(OneHandMode.Right, OneHandModeTogglePolicy.next(OneHandMode.Off, OneHandMode.Right))
    }

    @Test
    fun turningOnWithoutARememberedSideUsesRight() {
        assertEquals(OneHandMode.Right, OneHandModeTogglePolicy.next(OneHandMode.Off, OneHandMode.Off))
    }

    @Test
    fun tappingWhileOnTurnsTheModeOff() {
        for (lastSide in OneHandMode.entries) {
            assertEquals(OneHandMode.Off, OneHandModeTogglePolicy.next(OneHandMode.Left, lastSide))
            assertEquals(OneHandMode.Off, OneHandModeTogglePolicy.next(OneHandMode.Right, lastSide))
        }
    }

    @Test
    fun onlySidesAreRemembered() {
        assertEquals(OneHandMode.Left, OneHandModeTogglePolicy.sideToRemember(OneHandMode.Left))
        assertEquals(OneHandMode.Right, OneHandModeTogglePolicy.sideToRemember(OneHandMode.Right))
        assertNull(OneHandModeTogglePolicy.sideToRemember(OneHandMode.Off))
    }

    @Test
    fun leftSurvivesAnOffOnCycle() {
        var lastSide = OneHandMode.Right
        var mode = OneHandMode.Left
        OneHandModeTogglePolicy.sideToRemember(mode)?.let { lastSide = it }
        mode = OneHandModeTogglePolicy.next(mode, lastSide)
        assertEquals(OneHandMode.Off, mode)
        OneHandModeTogglePolicy.sideToRemember(mode)?.let { lastSide = it }
        assertEquals(OneHandMode.Left, OneHandModeTogglePolicy.next(mode, lastSide))
    }
}
