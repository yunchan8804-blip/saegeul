/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typingdna.DynamicBigram
import org.fcitx.fcitx5.android.input.ai.typingdna.PersonaDna
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaCompiler
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaInstantSync
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaProfile
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.vault.ImmediateReadRetryRule
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.SwitchableVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A vault file that exists but cannot be read must never be replaced by the empty state a store
 * falls back to, nor by the next thing it learns.
 */
class UnreadableVaultProtectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val retryRule = ImmediateReadRetryRule()

    private fun file(name: String) = File(tempFolder.root, name)

    private fun unreadable(base: File) =
        base.parentFile.listFiles { _, name -> name.startsWith("${base.name}.unreadable-") }.orEmpty().toList()

    private fun persona(category: String = "messenger") = PersonaDna(
        category = category,
        dominantTone = "Honorific",
        habitualEndings = listOf("합니다"),
        frequentBigrams = listOf(DynamicBigram("감사", "합니다", 0.8f)),
        cannedPhrases = listOf("감사합니다")
    )

    @Test
    fun typingDnaRepositoryKeepsAnUnreadableProfileAndItsLevel() {
        val storage = file("typing_dna.json")
        TypingDnaRepository(storage, SwitchableVaultCipher())
            .save(TypingDnaProfile(updatedAt = 1L, totalAnalyzedSentences = 400))
        val before = storage.readBytes()
        val repository = TypingDnaRepository(storage, SwitchableVaultCipher(failReads = true))

        val failure = runCatching { repository.updatePersona(persona(), 15) }.exceptionOrNull()

        assertNotNull(failure)
        assertArrayEquals(before, storage.readBytes())
        assertEquals(400, TypingDnaRepository(storage, SwitchableVaultCipher()).load(forceReload = true).totalAnalyzedSentences)
    }

    @Test
    fun typingDnaRepositoryKeepsAProfileThatDecryptsButDoesNotParse() {
        val storage = file("typing_dna_parse.json")
        VaultFile(storage, PlainVaultCipher, VaultFile.aadFor(storage.name)).writeText("{\"totalAnalyzedSentences\": ")
        val before = storage.readBytes()
        val repository = TypingDnaRepository(storage, PlainVaultCipher)

        val failure = runCatching { repository.updatePersona(persona(), 15) }.exceptionOrNull()

        assertNotNull(failure)
        assertArrayEquals(before, storage.readBytes())
    }

    @Test
    fun correctionPatternStoreDoesNotOverwriteAnUnreadableFile() {
        val storage = file("personal_corrections.json")
        val writer = CorrectionPatternStore(storage, cipher = SwitchableVaultCipher())
        assertTrue(writer.recordCorrection("사묘ㅏ함니다", "감사합니다"))
        writer.save()
        val before = storage.readBytes()

        val blocked = CorrectionPatternStore(storage, cipher = SwitchableVaultCipher(failReads = true))
        assertTrue(blocked.recordCorrection("사묘ㅏ함니다", "감사합니다"))
        blocked.save()

        assertArrayEquals(before, storage.readBytes())
        assertEquals(1, CorrectionPatternStore(storage, cipher = SwitchableVaultCipher()).stats().first)
    }

    @Test
    fun correctionPatternStoreKeepsContentItCannotParse() {
        val storage = file("personal_corrections_parse.json")
        VaultFile(storage, PlainVaultCipher, VaultFile.aadFor(storage.name)).writeText("{\"pairs\": [")
        val before = storage.readBytes()
        val store = CorrectionPatternStore(storage, cipher = PlainVaultCipher)

        assertTrue(store.recordCorrection("사묘ㅏ함니다", "감사합니다"))
        store.save()

        assertArrayEquals(before, storage.readBytes())
    }

    @Test
    fun personalSentenceVaultDoesNotOverwriteAnUnreadableFile() {
        val storage = file("personal_sentences.json")
        val writer = PersonalSentenceVault(storage, SwitchableVaultCipher())
        writer.record("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
        writer.save()
        val before = storage.readBytes()

        val blocked = PersonalSentenceVault(storage, SwitchableVaultCipher(failReads = true))
        blocked.record("오늘 저녁에 같이 밥 먹을래요", "com.kakao.talk")
        blocked.save()

        assertArrayEquals(before, storage.readBytes())
        assertEquals(1, PersonalSentenceVault(storage, SwitchableVaultCipher()).stats().sentences)
    }

    @Test
    fun personalNgramModelDoesNotOverwriteAnUnreadableFile() {
        val storage = file("personal_ngram.json")
        val writer = PersonalNgramModel(storage, cipher = SwitchableVaultCipher())
        writer.learn("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
        writer.save()
        val before = storage.readBytes()

        val blocked = PersonalNgramModel(storage, cipher = SwitchableVaultCipher(failReads = true))
        blocked.learn("오늘 저녁에 같이 밥 먹을래요", "com.kakao.talk")
        blocked.save()

        assertArrayEquals(before, storage.readBytes())
        assertTrue(PersonalNgramModel(storage, cipher = SwitchableVaultCipher()).stats().unigrams > 0)
    }

    @Test
    fun userClearRemovesUnreadableFilesPreservedCopiesAndCountersAndLearningResumes() {
        val corrections = file("clear_corrections.json")
        val sentences = file("clear_sentences.json")
        val ngrams = file("clear_ngram.json")
        val failing = SwitchableVaultCipher(failReads = true)
        CorrectionPatternStore(corrections, cipher = SwitchableVaultCipher()).apply {
            recordCorrection("사묘ㅏ함니다", "감사합니다")
            save()
        }
        PersonalSentenceVault(sentences, SwitchableVaultCipher()).apply {
            record("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
            save()
        }
        PersonalNgramModel(ngrams, cipher = SwitchableVaultCipher()).apply {
            learn("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
            save()
        }
        // Each failed open leaves a read block and a failure counter behind.
        val correctionStore = CorrectionPatternStore(corrections, cipher = failing)
        val sentenceVault = PersonalSentenceVault(sentences, failing)
        val ngramModel = PersonalNgramModel(ngrams, cipher = failing)
        File(sentences.path + ".bak").writeBytes(sentences.readBytes())
        // An earlier preserved copy of the same store.
        File(ngrams.parentFile, ngrams.name + ".unreadable-1").writeText("older preserved copy")

        correctionStore.clear()
        sentenceVault.clear()
        ngramModel.clear()

        assertTrue("left: ${tempFolder.root.list()?.toList()}", tempFolder.root.list().isNullOrEmpty())
        correctionStore.recordCorrection("사묘ㅏ함니다", "감사합니다")
        correctionStore.save()
        sentenceVault.record("오늘 저녁에 같이 밥 먹을래요", "com.kakao.talk")
        sentenceVault.save()
        ngramModel.learn("오늘 저녁에 같이 밥 먹을래요", "com.kakao.talk")
        ngramModel.save()
        assertEquals(1, CorrectionPatternStore(corrections, cipher = SwitchableVaultCipher()).stats().first)
        assertEquals(1, PersonalSentenceVault(sentences, SwitchableVaultCipher()).stats().sentences)
        assertTrue(PersonalNgramModel(ngrams, cipher = SwitchableVaultCipher()).stats().unigrams > 0)
    }

    @Test
    fun userClearOfTypingDnaProfileAndStagingWorksWhileBlocked() {
        val profile = file("clear_typing_dna.json")
        val staging = file("clear_typing_dna_pending.json")
        TypingDnaRepository(profile, SwitchableVaultCipher())
            .save(TypingDnaProfile(updatedAt = 1L, totalAnalyzedSentences = 400))
        TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = SwitchableVaultCipher())
            .recordSentence("com.kakao.talk", "첫 번째 문장을 저장해요")
        val failing = SwitchableVaultCipher(failReads = true)
        val repository = TypingDnaRepository(profile, failing)
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = failing)
        assertNotNull(runCatching { repository.updatePersona(persona(), 15) }.exceptionOrNull())

        repository.clear()
        vault.purge()

        assertTrue("left: ${tempFolder.root.list()?.toList()}", !profile.exists() && !File(profile.path + ".readfail").exists())
        assertFalse(File(staging.path + ".readfail").exists())
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = SwitchableVaultCipher()).totalBufferedCount())
        repository.save(TypingDnaProfile(updatedAt = 2L, totalAnalyzedSentences = 1))
        assertEquals(1, TypingDnaRepository(profile, SwitchableVaultCipher()).load(forceReload = true).totalAnalyzedSentences)
    }

    @Test
    fun personalNgramModelLoadsAllTablesOrNone() {
        val storage = file("personal_ngram_partial.json")
        val broken = """{"v":1,"learned":3,"last":9,"tables":{
            "*":{"uni":{"회의":[2.0,5],"자료":[1.0,5]},"bi":{},"tri":{}},
            "work":{"uni":{"보내":["x",5]},"bi":{},"tri":{}}}}"""
        VaultFile(storage, PlainVaultCipher, VaultFile.aadFor(storage.name)).writeText(broken)
        val before = storage.readBytes()
        val model = PersonalNgramModel(storage, cipher = PlainVaultCipher)

        assertEquals(0, model.stats().unigrams)
        var seen = 0
        model.forEachUnigram { _, _ -> seen++ }
        assertEquals(0, seen)
        assertTrue(model.categoryCounts().isEmpty())

        model.learn("회의 자료 준비", "com.kakao.talk")
        model.save()
        assertArrayEquals(before, storage.readBytes())
    }

    @Test
    fun typingDnaVaultDoesNotOverwriteAnUnreadableStagingFile() {
        val staging = file("typing_dna_pending.json")
        val writer = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = SwitchableVaultCipher())
        writer.recordSentence("com.kakao.talk", "첫 번째 문장을 저장해요")
        val before = staging.readBytes()

        val blocked = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = SwitchableVaultCipher(failReads = true))
        blocked.recordSentence("com.kakao.talk", "두 번째 문장은 저장되지 않아요")

        assertArrayEquals(before, staging.readBytes())
        assertEquals(1, blocked.totalBufferedCount())
    }

    @Test
    fun threeFailedOpensPreserveTheFileAndLearningStartsFresh() {
        val storage = file("personal_ngram_three.json")
        val writer = PersonalNgramModel(storage, cipher = SwitchableVaultCipher())
        writer.learn("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
        writer.save()
        val before = storage.readBytes()
        val failing = SwitchableVaultCipher(failReads = true)

        PersonalNgramModel(storage, cipher = failing)
        PersonalNgramModel(storage, cipher = failing)
        val third = PersonalNgramModel(storage, cipher = failing)

        assertEquals(0, third.stats().unigrams)
        third.learn("새로 배우는 문장입니다", "com.kakao.talk")
        third.save()

        assertArrayEquals(before, unreadable(storage).single().readBytes())
        assertFalse(File(storage.path + ".readfail").exists())
        val reloaded = PersonalNgramModel(storage, cipher = SwitchableVaultCipher())
        assertTrue(reloaded.stats().unigrams > 0)
        assertEquals(1, unreadable(storage).size)
    }

    @Test
    fun healthyOpenAfterEarlierFailuresLoadsTheOriginalDataAndClearsTheCounter() {
        val storage = file("personal_sentences_recover.json")
        val writer = PersonalSentenceVault(storage, SwitchableVaultCipher())
        writer.record("내일 회의 자료를 미리 보내드릴게요", "com.kakao.talk")
        writer.save()
        val failing = SwitchableVaultCipher(failReads = true)
        PersonalSentenceVault(storage, failing)
        PersonalSentenceVault(storage, failing)
        assertTrue(File(storage.path + ".readfail").exists())

        val healthy = PersonalSentenceVault(storage, SwitchableVaultCipher())

        assertEquals(1, healthy.stats().sentences)
        assertFalse(File(storage.path + ".readfail").exists())
        healthy.record("오늘 저녁에 같이 밥 먹을래요", "com.kakao.talk")
        healthy.save()
        assertEquals(2, PersonalSentenceVault(storage, SwitchableVaultCipher()).stats().sentences)
        assertTrue(unreadable(storage).isEmpty())
    }

    @Test
    fun stagingLoadKeepsOnlyTheNewestSentencesPerCategory() {
        val staging = file("typing_dna_cap.json")
        val writer = TypingDnaVault(thresholdPerCategory = 10_000, maxCapacityPerCategory = 1000, stagingFile = staging)
        val sentences = (0 until 500).map { "안녕하세요 반갑습니다 ${(0xAC00 + it).toChar()}" }
        sentences.forEach { writer.recordSentence("com.kakao.talk", it) }
        assertEquals(500, writer.totalBufferedCount())

        val reloaded = TypingDnaVault(thresholdPerCategory = 10_000, stagingFile = staging)

        assertEquals(50, reloaded.totalBufferedCount())
        assertEquals(sentences.takeLast(50), reloaded.getSentences(TypingDnaVault.CATEGORY_MESSENGER))
    }

    @Test
    fun stagingLoadDropsUnknownCategories() {
        val staging = file("typing_dna_categories.json")
        VaultFile(staging, PlainVaultCipher, VaultFile.aadFor(staging.name)).writeText(
            """{"messenger":["안녕하세요 반갑습니다"],"made-up":["이건 알 수 없는 범주입니다"]}"""
        )

        val vault = TypingDnaVault(stagingFile = staging)

        assertEquals(setOf(TypingDnaVault.CATEGORY_MESSENGER), vault.snapshot().keys)
    }

    @Test
    fun sentenceVaultLoadAppliesTheSentenceCap() {
        val storage = file("personal_sentences_cap.json")
        val writer = PersonalSentenceVault(storage, maxSentences = 3000)
        (0 until 40).forEach { writer.record("오늘은 ${(0xAC00 + it).toChar()}곳에서 회의를 합니다", "com.kakao.talk") }
        writer.save()
        assertEquals(40, writer.stats().sentences)

        val capped = PersonalSentenceVault(storage, maxSentences = 25)

        assertEquals(25, capped.stats().sentences)
    }

    @Test
    fun failingAnalyzedSentenceListenerDoesNotMakeTheBatchCountTwice() {
        val storage = file("typing_dna_listener.json")
        val repository = TypingDnaRepository(storage)
        val vault = TypingDnaVault(thresholdPerCategory = 20)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        val compiler = TypingDnaCompiler(
            collocationModel = KoreanCollocationModel(),
            sentenceStore = PersonalizedSentenceStore(),
            repository = repository
        )
        val sync = TypingDnaInstantSync(vault, repository, compiler = compiler)
        val previous = TypingDnaRepository.onSentencesAnalyzed
        TypingDnaRepository.onSentencesAnalyzed = { _, _ -> throw IllegalStateException("listener failed") }
        try {
            sync.syncNow()
            sync.syncNow()
        } finally {
            TypingDnaRepository.onSentencesAnalyzed = previous
        }

        assertEquals(1, repository.load(forceReload = true).totalAnalyzedSentences)
        assertEquals(0, vault.totalBufferedCount())
    }

    @Test
    fun completionIndexesShrinkWhenUnigramsArePruned() {
        val model = PersonalNgramModel(maxUnigrams = 10)
        for (i in 0 until 1000) {
            val word = "${(0xAC00 + i / 40).toChar()}${(0xB000 + i % 40).toChar()}"
            model.learn(word, "com.kakao.talk")
        }

        val index = privateMap(model, "completionIndex")
        val byFirst = privateMap(model, "wordsByFirstChar")
        val byJamo = privateMap(model, "wordsByJamoFirst")
        val byChoseong = privateMap(model, "wordsByChoseongFirst")

        // Two tables ("*" and the app category) each hold at most maxUnigrams words.
        val bound = 2 * 10
        assertTrue("completionIndex=${index.size}", index.size in 1..bound)
        for (indexed in listOf(byFirst, byJamo, byChoseong)) {
            val words = indexed.values.sumOf { (it as List<*>).size }
            assertTrue("indexed words=$words", words in 1..bound)
        }
    }

    private fun privateMap(model: PersonalNgramModel, field: String): Map<*, *> {
        val f = PersonalNgramModel::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(model) as Map<*, *>
    }
}
