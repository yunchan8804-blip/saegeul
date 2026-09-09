/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class StorePersistenceLockTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun personalNgramSaveDoesNotBlockLearningAndClearWinsOverInFlightSave() {
        val file = tempFile("personal_ngram.json")
        val cipher = BlockingCipher()
        val model = PersonalNgramModel(storeFile = file, cipher = cipher)
        model.learn("오늘 회의 참석합니다", PACKAGE_NAME)

        withExecutor { executor ->
            assertSaveAllowsOperation(cipher, executor, model::save) {
                model.learn("내일 회의 참석합니다", PACKAGE_NAME)
                assertTrue(model.stats().learnedSentences > 0)
            }
        }

        val clearCipher = BlockingCipher()
        val clearingModel = PersonalNgramModel(storeFile = file, cipher = clearCipher)
        assertClearWinsOverInFlightSave(clearCipher, clearingModel::save, clearingModel::clear)

        assertEquals(0, PersonalNgramModel(storeFile = file, cipher = clearCipher).stats().unigrams)
    }

    @Test
    fun personalSentenceVaultSaveDoesNotBlockRecordingAndClearWinsOverInFlightSave() {
        val file = tempFile("personal_sentences.json")
        val cipher = BlockingCipher()
        val vault = PersonalSentenceVault(storeFile = file, cipher = cipher)
        vault.record("오늘 회의 참석하겠습니다", PACKAGE_NAME)

        withExecutor { executor ->
            assertSaveAllowsOperation(cipher, executor, vault::save) {
                vault.record("내일 회의 참석하겠습니다", PACKAGE_NAME)
                assertTrue(vault.stats().sentences > 0)
            }
        }

        val clearCipher = BlockingCipher()
        val clearingVault = PersonalSentenceVault(storeFile = file, cipher = clearCipher)
        assertClearWinsOverInFlightSave(clearCipher, clearingVault::save, clearingVault::clear)

        assertEquals(0, PersonalSentenceVault(storeFile = file, cipher = clearCipher).stats().sentences)
    }

    @Test
    fun predictionMetricsSaveDoesNotBlockRecordingAndClearWinsOverInFlightSave() {
        val file = tempFile("prediction_metrics.json")
        val cipher = BlockingCipher()
        val store = PredictionMetricsStore(storeFile = file, cipher = cipher, zone = ZoneId.of("UTC"))
        store.recordShown(1)

        withExecutor { executor ->
            assertSaveAllowsOperation(cipher, executor, store::save) {
                store.recordLearned(1, 2)
                assertTrue(store.summary().learnedSentences > 0)
            }
        }

        val clearCipher = BlockingCipher()
        val clearingStore = PredictionMetricsStore(storeFile = file, cipher = clearCipher, zone = ZoneId.of("UTC"))
        assertClearWinsOverInFlightSave(clearCipher, clearingStore::save, clearingStore::clear)

        assertEquals(0, PredictionMetricsStore(storeFile = file, cipher = clearCipher, zone = ZoneId.of("UTC")).summary().activeDays)
    }

    @Test
    fun correctionPatternSaveDoesNotBlockRecordingAndClearWinsOverInFlightSave() {
        val file = tempFile("correction_patterns.json")
        val cipher = BlockingCipher()
        val store = CorrectionPatternStore(storeFile = file, cipher = cipher)
        store.recordCorrection("사묘ㅏ함니다", "감사합니다")

        withExecutor { executor ->
            assertSaveAllowsOperation(cipher, executor, store::save) {
                store.recordCorrection("감사함니다", "감사합니다")
                assertTrue(store.stats().first > 0)
            }
        }

        val clearCipher = BlockingCipher()
        val clearingStore = CorrectionPatternStore(storeFile = file, cipher = clearCipher)
        assertClearWinsOverInFlightSave(clearCipher, clearingStore::save, clearingStore::clear)

        assertEquals(0, CorrectionPatternStore(storeFile = file, cipher = clearCipher).stats().first)
    }

    private fun tempFile(name: String): File = tempFolder.newFile(name)

    private fun assertSaveAllowsOperation(
        cipher: BlockingCipher,
        executor: ExecutorService,
        save: () -> Unit,
        operation: () -> Unit
    ) {
        val saveFuture = executor.submit<Unit> { save() }
        assertTrue(cipher.encryptStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        executor.submit<Unit> { operation() }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        cipher.release.countDown()
        saveFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun assertClearWinsOverInFlightSave(
        cipher: BlockingCipher,
        save: () -> Unit,
        clear: () -> Unit
    ) {
        withExecutor { executor ->
            val saveFuture = executor.submit<Unit> { save() }
            assertTrue(cipher.encryptStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val clearStarted = CountDownLatch(1)
            val clearFuture = executor.submit<Unit> {
                clearStarted.countDown()
                clear()
            }
            assertTrue(clearStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                clearFuture.get(100, TimeUnit.MILLISECONDS)
            }
            cipher.release.countDown()
            saveFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            clearFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun withExecutor(block: (ExecutorService) -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        try {
            block(executor)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private class BlockingCipher : VaultCipher {
        override val id: String = "blocking"
        val encryptStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            encryptStarted.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return plain
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = blob
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.test"
        const val TIMEOUT_SECONDS = 2L
    }
}
