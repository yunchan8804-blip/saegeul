/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces the user-visible "sync shows 0 sentences" failure:
 * Hangul chat is committed via clipboard transports, the IME process dies,
 * and the dashboard must still compile staged sentences from disk.
 */
class TypingDnaInstantSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun bufferedPasteThenImeDeathThenDashboardSyncPersistsHangulSentences() {
        val staging = tempFolder.newFile("typing_dna_pending.json")
        val repoFile = tempFolder.newFile("typing_dna.json")
        val vault = TypingDnaVault(
            thresholdPerCategory = 15,
            stagingFile = staging
        )
        val collector = UserTypingContextCollector(
            onSentenceCommitted = { pkg, sentence -> vault.recordSentence(pkg, sentence) }
        )
        val sink = TypingDnaCommitSink(collector)

        // SystemPaste / CtrlV never go through commitTextToEditor. Only the sink fires.
        sink.onEditorTextCommitted(
            packageName = "com.kakao.talk",
            text = "오늘 저녁에 만나자",
            inspectionAllowed = true
        )
        assertEquals(
            "Casual Hangul stays pending until send/finish",
            0,
            vault.totalBufferedCount()
        )

        sink.onEditorSubmit(packageName = "com.kakao.talk", inspectionAllowed = true)
        assertEquals(1, vault.totalBufferedCount())
        assertTrue("Staging file must survive IME destruction", staging.length() > 2)

        // IME service is gone; a new vault instance is what the dashboard process would open.
        val vaultAfterImeDeath = TypingDnaVault(
            thresholdPerCategory = 15,
            stagingFile = staging
        )
        assertEquals(
            "Pending Hangul must rehydrate after IME death",
            1,
            vaultAfterImeDeath.totalBufferedCount()
        )

        val repository = TypingDnaRepository(repoFile)
        val stats = TypingDnaInstantSync.persistOnly(vaultAfterImeDeath, repository)

        assertEquals(1, stats.totalSentences)
        assertTrue(stats.hasLearnedData)
        assertEquals(0, vaultAfterImeDeath.totalBufferedCount())
        assertEquals(
            "Drained staging must not be replayed on the next dashboard open",
            0,
            TypingDnaVault(stagingFile = staging).totalBufferedCount()
        )
        assertEquals(
            "A second sync with an empty vault must not inflate sentence counts",
            1,
            TypingDnaInstantSync.persistOnly(
                TypingDnaVault(stagingFile = staging),
                repository
            ).totalSentences
        )
    }

    @Test
    fun privateEditorAndBlankCommitsAreIgnored() {
        val collectorSentences = mutableListOf<String>()
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(
                onSentenceCommitted = { _, sentence -> collectorSentences.add(sentence) }
            )
        )
        sink.onEditorTextCommitted("com.kakao.talk", "비밀 메시지입니다", inspectionAllowed = false)
        sink.onEditorSubmit("com.kakao.talk", inspectionAllowed = false)
        sink.onEditorTextCommitted("com.kakao.talk", "   ", inspectionAllowed = true)
        sink.onEditorTextCommitted(null, "오늘 저녁에 만나자", inspectionAllowed = true)
        assertTrue(collectorSentences.isEmpty())
    }

    @Test
    fun koreanEndingFromPasteTransportIsCollectedWithoutSubmit() {
        val vault = TypingDnaVault(thresholdPerCategory = 15)
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(
                onSentenceCommitted = { pkg, sentence -> vault.recordSentence(pkg, sentence) }
            )
        )
        sink.onEditorTextCommitted("com.kakao.talk", "확인했습니다", inspectionAllowed = true)
        assertEquals(1, vault.totalBufferedCount())
    }

    @Test
    fun deferredThresholdCallbackAfterInstantSyncDoesNotAnalyzeTheBatchTwice() {
        val scheduledCategories = mutableListOf<String>()
        val vault = TypingDnaVault(
            thresholdPerCategory = 1,
            onBatchReady = { category, _ -> scheduledCategories.add(category) }
        )
        val repository = TypingDnaRepository(tempFolder.newFile("typing_dna_callback.json"))
        val compiler = TypingDnaCompiler(
            collocationModel = KoreanCollocationModel(),
            sentenceStore = PersonalizedSentenceStore(),
            repository = repository
        )
        val sync = TypingDnaInstantSync(vault, repository, compiler = compiler)

        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        assertEquals(listOf(TypingDnaVault.CATEGORY_MESSENGER), scheduledCategories)

        assertEquals(1, sync.syncNow().totalSentences)
        scheduledCategories.forEach { category -> sync.syncNow(category) }

        assertEquals(1, repository.getStats().totalSentences)
        assertEquals(0, vault.totalBufferedCount())
    }

    @Test
    fun syncNowUsesOnlyOnDeviceProfiling() {
        val vault = TypingDnaVault(thresholdPerCategory = 15)
        val repository = TypingDnaRepository(tempFolder.newFile("typing_dna_on_device.json"))
        val compiler = TypingDnaCompiler(
            collocationModel = KoreanCollocationModel(),
            sentenceStore = PersonalizedSentenceStore(),
            repository = repository
        )
        val llmCalls = AtomicInteger()
        val profiler = TypingDnaProfiler(llmCaller = {
            llmCalls.incrementAndGet()
            throw AssertionError("Instant sync must not call an LLM profiler")
        })
        val sync = TypingDnaInstantSync(vault, repository, profiler, compiler)

        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")

        assertEquals(1, sync.syncNow().totalSentences)
        assertEquals(0, vault.totalBufferedCount())
        assertEquals(0, llmCalls.get())
    }

    @Test
    fun whitespaceOnlyCommitPassesThroughTheSinkToCloseAPendingSentence() {
        // A bare space or newline commit is not itself a sentence, but it is the delimiter the
        // collector needs to close a pending Korean-ending boundary once a per-syllable engine
        // commit has only armed it (a trailing chunk shorter than 2 chars never emits on its
        // own). The sink must forward that bare space instead of dropping it as blank.
        val committedSentences = mutableListOf<String>()
        val sink = TypingDnaCommitSink(
            UserTypingContextCollector(
                onSentenceCommitted = { _, sentence -> committedSentences.add(sentence) }
            )
        )
        sink.onEditorTextCommitted("com.kakao.talk", "확인했습니", inspectionAllowed = true)
        sink.onEditorTextCommitted("com.kakao.talk", "다", inspectionAllowed = true)
        assertTrue("Sentence must stay pending before the delimiter arrives", committedSentences.isEmpty())

        sink.onEditorTextCommitted("com.kakao.talk", " ", inspectionAllowed = true)
        assertEquals(listOf("확인했습니다"), committedSentences)
    }
}
