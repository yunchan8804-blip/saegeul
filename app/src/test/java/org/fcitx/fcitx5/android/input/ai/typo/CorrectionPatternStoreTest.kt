/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for CorrectionPatternStore: "지우고 다시 쓴" 교정 쌍 학습,
 * 키 혼동 카운트 역추적, 개인화된 치환 비용 보정, 저장/로드 왕복.
 */
class CorrectionPatternStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recordsGenuineTypoCorrection() {
        val store = CorrectionPatternStore()
        val recorded = store.recordCorrection("사묘ㅏ함니다", "감사합니다")
        assertTrue(recorded)

        val results = store.lookup("사묘ㅏ함니다")
        assertTrue(results.isNotEmpty())
        assertEquals("감사합니다", results[0].corrected)
    }

    @Test
    fun rejectsUnrelatedRephrase() {
        val store = CorrectionPatternStore()
        val recorded = store.recordCorrection("감사합니다", "고맙습니다")
        assertFalse(recorded)
    }

    @Test
    fun accumulatesCountForRepeatedPair() {
        val store = CorrectionPatternStore()
        store.recordCorrection("사묘ㅏ함니다", "감사합니다")
        store.recordCorrection("사묘ㅏ함니다", "감사합니다")

        val results = store.lookup("사묘ㅏ함니다")
        assertEquals(1, results.size)
        assertEquals(2f, results[0].count, 0.0001f)
    }

    @Test
    fun tracksConfusionAndPersonalizesSubstitutionCost() {
        val store = CorrectionPatternStore()
        store.recordCorrection("사묘ㅏ함니다", "감사합니다")

        assertTrue(store.confusionCount('t', 'r') >= 1f)
        val personalized = store.personalizedSubstitutionCost('t', 'r')
        val base = DubeolsikKeyMap.substitutionCost('t', 'r')
        assertTrue("personalized=$personalized base=$base", personalized < base)
    }

    @Test
    fun savesAndReloadsAcrossInstances() {
        val file = tempFolder.newFile("correction_patterns.json")
        val clockValue = longArrayOf(1_000L)
        val store1 = CorrectionPatternStore(storeFile = file, clock = { clockValue[0] })
        store1.recordCorrection("사묘ㅏ함니다", "감사합니다")
        store1.save()

        val store2 = CorrectionPatternStore(storeFile = file, clock = { clockValue[0] })
        val (pairs, confusions) = store2.stats()
        assertEquals(1, pairs)
        assertTrue(confusions > 0)
        assertEquals("감사합니다", store2.lookup("사묘ㅏ함니다").first().corrected)
    }

    @Test
    fun clearRemovesStateAndFile() {
        val file = tempFolder.newFile("correction_patterns_clear.json")
        val store = CorrectionPatternStore(storeFile = file)
        store.recordCorrection("사묘ㅏ함니다", "감사합니다")
        store.save()
        assertTrue(file.exists())

        store.clear()
        assertEquals(0 to 0, store.stats())
        assertFalse(file.exists())
    }

    @Test
    fun rejectsPairsContainingPii() {
        val store = CorrectionPatternStore()
        val recorded = store.recordCorrection("010-1234-5678", "01012345678")
        assertFalse(recorded)
    }
}
