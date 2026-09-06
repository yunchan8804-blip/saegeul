/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [PersonalSentenceVault]: recording, BM25 retrieval ranking, category/recency/
 * continuation boosts, stem backoff, self-exclusion, capacity pruning, and encrypted persistence.
 */
class PersonalSentenceVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recordRejectsSingleTokenSentencesAndStatsReflectStoredDocs() {
        val vault = PersonalSentenceVault(clock = { 1000L })

        assertFalse(vault.record("안녕", "com.android.chrome"))
        assertEquals(0, vault.stats().sentences)

        assertTrue(vault.record("오늘 날씨 좋다", "com.android.chrome"))
        val stats = vault.stats()
        assertEquals(1, stats.sentences)
        assertTrue(stats.uniqueTerms > 0)

        // Recording the same sentence again bumps count but not sentence count.
        assertTrue(vault.record("오늘 날씨 좋다", "com.android.chrome"))
        assertEquals(1, vault.stats().sentences)
    }

    @Test
    fun retrieveRanksMatchingSentencesAboveAndExcludesNonMatching() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        vault.record("내일 판교에서 봐요", "com.android.chrome")
        vault.record("회의 자료 검토했습니다", "com.android.chrome")

        val results = vault.retrieve("회의", "com.android.chrome")
        val sentences = results.map { it.sentence }

        assertEquals(2, sentences.size)
        assertTrue(sentences.contains("오늘 회의 참석하겠습니다"))
        assertTrue(sentences.contains("회의 자료 검토했습니다"))
        assertFalse(sentences.contains("내일 판교에서 봐요"))
    }

    @Test
    fun retrieveBoostsSentencesContinuingFromTheTypedContext() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        vault.record("회의 자료 검토했습니다", "com.android.chrome")
        vault.record("회의 자료 준비중입니다", "com.android.chrome")

        val results = vault.retrieve("회의 자료", "com.android.chrome")

        assertTrue(results.isNotEmpty())
        val top = results.first()
        assertTrue(top.startsWithLastWord)
        assertTrue(top.sentence == "회의 자료 검토했습니다" || top.sentence == "회의 자료 준비중입니다")
    }

    @Test
    fun retrieveMatchesViaParticleStemBackoff() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")

        val results = vault.retrieve("회의에서", "com.android.chrome")

        assertTrue(results.any { it.sentence == "오늘 회의 참석하겠습니다" })
    }

    @Test
    fun retrieveBoostsSameCategoryOverOtherCategory() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("프로젝트 회의 일정 공유합니다", "com.slack") // work
        vault.record("프로젝트 회의 자료 올렸어요", "com.kakao.talk") // messenger

        val results = vault.retrieve("프로젝트 회의", "com.slack")

        assertEquals("프로젝트 회의 일정 공유합니다", results.first().sentence)
    }

    @Test
    fun retrieveBoostsMoreRecentSentenceOverOlderOne() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now })
        vault.record("업무 보고서 작성했습니다", "com.android.chrome")

        now = 60L * 24 * 60 * 60 * 1000 // 60 days later, well past the 45-day half-life
        vault.record("업무 보고서 검토했습니다", "com.android.chrome")

        val results = vault.retrieve("업무 보고서", "com.android.chrome")

        assertEquals("업무 보고서 검토했습니다", results.first().sentence)
    }

    @Test
    fun retrieveExcludesTheContextSentenceItself() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("점심 뭐 먹을까요", "com.android.chrome")

        val results = vault.retrieve("점심 뭐 먹을까요", "com.android.chrome")

        assertTrue(results.isEmpty())
    }

    @Test
    fun capacityOverflowPrunesOldestByDecayedCount() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now }, maxSentences = 3)

        now = 0; vault.record("문장 하나 입니다", "com.android.chrome")
        now = 1000; vault.record("문장 둘 입니다", "com.android.chrome")
        now = 2000; vault.record("문장 셋 입니다", "com.android.chrome")
        now = 3000; vault.record("문장 넷 입니다", "com.android.chrome")

        assertEquals(3, vault.stats().sentences)
        val sentences = vault.retrieve("문장", "com.android.chrome", limit = 10).map { it.sentence }
        assertFalse(sentences.contains("문장 하나 입니다"))
        assertTrue(sentences.contains("문장 넷 입니다"))
    }

    @Test
    fun saveAndLoadRoundTripThroughEncryptedVaultFile() {
        val file = tempFolder.newFile("personal_sentence_vault_encrypted.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val fixedTime = 1_700_000_000_000L

        val first = PersonalSentenceVault(storeFile = file, cipher = cipher, clock = { fixedTime })
        first.record("정기 회의 참석 확인했습니다", "com.slack")
        first.save()

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)

        val second = PersonalSentenceVault(storeFile = file, cipher = cipher, clock = { fixedTime })
        assertEquals(1, second.stats().sentences)
        val results = second.retrieve("정기 회의", "com.slack")
        assertTrue(results.any { it.sentence == "정기 회의 참석 확인했습니다" })
    }

    @Test
    fun clearResetsStatsAndDeletesFile() {
        val file = tempFolder.newFile("personal_sentence_vault_clear.json")
        val vault = PersonalSentenceVault(storeFile = file, clock = { 1000L })
        vault.record("정리 테스트 문장 입니다", "com.android.chrome")
        vault.save()
        assertTrue(file.exists())

        vault.clear()

        assertEquals(0, vault.stats().sentences)
        assertFalse(file.exists())
    }

    @Test
    fun retrievePerformsWithinBudgetAtMaxCapacity() {
        val vault = PersonalSentenceVault(clock = { System.currentTimeMillis() }, maxSentences = 3000)
        val words = listOf("회의", "보고서", "프로젝트", "일정", "자료", "점검", "확인", "작성", "공유", "검토")
        for (i in 0 until 3000) {
            val a = words[i % words.size]
            val b = words[(i / words.size) % words.size]
            vault.record("$a $b 문장 순번 $i 입니다", "com.android.chrome")
        }
        assertEquals(3000, vault.stats().sentences)

        // Warm up JIT before measuring.
        repeat(5) { vault.retrieve("회의 보고서", "com.android.chrome") }

        val start = System.nanoTime()
        vault.retrieve("회의 보고서", "com.android.chrome")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue("retrieve took ${elapsedMs}ms, expected <= 40ms", elapsedMs <= 40.0)
    }
}
