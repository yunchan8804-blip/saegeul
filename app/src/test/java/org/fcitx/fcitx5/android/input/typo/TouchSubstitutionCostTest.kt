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

    private fun cost(key: Char, dx: Float, dy: Float, intended: Char, habit: TouchHabit? = null): Float =
        requireNotNull(TouchSubstitutionCost.cost(touch(key, dx, dy), intended, habit))

    // 키 경계에서 의도한 키 중심까지의 거리 0.5키 -> (0.5 / 0.55)^2 / 2.
    private val boundaryCost = 0.5f * 0.5f / (TouchSubstitutionCost.SIGMA * TouchSubstitutionCost.SIGMA) / 2f

    @Test
    fun theConstantsAreThePaperSigmaAndTheLatinImeDerivedCap() {
        assertEquals(0.55f, TouchSubstitutionCost.SIGMA, 0f)
        assertEquals(0.47f, TouchSubstitutionCost.SUBSTITUTION_CAP, 0f)
        assertEquals(0.9f * 0.3806f / 0.7248f, TouchSubstitutionCost.SUBSTITUTION_CAP, 0.005f)
    }

    @Test
    fun touchAtTheIntendedKeyCenterCostsNothing() {
        // e를 쳤지만 터치 지점은 오른쪽 이웃 r의 정중앙이다.
        assertEquals(0f, cost('e', 1f, 0f, 'r'), 0.0001f)
    }

    @Test
    fun touchAtTheIntendedKeyBoundaryCostsAboutFourTenths() {
        val atBoundary = cost('e', 0.5f, 0f, 'r')

        assertEquals(boundaryCost, atBoundary, 0.0001f)
        assertEquals(0.41f, atBoundary, 0.005f)
    }

    @Test
    fun theCostFollowsTheDistanceToTheIntendedKeyCenterOnBothAxes() {
        // 같은 거리 0.5키: 중심 너머로 지나친 터치도, 위쪽에서 닿은 터치도 같은 비용이다.
        assertEquals(boundaryCost, cost('e', 1.5f, 0f, 'r'), 0.0001f)
        // r의 정중앙 위 0.5키.
        assertEquals(boundaryCost, cost('e', 1f, -0.5f, 'r'), 0.0001f)
    }

    @Test
    fun centerTouchMakesTheNeighborCostTheCap() {
        assertEquals(TouchSubstitutionCost.SUBSTITUTION_CAP, cost('e', 0f, 0f, 'r'), 0.0001f)
        assertEquals(TouchSubstitutionCost.SUBSTITUTION_CAP, cost('e', 0f, 0f, 'w'), 0.0001f)
    }

    @Test
    fun keysTwoCellsAwayStillUseTheTouchFormula() {
        // e와 t는 격자 비용 0.7이다.
        assertEquals(0.7f, DubeolsikKeyMap.substitutionCost('e', 't'), 0f)
        assertEquals(TouchSubstitutionCost.SUBSTITUTION_CAP, cost('e', 0f, 0f, 't'), 0.0001f)
        assertEquals(boundaryCost, cost('e', 1.5f, 0f, 't'), 0.0001f)
    }

    @Test
    fun farKeyKeepsTheGridCostEvenWithATouchAtItsCenter() {
        assertEquals(1.0f, DubeolsikKeyMap.substitutionCost('e', 'p'), 0f)
        assertNull(TouchSubstitutionCost.cost(touch('e', dx = 7f), 'p'))
        assertNull(TouchSubstitutionCost.cost(touch('e'), 'p'))
    }

    @Test
    fun touchNearBoundaryMakesThatNeighborCheaperThanTheOtherSide() {
        val towardNeighbor = cost('e', 0.5f, 0f, 'r')
        val awayFromNeighbor = cost('e', 0.5f, 0f, 'w')
        assertTrue("toward=$towardNeighbor away=$awayFromNeighbor", towardNeighbor < awayFromNeighbor)
        assertEquals(TouchSubstitutionCost.SUBSTITUTION_CAP, awayFromNeighbor, 0.0001f)
    }

    @Test
    fun verticalNeighborFollowsTheActualRowOffset() {
        // s는 윗줄의 w와 e 사이 아래에 있다. 왼쪽 위 모서리(w의 아래 경계)를 누르면 w가 옆 키 d보다 싸다.
        val toUpperKey = cost('s', -0.5f, -0.5f, 'w')
        val toSideKey = cost('s', -0.5f, -0.5f, 'd')
        assertEquals(boundaryCost, toUpperKey, 0.0001f)
        assertEquals(TouchSubstitutionCost.SUBSTITUTION_CAP, toSideKey, 0.0001f)
    }

    @Test
    fun theHabitOfTheIntendedKeyMovesItsCenter() {
        // 사용자는 r을 평소 왼쪽(e 쪽)으로 0.5키 치우쳐 누른다: e의 중앙을 누른 터치는 r의 옮겨진 중심과 0.5키 떨어진다.
        val leftLeaningR = TouchHabit { key -> if (key == 'r') KeyBias(-0.5f, 0f) else null }

        assertEquals(boundaryCost, cost('e', 0f, 0f, 'r', leftLeaningR), 0.0001f)
        // r의 중심이 e 쪽으로 한 칸 옮겨져 있으면 e의 중앙이 곧 r의 중심이다.
        assertEquals(0f, cost('e', 0f, 0f, 'r', TouchHabit { KeyBias(-1f, 0f) }), 0.0001f)
        assertEquals(0.47f, cost('e', 0f, 0f, 'r', TouchHabit { null }), 0.0001f)
    }

    @Test
    fun theHabitOfTheTypedKeyDoesNotMoveTheCost() {
        val onlyTypedKey = TouchHabit { key -> if (key == 'e') KeyBias(0.3f, 0f) else null }

        assertEquals(cost('e', 0.5f, 0f, 'r'), cost('e', 0.5f, 0f, 'r', onlyTypedKey), 0f)
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
