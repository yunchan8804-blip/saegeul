/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TypingDnaStatsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `empty repository returns level 1 sprout learner stats`() {
        val storageFile = File(tempFolder.root, "test_typing_dna.json")
        val repo = TypingDnaRepository(storageFile)

        val stats = repo.getStats()
        assertEquals(1, stats.level)
        assertEquals("새싹 학습자", stats.levelTitle)
        assertEquals(0, stats.totalSentences)
        assertEquals(0, stats.bigramsCount)
        assertEquals(0, stats.endingsCount)
        assertEquals(0, stats.levelProgressPercent)
        assertEquals(15, stats.nextLevelTargetSentences)
        assertTrue(stats.topBigrams.isEmpty())
        assertEquals(0f, stats.honorificRatio, 0.001f)
        assertEquals(0f, stats.informalRatio, 0.001f)
        assertEquals(0f, stats.messengerSentencesRatio, 0.001f)
        assertEquals(0f, stats.workSentencesRatio, 0.001f)
        assertEquals(0f, stats.generalSentencesRatio, 0.001f)
        assertEquals(100, stats.privacyOnDevicePercent)
        assertEquals(0, stats.cloudBytesExported)
        assertEquals(false, stats.hasLearnedData)
    }

    @Test
    fun `updatePersona honors explicit analyzed sentence count for live chart sync`() {
        val storageFile = File(tempFolder.root, "test_typing_dna_live.json")
        val repo = TypingDnaRepository(storageFile)
        repo.updatePersona(
            PersonaDna(
                category = "messenger",
                dominantTone = "Informal",
                habitualEndings = listOf("~해"),
                frequentBigrams = listOf(DynamicBigram("오늘", "만나", 0.9f)),
                cannedPhrases = listOf("오늘 만나!")
            ),
            analyzedSentenceCount = 3
        )
        val stats = repo.getStats()
        assertEquals(3, stats.totalSentences)
        assertEquals(1, stats.level)
        assertTrue(stats.hasLearnedData)
        assertEquals(100, stats.privacyOnDevicePercent)
        assertEquals(0, stats.cloudBytesExported)
        assertEquals("오늘", stats.topBigrams.first().prev)
    }

    @Test
    fun `populated profile reflects correct level progression and tone balance`() {
        val storageFile = File(tempFolder.root, "test_typing_dna_populated.json")
        val repo = TypingDnaRepository(storageFile)

        val messengerPersona = PersonaDna(
            category = "messenger",
            dominantTone = "Informal",
            habitualEndings = listOf("~해", "~용", "~어"),
            frequentBigrams = listOf(
                DynamicBigram("오늘", "만나", 0.9f),
                DynamicBigram("밥", "먹었어", 0.85f),
                DynamicBigram("집에", "가는중", 0.75f)
            ),
            cannedPhrases = listOf("오늘 저녁에 만나!")
        )

        val workPersona = PersonaDna(
            category = "work",
            dominantTone = "Honorific",
            habitualEndings = listOf("~습니다", "~요", "~시지요"),
            frequentBigrams = listOf(
                DynamicBigram("확인", "부탁드립니다", 0.95f),
                DynamicBigram("회의", "참석합니다", 0.8f)
            ),
            cannedPhrases = listOf("확인했습니다, 감사합니다.")
        )

        repo.updatePersona(messengerPersona)
        repo.updatePersona(workPersona)

        val stats = repo.getStats()
        // Two updates: 15 + 15 = 30 sentences -> Level 2
        assertEquals(2, stats.level)
        assertEquals("성장하는 AI 파트너", stats.levelTitle)
        assertEquals(30, stats.totalSentences)
        assertEquals(5, stats.bigramsCount)
        assertEquals(6, stats.endingsCount)
        assertEquals(2, stats.phrasesCount)

        // Top bigrams ranking check
        assertTrue(stats.topBigrams.isNotEmpty())
        assertEquals("확인", stats.topBigrams[0].prev)
        assertEquals("부탁드립니다", stats.topBigrams[0].next)
        assertEquals(0.95f, stats.topBigrams[0].weight, 0.01f)

        // Tone distribution
        assertTrue(stats.honorificRatio > 0.3f)
        assertTrue(stats.informalRatio > 0.3f)
        assertEquals(1.0f, stats.honorificRatio + stats.informalRatio, 0.01f)
    }

    @Test
    fun `high volume of sentences scales to higher levels`() {
        val storageFile = File(tempFolder.root, "test_typing_dna_high.json")
        val repo = TypingDnaRepository(storageFile)

        // Simulate 8 persona updates: 8 * 15 = 120 sentences -> Level 4
        repeat(8) { idx ->
            repo.updatePersona(
                PersonaDna(
                    category = "general",
                    dominantTone = "Honorific",
                    habitualEndings = listOf("~$idx"),
                    frequentBigrams = listOf(DynamicBigram("w$idx", "w${idx + 1}", 0.7f)),
                    cannedPhrases = emptyList()
                )
            )
        }

        val stats = repo.getStats()
        assertEquals(4, stats.level)
        assertEquals("정밀 문체 동기화", stats.levelTitle)
        assertEquals(120, stats.totalSentences)
    }

    @Test
    fun `level 3 and level 5 master progressions and clear verification`() {
        val storageFile = File(tempFolder.root, "test_typing_dna_levels.json")
        val repo = TypingDnaRepository(storageFile)

        // Level 3: 4 updates -> 60 sentences (45 <= s < 100)
        repeat(4) { idx ->
            repo.updatePersona(
                PersonaDna(
                    category = "messenger",
                    dominantTone = "Informal",
                    habitualEndings = listOf("~네$idx"),
                    frequentBigrams = listOf(DynamicBigram("a$idx", "b$idx", 0.8f)),
                    cannedPhrases = emptyList()
                )
            )
        }
        val level3Stats = repo.getStats()
        assertEquals(3, level3Stats.level)
        assertEquals("어휘 습관 형성", level3Stats.levelTitle)
        assertEquals(60, level3Stats.totalSentences)
        assertEquals(100, level3Stats.nextLevelTargetSentences)

        // Scale to Level 5: 14 updates total -> 14 * 15 = 210 sentences (>= 200)
        repeat(10) { idx ->
            repo.updatePersona(
                PersonaDna(
                    category = "work",
                    dominantTone = "Honorific",
                    habitualEndings = listOf("~다$idx"),
                    frequentBigrams = listOf(DynamicBigram("w$idx", "k$idx", 0.9f)),
                    cannedPhrases = emptyList()
                )
            )
        }
        val level5Stats = repo.getStats()
        assertEquals(5, level5Stats.level)
        assertEquals("언어 지문 마스터", level5Stats.levelTitle)
        assertEquals(210, level5Stats.totalSentences)
        assertEquals(100, level5Stats.levelProgressPercent)

        // Clear verification
        repo.clear()
        val clearedStats = repo.getStats()
        assertEquals(1, clearedStats.level)
        assertEquals(0, clearedStats.totalSentences)
        assertEquals(0, clearedStats.bigramsCount)
    }
}
