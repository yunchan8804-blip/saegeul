/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TypingDnaVault.
 * Tests package categorization, automatic PII scrubbing, threshold triggers, and zero-knowledge purge.
 */
class TypingDnaVaultTest {

    @Test
    fun testPackageCategorization() {
        assertEquals(TypingDnaVault.CATEGORY_MESSENGER, TypingDnaVault.categorizePackage("com.kakao.talk"))
        assertEquals(TypingDnaVault.CATEGORY_MESSENGER, TypingDnaVault.categorizePackage("org.telegram.messenger"))
        assertEquals(TypingDnaVault.CATEGORY_WORK, TypingDnaVault.categorizePackage("com.slack"))
        assertEquals(TypingDnaVault.CATEGORY_WORK, TypingDnaVault.categorizePackage("com.google.android.gm"))
        assertEquals(TypingDnaVault.CATEGORY_GENERAL, TypingDnaVault.categorizePackage("com.android.chrome"))
    }

    @Test
    fun testScrubbingOnRecordAndThresholdTrigger() {
        var batchReceivedCategory: String? = null
        var batchReceivedSentences: List<String>? = null

        val vault = TypingDnaVault(
            thresholdPerCategory = 3,
            maxCapacityPerCategory = 10,
            onBatchReady = { cat, sentences ->
                batchReceivedCategory = cat
                batchReceivedSentences = sentences
            }
        )

        // Record 2 sentences in KakaoTalk with PII
        vault.recordSentence("com.kakao.talk", "내 전화번호는 010-9999-8888 이야.")
        vault.recordSentence("com.kakao.talk", "오늘 저녁에 치맥 먹자!")

        assertEquals(2, vault.totalBufferedCount())
        assertFalse(vault.getSentences(TypingDnaVault.CATEGORY_MESSENGER).first().contains("010-9999-8888"))
        assertTrue(vault.getSentences(TypingDnaVault.CATEGORY_MESSENGER).first().contains("[전화번호]"))
        assertEquals(null, batchReceivedCategory)

        // 3rd sentence reaches threshold of 3
        val triggered = vault.recordSentence("com.kakao.talk", "강남역 2번 출구에서 만나자.")
        assertTrue(triggered)
        assertEquals(TypingDnaVault.CATEGORY_MESSENGER, batchReceivedCategory)
        assertEquals(3, batchReceivedSentences?.size)
    }

    @Test
    fun testZeroKnowledgePurge() {
        val vault = TypingDnaVault(thresholdPerCategory = 5)
        vault.recordSentence("com.kakao.talk", "내일 몇 시에 볼까?")
        vault.recordSentence("com.slack", "서버 배포 완료했습니다.")

        assertEquals(2, vault.totalBufferedCount())

        // Purge messenger only
        vault.purge(TypingDnaVault.CATEGORY_MESSENGER)
        assertEquals(0, vault.getSentences(TypingDnaVault.CATEGORY_MESSENGER).size)
        assertEquals(1, vault.getSentences(TypingDnaVault.CATEGORY_WORK).size)

        // Purge all
        vault.purge()
        assertEquals(0, vault.totalBufferedCount())
    }

    @Test
    fun stagingFileRehydratesAfterNewVaultInstance() {
        val staging = java.io.File.createTempFile("typing_dna_pending", ".json").apply { deleteOnExit() }
        val first = TypingDnaVault(thresholdPerCategory = 15, stagingFile = staging)
        first.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자 ㅋㅋ")
        first.recordSentence("com.slack", "배포 모니터링 부탁드립니다.")
        assertTrue(staging.exists() && staging.length() > 2)

        val second = TypingDnaVault(thresholdPerCategory = 15, stagingFile = staging)
        assertEquals(1, second.getSentences(TypingDnaVault.CATEGORY_MESSENGER).size)
        assertEquals(1, second.getSentences(TypingDnaVault.CATEGORY_WORK).size)

        second.drain()
        val third = TypingDnaVault(thresholdPerCategory = 15, stagingFile = staging)
        assertEquals(0, third.totalBufferedCount())
    }

    @Test
    fun testDrainReturnsBufferedSentencesWithoutDispatchingCallback() {
        var callbackCount = 0
        val vault = TypingDnaVault(
            thresholdPerCategory = 10,
            onBatchReady = { _, _ -> callbackCount++ }
        )
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자 ㅋㅋ")
        vault.recordSentence("com.slack", "배포 모니터링 부탁드립니다.")

        val drained = vault.drain()
        assertEquals(1, drained[TypingDnaVault.CATEGORY_MESSENGER]?.size)
        assertEquals(1, drained[TypingDnaVault.CATEGORY_WORK]?.size)
        assertEquals(0, vault.totalBufferedCount())
        assertEquals("drain() must not fire onBatchReady so instant sync can compile once", 0, callbackCount)
    }
}
