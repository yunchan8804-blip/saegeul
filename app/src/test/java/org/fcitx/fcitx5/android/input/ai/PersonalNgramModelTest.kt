/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for PersonalNgramModel.
 * Covers trigram/bigram/unigram interpolation, choseong/jamo completion,
 * time decay, per-category tables, stem back-off, reinforcement,
 * persistence, pruning, PII safety, and completion latency.
 */
class PersonalNgramModelTest {

    private val defaultPackage = "com.example.test"

    @Test
    fun predictNextTrigramTopMatch() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)

        val result = model.predictNext("오늘 회의", defaultPackage, 5)

        assertTrue(result.isNotEmpty())
        assertEquals("참석합니다", result.first().word)
        assertEquals(3, result.first().level)
    }

    @Test
    fun predictNextEmptyContextTopUnigram() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)

        val result = model.predictNext("", defaultPackage, 5)

        assertTrue(result.isNotEmpty())
        assertEquals("오늘", result.first().word)
    }

    @Test
    fun completeWithPlainPrefixUsesContext() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)

        val result = model.complete("참", "오늘 회의", defaultPackage, 5)

        assertTrue(result.isNotEmpty())
        assertEquals("참석합니다", result.first().word)
    }

    @Test
    fun completeChoseongSequenceMatchesWord() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)
        model.learn("내일 차 마시자", defaultPackage)

        val result = model.complete("ㅊㅅ", "", defaultPackage, 10)

        assertTrue(result.any { it.word == "참석합니다" })
    }

    @Test
    fun completeSingleChoseongMatchesAllWordsWithThatInitial() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", defaultPackage)
        model.learn("내일 차 마시자", defaultPackage)

        val words = model.complete("ㅊ", "", defaultPackage, 10).map { it.word }

        assertTrue(words.contains("참석합니다"))
        assertTrue(words.any { it.startsWith("차") })
    }

    @Test
    fun decayPrefersRecentOverStaleLearning() {
        val time = longArrayOf(0L)
        val model = PersonalNgramModel(clock = { time[0] })

        time[0] = 0L
        model.learn("휴가 신청합니다", defaultPackage)

        time[0] = 60L * 24 * 60 * 60 * 1000 // 60 days later
        model.learn("휴가 취소합니다", defaultPackage)

        val result = model.predictNext("휴가", defaultPackage, 5)

        assertTrue(result.isNotEmpty())
        assertEquals("취소합니다", result.first().word)
    }

    @Test
    fun categoryTablesDivergeAcrossApps() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("밥 먹자", "com.kakao.talk")
        model.learn("밥 먹었습니다", "com.slack")

        val kakaoTop = model.predictNext("밥", "com.kakao.talk", 3).first().word
        val slackTop = model.predictNext("밥", "com.slack", 3).first().word

        assertEquals("먹자", kakaoTop)
        assertEquals("먹었습니다", slackTop)
        assertNotEquals(kakaoTop, slackTop)
    }

    @Test
    fun stemBackoffAppliesParticleVariant() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("회사에서 만나요", defaultPackage)

        val words = model.predictNext("회사에서는", defaultPackage, 5).map { it.word }

        assertTrue(words.contains("만나요"))
    }

    @Test
    fun reinforceBoostsSelectedWordRank() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("점심 뭐 먹지", defaultPackage)
        model.learn("점심 뭐 먹을까", defaultPackage)
        model.learn("점심 뭐 먹을까", defaultPackage)

        val before = model.predictNext("점심 뭐", defaultPackage, 5)
        assertEquals("먹을까", before.first().word)

        repeat(3) { model.reinforce("점심 뭐", "먹지", defaultPackage) }

        val after = model.predictNext("점심 뭐", defaultPackage, 5)
        assertEquals("먹지", after.first().word)
    }

    @Test
    fun saveAndLoadRoundTripPreservesPredictions() {
        val storeFile = File.createTempFile("personal_ngram_roundtrip", ".json").apply { deleteOnExit() }
        val fixedTime = 1_000_000_000L
        val first = PersonalNgramModel(storeFile = storeFile, clock = { fixedTime })
        first.learn("오늘 회의 참석합니다", defaultPackage)
        first.learn("오늘 점심 먹었습니다", defaultPackage)
        first.save()

        val before = first.predictNext("오늘", defaultPackage, 5).map { it.word }

        val second = PersonalNgramModel(storeFile = storeFile, clock = { fixedTime })
        val after = second.predictNext("오늘", defaultPackage, 5).map { it.word }

        assertEquals(before, after)
    }

    @Test
    fun pruneKeepsSizeWithinBoundAndRetainsRecentEntries() {
        val time = longArrayOf(0L)
        val model = PersonalNgramModel(
            clock = { time[0] },
            maxUnigrams = 20,
            maxBigrams = 200,
            maxTrigrams = 200,
            halfLifeMs = 1000L
        )
        for (i in 1..30) {
            time[0] = i.toLong() * 2000L
            model.learn("단어$i 입니다", defaultPackage)
        }

        val stats = model.stats()
        assertTrue("uni size ${stats.unigrams} should stay within max", stats.unigrams <= 20)

        // complete()'s candidate vocabulary is drawn directly from the uni tables, unlike
        // predictNext() which can still surface a pruned word via its surviving <s> bigram entry.
        val words = model.complete("단어", "", defaultPackage, 50).map { it.word }
        assertTrue("recent word should survive pruning", words.contains("단어30"))
        assertFalse("stale word should have been pruned", words.contains("단어1"))
    }

    @Test
    fun learnScrubsPiiBeforeTokenizing() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("제 번호는 010-1234-5678 입니다", defaultPackage)

        val stats = model.stats()
        val words = model.predictNext("", defaultPackage, stats.unigrams.coerceAtLeast(10)).map { it.word }

        assertFalse(words.any { it.any(Char::isDigit) })
        assertFalse(words.contains("[전화번호]"))
    }

    @Test
    fun clearResetsStatsAndDeletesFile() {
        val storeFile = File.createTempFile("personal_ngram_clear", ".json").apply { deleteOnExit() }
        val model = PersonalNgramModel(storeFile = storeFile, clock = { 1_000_000_000L })
        model.learn("오늘 날씨가 좋다", defaultPackage)
        model.save()
        assertTrue(storeFile.exists())

        model.clear()

        val stats = model.stats()
        assertEquals(0, stats.unigrams)
        assertEquals(0, stats.bigrams)
        assertEquals(0, stats.trigrams)
        assertEquals(0, stats.learnedSentences)
        assertEquals(0L, stats.lastLearnedMs)
        assertFalse(storeFile.exists())
    }

    @Test
    fun completePerformanceStaysWithinBudget() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        for (i in 1..6000) {
            model.learn("단어$i 입니다", defaultPackage)
        }

        repeat(5) { model.complete("단어", "", defaultPackage, 10) }

        val iterations = 100
        val start = System.nanoTime()
        repeat(iterations) {
            model.complete("단어", "", defaultPackage, 10)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        assertTrue("complete() averaged ${elapsedMs}ms per call, expected <= 50ms", elapsedMs <= 50.0)
    }

    @Test
    fun categoryCountsSumsDecayedUnigramsPerCategoryExcludingStar() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        model.learn("오늘 회의 참석합니다", "com.slack")
        model.learn("오늘 저녁 뭐 먹지", "com.kakao.talk")

        val counts = model.categoryCounts()

        assertFalse(counts.containsKey("*"))
        assertTrue(counts.containsKey(TypingDnaVault.CATEGORY_WORK))
        assertTrue(counts.containsKey(TypingDnaVault.CATEGORY_MESSENGER))
        assertTrue(counts[TypingDnaVault.CATEGORY_WORK]!! > 0f)
        assertTrue(counts[TypingDnaVault.CATEGORY_MESSENGER]!! > 0f)
    }
}
