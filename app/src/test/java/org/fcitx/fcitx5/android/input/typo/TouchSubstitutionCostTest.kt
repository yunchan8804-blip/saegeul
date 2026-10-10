/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSubstitutionCostTest {

    private fun cost(key: Char, dx: Float, dy: Float, intended: Char): Float =
        requireNotNull(TouchSubstitutionCost.cost(touch(key, dx, dy), intended))

    @Test
    fun centerTouchMakesNeighborExpensive() {
        assertEquals(0.37f, cost('e', 0f, 0f, 'r'), 0.005f)
        assertEquals(0.37f, cost('e', 0f, 0f, 'w'), 0.005f)
    }

    @Test
    fun touchNearBoundaryMakesThatNeighborCheap() {
        val towardNeighbor = cost('e', 0.45f, 0f, 'r')
        val awayFromNeighbor = cost('e', 0.45f, 0f, 'w')
        val centered = cost('e', 0f, 0f, 'r')
        assertEquals(TouchSubstitutionCost.MIN_COST, towardNeighbor, 0.0001f)
        assertTrue("toward=$towardNeighbor centered=$centered", towardNeighbor < centered)
        assertTrue("away=$awayFromNeighbor centered=$centered", awayFromNeighbor > centered)
    }

    @Test
    fun verticalNeighborFollowsTheActualRowOffset() {
        // s는 윗줄의 w와 e 사이 아래에 있다. 위쪽 경계를 누르면 위의 두 키가 옆 키 d보다 싸다.
        val toUpperKey = cost('s', -0.3f, -0.45f, 'w')
        val toSideKey = cost('s', -0.3f, -0.45f, 'd')
        assertTrue("upper=$toUpperKey side=$toSideKey", toUpperKey < toSideKey)
    }

    @Test
    fun farKeyIsCappedAtTheGridScale() {
        assertEquals(TouchSubstitutionCost.MAX_COST, cost('e', 0f, 0f, 'p'), 0.0001f)
    }

    @Test
    fun touchLandingBeyondTheTypedKeyNeverGoesBelowTheFloor() {
        assertEquals(TouchSubstitutionCost.MIN_COST, cost('e', 0.9f, 0f, 'r'), 0.0001f)
    }

    @Test
    fun costIsUndefinedForTheSameOrShiftOnlyKeyAndForKeysOutsideTheLayout() {
        assertNull(TouchSubstitutionCost.cost(touch('r'), 'r'))
        assertNull(TouchSubstitutionCost.cost(touch('R'), 'r'))
        assertNull(TouchSubstitutionCost.cost(touch('r'), 'R'))
        assertNull(TouchSubstitutionCost.cost(touch('r'), '1'))
        assertNull(TouchSubstitutionCost.cost(touch('1'), 'r'))
    }

    @Test
    fun shiftedKeyUsesItsBaseKeyCell() {
        assertEquals(cost('e', 0f, 0f, 'r'), cost('E', 0f, 0f, 'r'), 0.0001f)
    }
}
