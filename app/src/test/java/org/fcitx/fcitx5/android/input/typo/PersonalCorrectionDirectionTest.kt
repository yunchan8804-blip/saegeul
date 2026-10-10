/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 개인 교정 학습이 교정 방향에 맞게 쓰이는지: 사용자가 X를 쳤다가 Y로 고친 기록은 트라이가
 * X를 친 오타에서 Y를 의도한 후보를 싸게 만들어야 하고, 반대 방향은 싸게 만들면 안 된다.
 */
class PersonalCorrectionDirectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // "다나"를 쳤다가 "자나"로 고쳤다: 친 키 e를 w로 고쳤다.
    private fun learnedStore(): CorrectionPatternStore =
        CorrectionPatternStore().also { assertTrue(it.recordCorrection("다나", "자나")) }

    private fun corrector(store: CorrectionPatternStore): KeyboardAwareTypoCorrector {
        val corrector = KeyboardAwareTypoCorrector(
            substitutionCost = store::personalizedSubstitutionCost,
            personalDiscount = store::substitutionDiscount
        )
        corrector.addWord("가", 0.50f)
        corrector.addWord("자", 0.49f)
        return corrector
    }

    @Test
    fun confusionIsRecordedAsTypedKeyThenIntendedKey() {
        val store = learnedStore()
        assertTrue(store.confusionCount('e', 'w') >= 1f)
        assertEquals(0f, store.confusionCount('w', 'e'), 0f)
        assertTrue(store.substitutionDiscount('e', 'w') < 1f)
        assertEquals(1f, store.substitutionDiscount('w', 'e'), 0f)
    }

    @Test
    fun withoutLearningTheMoreCommonWordWins() {
        val results = corrector(CorrectionPatternStore()).correct("다", limit = 3)
        assertEquals(listOf("가", "자"), results.map { it.word })
        assertEquals(results[0].cost, results[1].cost, 0.0001f)
    }

    @Test
    fun learnedConfusionMakesTheIntendedWordCheaperAndRanksItFirst() {
        val results = corrector(learnedStore()).correct("다", limit = 3)

        assertEquals(listOf("자", "가"), results.map { it.word })
        val learned = results.first { it.word == "자" }.cost
        val unlearned = results.first { it.word == "가" }.cost
        assertTrue("learned=$learned unlearned=$unlearned", learned < unlearned)
    }

    @Test
    fun theReverseConfusionIsNotDiscounted() {
        // 다를 쳤다가 자로 고친 기록만 있으므로, 자를 친 오타에서 다(e)를 의도한 쪽은 싸지지 않는다.
        val corrector = KeyboardAwareTypoCorrector(
            substitutionCost = learnedStore()::personalizedSubstitutionCost
        )
        corrector.addWord("다", 0.5f)
        corrector.addWord("나", 0.5f)
        val costs = corrector.correct("자", limit = 3).associate { it.word to it.cost }
        assertEquals(costs.getValue("다"), costs.getValue("나"), 0.0001f)
    }

    @Test
    fun learnedConfusionSurvivesSaveAndReloadAndStillDiscountsTheSameWay() {
        val file = tempFolder.newFile("personal_corrections.json")
        val store = CorrectionPatternStore(storeFile = file)
        assertTrue(store.recordCorrection("다나", "자나"))
        store.save()

        val reloaded = CorrectionPatternStore(storeFile = file)
        assertTrue(reloaded.confusionCount('e', 'w') >= 1f)
        assertEquals(listOf("자", "가"), corrector(reloaded).correct("다", limit = 3).map { it.word })
    }

    @Test
    fun learnedDiscountAlsoCheapensTheTouchCost() {
        val corrector = corrector(learnedStore())
        val centered = TouchTestFixtures.centeredTrace("ek")
        val results = corrector.correct("다", limit = 3, touchTrace = centered)

        assertEquals("자", results.first().word)
        assertTrue(results.first().cost < results.first { it.word == "가" }.cost)
    }
}
