/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.typingdna.*

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class TypingDnaPersistenceFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun failedRepositorySavePropagatesAndPreservesCachedAndDiskBaseline() {
        val storage = tempFolder.newFile("typing_dna_repository.json")
        val cipher = ToggleFailingCipher()
        val repository = TypingDnaRepository(storage, cipher)
        val baseline = TypingDnaProfile(updatedAt = 1L, totalAnalyzedSentences = 4)
        repository.save(baseline)
        val beforeDigest = digest(storage)

        cipher.failWrites = true
        val failure = runCatching {
            repository.save(baseline.copy(updatedAt = 2L, totalAnalyzedSentences = 5))
        }.exceptionOrNull()
        val afterDigest = digest(storage)
        val cachedProfile = repository.load()
        val diskProfile = repository.load(forceReload = true)

        assertEquals(beforeDigest, afterDigest)
        assertTrue(
            "Expected IOException, actual=${failure?.javaClass?.name}; before=$beforeDigest after=$afterDigest; " +
                "cached=${cachedProfile.totalAnalyzedSentences} disk=${diskProfile.totalAnalyzedSentences}",
            failure is IOException
        )
        assertEquals(baseline, cachedProfile)
        assertEquals(baseline, diskProfile)
    }

    @Test
    fun failedInstantSyncPropagatesAndLeavesPendingBatchAndCountUntouched() {
        val storage = tempFolder.newFile("typing_dna_sync.json")
        val cipher = ToggleFailingCipher()
        val repository = TypingDnaRepository(storage, cipher)
        val baseline = TypingDnaProfile(updatedAt = 1L, totalAnalyzedSentences = 4)
        repository.save(baseline)
        val beforeDigest = digest(storage)
        val vault = TypingDnaVault(thresholdPerCategory = 20)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        val compiler = TypingDnaCompiler(
            collocationModel = KoreanCollocationModel(),
            sentenceStore = PersonalizedSentenceStore(),
            repository = repository
        )
        val sync = TypingDnaInstantSync(vault, repository, compiler = compiler)

        cipher.failWrites = true
        val failure = runCatching { sync.syncNow() }.exceptionOrNull()
        val afterDigest = digest(storage)
        val remainingPending = vault.totalBufferedCount()
        val cachedProfile = repository.load()
        val diskProfile = repository.load(forceReload = true)

        assertEquals(beforeDigest, afterDigest)
        assertTrue(
            "Expected IOException, actual=${failure?.javaClass?.name}; before=$beforeDigest after=$afterDigest; " +
                "pending=$remainingPending cached=${cachedProfile.totalAnalyzedSentences} " +
                "disk=${diskProfile.totalAnalyzedSentences}",
            failure is IOException
        )
        assertEquals(1, remainingPending)
        assertEquals(baseline.totalAnalyzedSentences, cachedProfile.totalAnalyzedSentences)
        assertEquals(baseline, diskProfile)

        cipher.failWrites = false
        assertEquals(5, sync.syncNow().totalSentences)
        assertEquals(0, vault.totalBufferedCount())
        assertEquals(5, sync.syncNow().totalSentences)
    }

    @Test
    fun failedStagingSaveKeepsTheSentencePendingAndTheNextSuccessfulSaveWritesIt() {
        val staging = File(tempFolder.root, "typing_dna_pending_record.json")
        val cipher = ToggleFailingCipher()
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = cipher)
        vault.recordSentence("com.kakao.talk", "첫 번째 문장을 저장해요")

        cipher.failWrites = true
        vault.recordSentence("com.kakao.talk", "두 번째 문장은 저장이 실패해요")

        assertEquals(2, vault.totalBufferedCount())
        assertEquals(
            listOf("첫 번째 문장을 저장해요"),
            TypingDnaVault(stagingFile = staging, cipher = cipher).getSentences(TypingDnaVault.CATEGORY_MESSENGER)
        )

        cipher.failWrites = false
        vault.recordSentence("com.kakao.talk", "세 번째 문장으로 다시 저장해요")

        assertEquals(
            listOf("첫 번째 문장을 저장해요", "두 번째 문장은 저장이 실패해요", "세 번째 문장으로 다시 저장해요"),
            TypingDnaVault(stagingFile = staging, cipher = cipher).getSentences(TypingDnaVault.CATEGORY_MESSENGER)
        )
    }

    @Test
    fun failedPurgeThrowsAndKeepsMemoryMatchingTheStagingFile() {
        val staging = File(tempFolder.root, "typing_dna_pending_purge.json")
        val cipher = ToggleFailingCipher()
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = cipher)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        vault.recordSentence("com.slack", "배포 모니터링 부탁드립니다")
        val beforeDigest = digest(staging)

        cipher.failWrites = true
        val allFailure = runCatching { vault.purge() }.exceptionOrNull()
        val categoryFailure = runCatching { vault.purge(TypingDnaVault.CATEGORY_WORK) }.exceptionOrNull()

        assertTrue("actual=${allFailure?.javaClass?.name}", allFailure is TypingDnaPersistenceException)
        assertTrue("actual=${categoryFailure?.javaClass?.name}", categoryFailure is TypingDnaPersistenceException)
        assertEquals(beforeDigest, digest(staging))
        assertEquals(2, vault.totalBufferedCount())
        assertEquals(TypingDnaVault(stagingFile = staging, cipher = cipher).snapshot(), vault.snapshot())

        cipher.failWrites = false
        vault.purge()

        assertEquals(0, vault.totalBufferedCount())
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = cipher).totalBufferedCount())
    }

    @Test
    fun failedStagingSaveBeforeProcessingThrowsWithoutRunningTheProcessor() {
        val staging = File(tempFolder.root, "typing_dna_pending_process_save.json")
        val cipher = ToggleFailingCipher()
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = cipher)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        vault.recordSentence("com.slack", "배포 모니터링 부탁드립니다")
        val beforeDigest = digest(staging)
        val processed = mutableListOf<String>()

        cipher.failWrites = true
        val failure = runCatching { vault.processPending { category, _ -> processed += category } }.exceptionOrNull()

        assertTrue("actual=${failure?.javaClass?.name}", failure is TypingDnaPersistenceException)
        assertTrue(processed.isEmpty())
        assertEquals(2, vault.totalBufferedCount())
        assertEquals(beforeDigest, digest(staging))

        cipher.failWrites = false
        assertEquals(2, vault.processPending { category, _ -> processed += category })

        assertEquals(listOf(TypingDnaVault.CATEGORY_MESSENGER, TypingDnaVault.CATEGORY_WORK), processed)
        assertEquals(0, vault.totalBufferedCount())
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = cipher).totalBufferedCount())
    }

    @Test
    fun failedProcessorRestoresItsBatchInMemoryAndInTheStagingFile() {
        val staging = File(tempFolder.root, "typing_dna_pending_process_failure.json")
        val cipher = ToggleFailingCipher()
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = cipher)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        vault.recordSentence("com.slack", "배포 모니터링 부탁드립니다")
        val processed = mutableListOf<String>()
        val processorFailure = IllegalStateException("on-device profiling failed")

        val failure = runCatching {
            vault.processPending { category, _ ->
                if (category == TypingDnaVault.CATEGORY_WORK) throw processorFailure
                processed += category
            }
        }.exceptionOrNull()

        assertSame(processorFailure, failure)
        assertEquals(0, processorFailure.suppressed.size)
        assertEquals(listOf(TypingDnaVault.CATEGORY_MESSENGER), processed)
        assertEquals(mapOf(TypingDnaVault.CATEGORY_WORK to listOf("배포 모니터링 부탁드립니다")), vault.snapshot())
        assertEquals(vault.snapshot(), TypingDnaVault(stagingFile = staging, cipher = cipher).snapshot())

        assertEquals(1, vault.processPending { category, _ -> processed += category })

        assertEquals(listOf(TypingDnaVault.CATEGORY_MESSENGER, TypingDnaVault.CATEGORY_WORK), processed)
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = cipher).totalBufferedCount())
    }

    @Test
    fun failedProcessorWithAFailedRollbackSaveKeepsTheBatchInMemoryAndSuppressesTheSaveFailure() {
        val staging = File(tempFolder.root, "typing_dna_pending_rollback_failure.json")
        val cipher = ToggleFailingCipher()
        val vault = TypingDnaVault(thresholdPerCategory = 20, stagingFile = staging, cipher = cipher)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")
        val processorFailure = IllegalStateException("on-device profiling failed")

        val failure = runCatching {
            vault.processPending { _, _ ->
                cipher.failWrites = true
                throw processorFailure
            }
        }.exceptionOrNull()

        assertSame(processorFailure, failure)
        assertEquals(1, processorFailure.suppressed.size)
        assertTrue(processorFailure.suppressed.single() is IOException)
        assertEquals(1, vault.totalBufferedCount())
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = cipher).totalBufferedCount())

        cipher.failWrites = false
        val processed = mutableListOf<List<String>>()
        assertEquals(1, vault.processPending { _, sentences -> processed += sentences })

        assertEquals(listOf(listOf("친구야 오늘 저녁에 만나자")), processed)
        assertEquals(0, TypingDnaVault(stagingFile = staging, cipher = cipher).totalBufferedCount())
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    private class ToggleFailingCipher : VaultCipher {
        var failWrites: Boolean = false

        override val id: String = PlainVaultCipher.id

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            if (failWrites) throw IOException("Injected vault write failure")
            return PlainVaultCipher.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray =
            PlainVaultCipher.decrypt(blob, aad)
    }
}
