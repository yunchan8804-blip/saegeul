/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
    fun personaOverrideAppliesCategoryForRetrievalBoost() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        // "com.example.test" matches no registry package or token; without the override it would
        // land in "general" and lose the same-category boost against a "com.slack" (work) query.
        vault.record(
            "프로젝트 회의 일정 공유합니다",
            "com.example.test",
            personaOverride = TypingDnaVault.CATEGORY_WORK
        )
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
    fun retrieveReusesTheLastResultForAnIdenticalCallWithinTheSameSecond() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")

        val first = vault.retrieve("회의", "com.android.chrome")
        now = 1_999L
        val second = vault.retrieve("회의", "com.android.chrome")

        assertSame(first, second)
    }

    @Test
    fun retrieveRecomputesWhenAnyPartOfTheQueryChanges() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val first = vault.retrieve("회의", "com.android.chrome")

        assertNotSame(first, vault.retrieve("회의 ", "com.android.chrome"))
        assertNotSame(first, vault.retrieve("회의", "com.slack"))
        assertNotSame(first, vault.retrieve("회의", "com.android.chrome", limit = 3))
    }

    @Test
    fun retrieveRecomputesOnceTheSecondChanges() {
        var now = 1_000L
        val vault = PersonalSentenceVault(clock = { now })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val first = vault.retrieve("회의", "com.android.chrome")

        now = 2_000L
        val second = vault.retrieve("회의", "com.android.chrome")

        assertNotSame(first, second)
        assertEquals(first.map { it.sentence }, second.map { it.sentence })
    }

    @Test
    fun retrieveSeesSentencesAddedAfterTheCachedCall() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        vault.record("회의 자료 검토했습니다", "com.android.chrome")
        val before = vault.retrieve("회의", "com.android.chrome")

        vault.record("회의 끝나고 연락드릴게요", "com.android.chrome")
        val after = vault.retrieve("회의", "com.android.chrome")

        assertEquals(before.size + 1, after.size)
        assertTrue(after.any { it.sentence == "회의 끝나고 연락드릴게요" })
    }

    @Test
    fun retrieveSeesARepeatedSentenceRefreshedAfterTheCachedCall() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        now = 500L
        val before = vault.retrieve("회의", "com.android.chrome").single()

        now = 600L
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val after = vault.retrieve("회의", "com.android.chrome").single()

        assertTrue(after.score > before.score)
    }

    @Test
    fun retrieveSeesSentencesEvictedAfterTheCachedCall() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now }, maxSentences = 2)
        vault.record("문장 하나 입니다", "com.android.chrome")
        now = 100L
        vault.record("문장 둘 입니다", "com.android.chrome")
        assertTrue(vault.retrieve("문장", "com.android.chrome").any { it.sentence == "문장 하나 입니다" })

        now = 200L
        vault.record("다른 셋 입니다", "com.android.chrome")

        assertFalse(vault.retrieve("문장", "com.android.chrome").any { it.sentence == "문장 하나 입니다" })
    }

    @Test
    fun retrieveReturnsNothingOnceTheVaultIsClearedAfterTheCachedCall() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        assertTrue(vault.retrieve("회의", "com.android.chrome").isNotEmpty())

        vault.clear()
        assertTrue(vault.retrieve("회의", "com.android.chrome").isEmpty())

        vault.record("회의 자료 검토했습니다", "com.android.chrome")
        assertEquals(
            listOf("회의 자료 검토했습니다"),
            vault.retrieve("회의", "com.android.chrome").map { it.sentence }
        )
    }

    @Test
    fun retrieveResultCannotBeModifiedThroughACast() {
        val vault = PersonalSentenceVault(clock = { 1_000L })
        vault.record("오늘 회의 참석하겠습니다", "com.android.chrome")
        val results = vault.retrieve("회의", "com.android.chrome")

        assertThrows(UnsupportedOperationException::class.java) {
            (results as MutableList<PersonalSentenceVault.Retrieved>).clear()
        }
        assertEquals(1, vault.retrieve("회의", "com.android.chrome").size)
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

        // Warm up JIT before measuring, with queries other than the measured one so the measured
        // call is not served from the last-result cache.
        words.drop(1).take(5).forEach { vault.retrieve("$it 보고서", "com.android.chrome") }

        val start = System.nanoTime()
        vault.retrieve("회의 보고서", "com.android.chrome")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue("retrieve took ${elapsedMs}ms, expected <= 40ms", elapsedMs <= 40.0)
    }

    @Test
    fun exportForEnrichmentReturnsEmptyListForEmptyVault() {
        val vault = PersonalSentenceVault(clock = { 1000L })

        assertTrue(vault.exportForEnrichment(10).isEmpty())
    }

    @Test
    fun exportForEnrichmentRanksByDecayedCountDescending() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now })
        now = 0; vault.record("문장 하나 입니다", "com.android.chrome")
        now = 2000; vault.record("문장 셋 입니다", "com.android.chrome")
        now = 2000; vault.record("문장 둘 입니다", "com.android.chrome")
        now = 2000; vault.record("문장 둘 입니다", "com.android.chrome") // bump its count to 2

        val exported = vault.exportForEnrichment(10)

        assertEquals(3, exported.size)
        assertEquals("문장 둘 입니다", exported.first())
    }

    @Test
    fun exportForEnrichmentRespectsLimit() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("문장 하나 입니다", "com.android.chrome")
        vault.record("문장 둘 입니다", "com.android.chrome")
        vault.record("문장 셋 입니다", "com.android.chrome")

        val exported = vault.exportForEnrichment(2)

        assertEquals(2, exported.size)
    }

    @Test
    fun exportSinceReturnsEmptyListForEmptyVault() {
        val vault = PersonalSentenceVault(clock = { 1000L })

        assertTrue(vault.exportSince(0L, 10).isEmpty())
    }

    @Test
    fun exportSinceOnlyIncludesSentencesLastSeenStrictlyAfterTheCutoff() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now })
        now = 1000L; vault.record("이전 문장 입니다", "com.android.chrome")
        now = 2000L; vault.record("경계 문장 입니다", "com.android.chrome")
        now = 3000L; vault.record("이후 문장 입니다", "com.android.chrome")

        val exported = vault.exportSince(2000L, 10)

        assertEquals(listOf("이후 문장 입니다"), exported)
    }

    @Test
    fun exportSinceOrdersMostRecentFirstAndRespectsLimit() {
        var now = 0L
        val vault = PersonalSentenceVault(clock = { now })
        now = 100L; vault.record("첫 문장 입니다", "com.android.chrome")
        now = 200L; vault.record("둘째 문장 입니다", "com.android.chrome")
        now = 300L; vault.record("셋째 문장 입니다", "com.android.chrome")

        val exported = vault.exportSince(0L, 2)

        assertEquals(listOf("셋째 문장 입니다", "둘째 문장 입니다"), exported)
    }

    @Test
    fun categoryCountsTalliesActualStoredSentencesPerCategory() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 점심 뭐 먹지", "com.kakao.talk")
        vault.record("내일 회의 자료 준비", "com.kakao.talk")
        vault.record("분기 보고서 작성 중입니다", "com.Slack")
        vault.record("이 문장은 분류가 안 됩니다", "com.unknown.random.app")

        val counts = vault.categoryCounts()

        assertEquals(2, counts[TypingDnaVault.CATEGORY_MESSENGER])
        assertEquals(1, counts[TypingDnaVault.CATEGORY_WORK])
        assertEquals(1, counts[TypingDnaVault.CATEGORY_GENERAL])
    }

    @Test
    fun categoryCountsIsEmptyWhenNothingRecordedYet() {
        val vault = PersonalSentenceVault(clock = { 1000L })

        assertTrue(vault.categoryCounts().isEmpty())
    }
}
