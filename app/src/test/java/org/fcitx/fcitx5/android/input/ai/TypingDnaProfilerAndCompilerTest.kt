/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Comprehensive integration tests for Typing DNA Profiler, Compiler, and Repository.
 */
class TypingDnaProfilerAndCompilerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var collocationModel: KoreanCollocationModel
    private lateinit var lexiconModel: PersonalizedLexiconModel
    private lateinit var sentenceStore: PersonalizedSentenceStore
    private lateinit var vault: TypingDnaVault
    private lateinit var repoFile: File
    private lateinit var repository: TypingDnaRepository
    private lateinit var compiler: TypingDnaCompiler
    private lateinit var profiler: TypingDnaProfiler

    @Before
    fun setUp() {
        collocationModel = KoreanCollocationModel()
        lexiconModel = PersonalizedLexiconModel()
        sentenceStore = PersonalizedSentenceStore()
        vault = TypingDnaVault()
        repoFile = tempFolder.newFile("typing_dna_test.json")
        repository = TypingDnaRepository(repoFile)
        compiler = TypingDnaCompiler(
            collocationModel = collocationModel,
            lexiconModel = lexiconModel,
            sentenceStore = sentenceStore,
            vault = vault,
            repository = repository
        )
        profiler = TypingDnaProfiler()
    }

    @Test
    fun testTypingDnaProfileJsonRoundtrip() {
        val original = TypingDnaProfile(
            version = 1,
            totalAnalyzedSentences = 45,
            personas = mapOf(
                "messenger" to PersonaDna(
                    category = "messenger",
                    dominantTone = "Informal",
                    habitualEndings = listOf("~네용", "ㅋㅋ", "~했엉"),
                    frequentBigrams = listOf(DynamicBigram("오늘", "퇴근하고", 0.95f)),
                    cannedPhrases = listOf("완전 고마워 덕분이야!")
                )
            )
        )

        val json = original.toJson()
        val restored = TypingDnaProfile.fromJson(json)

        assertEquals(original.version, restored.version)
        assertEquals(original.totalAnalyzedSentences, restored.totalAnalyzedSentences)
        assertTrue(restored.personas.containsKey("messenger"))
        val persona = restored.personas["messenger"]!!
        assertEquals("Informal", persona.dominantTone)
        assertEquals(3, persona.habitualEndings.size)
        assertEquals("오늘", persona.frequentBigrams.first().prev)
        assertEquals("퇴근하고", persona.frequentBigrams.first().next)
        assertEquals("완전 고마워 덕분이야!", persona.cannedPhrases.first())
    }

    @Test
    fun testOnDeviceStatisticalProfiling() {
        val sampleSentences = listOf(
            "오늘 퇴근하고 저녁 먹을까? 시간 괜찮아?",
            "오늘 퇴근하고 치맥 한잔하자!",
            "내일 몇 시에 볼까? 약속 장소 어디로 할래?",
            "완전 고마워 덕분이야! ㅋㅋ",
            "진짜 고마워 덕분이야! ㅎㅎ"
        )

        val persona = profiler.profileOnDevice("messenger", sampleSentences)

        assertEquals("messenger", persona.category)
        assertEquals("Informal", persona.dominantTone)
        assertTrue("Expected frequent bigram '오늘' -> '퇴근하고'", persona.frequentBigrams.any { it.prev == "오늘" && it.next == "퇴근하고" })
        assertTrue("Expected ending 'ㅋㅋ' or 'ㅎㅎ' or '아?'", persona.habitualEndings.isNotEmpty())
    }

    @Test
    fun testCompilerInjectsDynamicBigramsAndPurgesVault() {
        // 1. Buffer text in vault
        vault.recordSentence("com.kakao.talk", "오늘 야근하고 치맥 먹자.")
        assertEquals(1, vault.totalBufferedCount())

        val persona = PersonaDna(
            category = "messenger",
            dominantTone = "Informal",
            habitualEndings = listOf("~했어용", "ㅋㅋ"),
            frequentBigrams = listOf(
                DynamicBigram("오늘", "칼퇴하고", 0.98f),
                DynamicBigram("배포", "성공했어", 0.95f)
            ),
            cannedPhrases = listOf("고생 많았어 내일 봐!")
        )

        // 2. Compile persona
        compiler.compilePersona(persona)

        // 3. Verify zero-knowledge purge
        assertEquals("Vault must be purged after compile", 0, vault.totalBufferedCount())

        // 4. Verify 0ms collocation model gives learned bigram priority!
        val nextWords = collocationModel.predictNextWords("오늘", isInformal = true, limit = 5)
        assertTrue("Learned bigram '칼퇴하고' must be returned for '오늘'", nextWords.contains("칼퇴하고"))
        assertEquals("Learned bigram must be at the very top (index 0)", "칼퇴하고", nextWords.first())

        // 5. Verify sentence store contains canned phrase
        val queryResult = sentenceStore.query("ㄱㅅ", context = "내일", limit = 5)
        assertTrue(queryResult.any { it.sentence == "고생 많았어 내일 봐!" })

        // 6. Verify repository persisted
        val loaded = repository.load()
        assertTrue(loaded.personas.containsKey("messenger"))
        assertEquals(15, loaded.totalAnalyzedSentences)

        val summary = repository.getSummary()
        assertEquals(15, summary.totalSentences)
        assertEquals(2, summary.bigramsCount)
        assertEquals(1, summary.phrasesCount)
    }

    @Test
    fun testRepositoryReset() {
        repository.updatePersona(
            PersonaDna(
                category = "work",
                dominantTone = "Honorific",
                habitualEndings = listOf("~드리겠습니다."),
                frequentBigrams = listOf(DynamicBigram("배포", "모니터링")),
                cannedPhrases = listOf("검토 완료 후 공유드리겠습니다.")
            )
        )
        assertTrue(repository.load().personas.isNotEmpty())

        repository.clear()
        val cleared = repository.load()
        assertTrue(cleared.personas.isEmpty())
        assertEquals(0, cleared.totalAnalyzedSentences)
    }

    @Test
    fun testCompileFullProfileDoesNotInflatePersistedSentenceCount() {
        val persona = PersonaDna(
            category = "messenger",
            dominantTone = "Informal",
            habitualEndings = listOf("~해"),
            frequentBigrams = listOf(DynamicBigram("오늘", "칼퇴하고", 0.9f)),
            cannedPhrases = listOf("오늘 칼퇴하고 만나자")
        )
        compiler.compilePersona(persona)
        assertEquals(15, repository.load().totalAnalyzedSentences)

        compiler.compileFullProfile(repository.load())
        assertEquals(
            "Runtime recompile must not increment persisted sentence counts",
            15,
            repository.load().totalAnalyzedSentences
        )
        val nextWords = collocationModel.predictNextWords("오늘", isInformal = true, limit = 3)
        assertEquals("칼퇴하고", nextWords.first())
    }

    @Test
    fun testInstantCompileUsesActualBufferedSentenceCount() {
        val persona = profiler.profileOnDevice(
            "messenger",
            listOf("오늘 칼퇴하고 치맥 먹자 ㅋㅋ", "내일 판교에서 만나자", "완전 고마워 덕분이야")
        )
        compiler.compilePersona(persona, persist = true, analyzedSentenceCount = 3)
        assertEquals(3, repository.getStats().totalSentences)
        assertTrue(repository.getStats().hasLearnedData)
        assertEquals(100, repository.getStats().privacyOnDevicePercent)
    }
}
