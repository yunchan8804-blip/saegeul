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
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD RED Tests for PersonalizedSentenceStore:
 * Verifies local persistent storage, instant choseong index lookup,
 * context keyword ranking, reinforcement scoring, and LRU pruning.
 */
class PersonalizedSentenceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var storeFile: File
    private lateinit var store: PersonalizedSentenceStore

    @Before
    fun setUp() {
        morphology = ChoseongMorphologyEngine()
        storeFile = tempFolder.newFile("test_personalized_sentences.json")
        store = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 10)
    }

    @Test
    fun testInsertAndRetrieveByChoseong() {
        val record = PersonalizedSentenceRecord(
            sentence = "내일 판교에서 뵙겠습니다.",
            intent = ContextualIntent.Scheduling,
            tone = KoreanTone.Honorific,
            keywords = listOf("내일", "판교"),
            source = "synthetic_llm"
        )
        store.upsert(record)

        // Query by prefix choseong
        val results1 = store.query(queryChoseong = "ㄴㅇㅍㄱ", context = "", limit = 5)
        assertEquals(1, results1.size)
        assertEquals("내일 판교에서 뵙겠습니다.", results1[0].sentence)

        // Query by substring choseong "ㅍㄱ" (판교)
        val results2 = store.query(queryChoseong = "ㅍㄱ", context = "", limit = 5)
        assertEquals(1, results2.size)
        assertEquals("내일 판교에서 뵙겠습니다.", results2[0].sentence)

        // Query by unrelated choseong
        val results3 = store.query(queryChoseong = "ㄱㄴ", context = "", limit = 5)
        assertTrue(results3.isEmpty())
    }

    @Test
    fun testContextKeywordMatchingAndScoreRanking() {
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "판교역 1번 출구에서 만나요!",
                intent = ContextualIntent.Scheduling,
                tone = KoreanTone.Informal,
                keywords = listOf("판교", "출구"),
                score = 1.0f
            )
        )
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "내일 판교에서 몇 시에 볼까?",
                intent = ContextualIntent.Scheduling,
                tone = KoreanTone.Informal,
                keywords = listOf("내일", "판교"),
                score = 2.5f // Higher score through past selection
            )
        )

        // Context contains "내일 판교"
        val results = store.query(queryChoseong = "", context = "내일 판교 어디서 볼까?", limit = 5)
        assertEquals(2, results.size)
        // Higher scored sentence matching both "내일" and "판교" must rank first
        assertEquals("내일 판교에서 몇 시에 볼까?", results[0].sentence)
    }

    @Test
    fun testLruCapacityEviction() {
        val smallStore = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 3)
        smallStore.upsert(PersonalizedSentenceRecord(sentence = "문장 1", score = 1.0f))
        smallStore.upsert(PersonalizedSentenceRecord(sentence = "문장 2", score = 3.0f))
        smallStore.upsert(PersonalizedSentenceRecord(sentence = "문장 3", score = 1.5f))
        assertEquals(3, smallStore.size())

        // Adding 4th item should evict the lowest scored item ("문장 1")
        smallStore.upsert(PersonalizedSentenceRecord(sentence = "문장 4", score = 2.0f))
        assertEquals(3, smallStore.size())
        assertFalse(smallStore.contains("문장 1"))
        assertTrue(smallStore.contains("문장 2"))
        assertTrue(smallStore.contains("문장 4"))
    }

    @Test
    fun testPersistenceAndReload() {
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "배포 완료 후 모니터링 중입니다.",
                intent = ContextualIntent.WorkProgress,
                tone = KoreanTone.Technical,
                keywords = listOf("배포", "모니터링"),
                score = 2.0f,
                useCount = 3
            )
        )
        store.save()

        // Create a new store instance pointing to the same file
        val reloadedStore = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 10)
        reloadedStore.load()

        assertEquals(1, reloadedStore.size())
        val found = reloadedStore.query(queryChoseong = "ㅂㅍ", context = "", limit = 5)
        assertEquals(1, found.size)
        assertEquals("배포 완료 후 모니터링 중입니다.", found[0].sentence)
        assertEquals(2.0f, found[0].score, 0.01f)
        assertEquals(3, found[0].useCount)
    }

    @Test
    fun saveSnapshotDoesNotBlockRecordsWhileCipherEncryptionWaits() {
        val cipher = BlockingVaultCipher()
        val concurrentFile = tempFolder.newFile("concurrent_personalized_sentences.json")
        val concurrentStore = PersonalizedSentenceStore(
            storageFile = concurrentFile,
            morphology = morphology,
            maxCapacity = 10,
            cipher = cipher
        )
        concurrentStore.upsert(PersonalizedSentenceRecord(sentence = "첫 저장 문장", keywords = listOf("첫")))

        val saveFailure = AtomicReference<Throwable?>()
        val saveThread = thread(start = true) {
            runCatching { concurrentStore.save() }.exceptionOrNull()?.let(saveFailure::set)
        }
        assertTrue(cipher.encryptionStarted.await(5, TimeUnit.SECONDS))

        val getFinished = CountDownLatch(1)
        val upsertFinished = CountDownLatch(1)
        val getFailure = AtomicReference<Throwable?>()
        val upsertFailure = AtomicReference<Throwable?>()
        val getThread = thread(start = true) {
            runCatching { concurrentStore.get("첫 저장 문장") }.exceptionOrNull()?.let(getFailure::set)
            getFinished.countDown()
        }
        val upsertThread = thread(start = true) {
            runCatching {
                concurrentStore.upsert(PersonalizedSentenceRecord(sentence = "후속 변경 문장", keywords = listOf("후속")))
            }.exceptionOrNull()?.let(upsertFailure::set)
            upsertFinished.countDown()
        }

        try {
            assertTrue(getFinished.await(1, TimeUnit.SECONDS))
            assertTrue(upsertFinished.await(1, TimeUnit.SECONDS))
            assertTrue(saveThread.isAlive)
        } finally {
            cipher.allowEncryption.countDown()
            saveThread.join(5_000)
            getThread.join(5_000)
            upsertThread.join(5_000)
        }
        assertTrue(saveFailure.get() == null)
        assertTrue(getFailure.get() == null)
        assertTrue(upsertFailure.get() == null)

        val firstReload = PersonalizedSentenceStore(storageFile = concurrentFile, morphology = morphology, maxCapacity = 10, cipher = cipher)
        firstReload.load()
        assertNotNull(firstReload.get("첫 저장 문장"))
        assertFalse(firstReload.contains("후속 변경 문장"))

        concurrentStore.save()
        val secondReload = PersonalizedSentenceStore(storageFile = concurrentFile, morphology = morphology, maxCapacity = 10, cipher = cipher)
        secondReload.load()
        assertNotNull(secondReload.get("첫 저장 문장"))
        assertNotNull(secondReload.get("후속 변경 문장"))
    }

    private class BlockingVaultCipher : VaultCipher {
        override val id: String = "blocking"
        val encryptionStarted = CountDownLatch(1)
        val allowEncryption = CountDownLatch(1)

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            encryptionStarted.countDown()
            check(allowEncryption.await(5, TimeUnit.SECONDS))
            return plain
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = blob
    }
}
