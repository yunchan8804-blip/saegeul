/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.points

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelRewardMathTest {

    @Test
    fun oneLevelGainsTenPoints() {
        val gained = LevelRewardMath.gainedLevels(5, 6)
        assertEquals(1, gained)
        assertEquals(10, LevelRewardMath.pointsFor(gained!!))
    }

    @Test
    fun multiLevelJumpGrantsAllLevels() {
        val gained = LevelRewardMath.gainedLevels(3, 8)
        assertEquals(5, gained)
        assertEquals(50, LevelRewardMath.pointsFor(gained!!))
    }

    @Test
    fun sameLevelGrantsNothing() {
        assertNull(LevelRewardMath.gainedLevels(6, 6))
    }

    @Test
    fun lowerLevelGrantsNothing() {
        assertNull(LevelRewardMath.gainedLevels(6, 5))
    }
}
