/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TypingDnaRepositoryCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun repeatedStatsAndSummaryReuseSameRepositoryCacheWithoutDecryptingAgain() {
        val file = file("repeated.json")
        val profile = profile(updatedAt = 11L, totalSentences = 10)
        TypingDnaRepository(file).save(profile)
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)

        val firstStats = repository.getStats()
        val firstSummary = repository.getSummary()
        val decryptsAfterFirstRead = cipher.decryptCount
        val secondStats = repository.getStats()
        val secondSummary = repository.getSummary()

        assertEquals(1, decryptsAfterFirstRead)
        assertEquals(decryptsAfterFirstRead, cipher.decryptCount)
        assertSame(firstStats, secondStats)
        assertSame(firstSummary, secondSummary)
    }

    @Test
    fun savePublishesProfileAndInvalidatesDerivedValuesWithoutDecrypting() {
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file("save.json"), cipher)
        val initial = profile(updatedAt = 11L, totalSentences = 10)
        repository.save(initial)
        val oldStats = repository.getStats()
        cipher.reset()

        repository.save(profile(updatedAt = 22L, totalSentences = 20))
        val newStats = repository.getStats()

        assertEquals(0, cipher.decryptCount)
        assertEquals(20, newStats.totalSentences)
        assertFalse(oldStats === newStats)
    }

    @Test
    fun invalidateAndForceReloadReadCommittedProfileAgain() {
        val file = file("reload.json")
        TypingDnaRepository(file).save(profile(updatedAt = 11L, totalSentences = 10))
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)

        repository.getStats()
        assertEquals(1, cipher.decryptCount)
        repository.invalidateCache()
        repository.getStats()
        assertEquals(2, cipher.decryptCount)
        repository.getStats(forceReload = true)
        assertEquals(3, cipher.decryptCount)
    }

    @Test
    fun failedReadReturnsFallbackWithoutCachingItAndRetriesCommittedProfile() {
        val file = file("failed-read.json")
        val baseline = profile(updatedAt = 11L, totalSentences = 10)
        TypingDnaRepository(file).save(baseline)
        val cipher = FailingReadOnceCipher()
        val repository = TypingDnaRepository(file, cipher)

        assertEquals(0, repository.getStats().totalSentences)
        assertEquals(10, repository.getStats().totalSentences)
        assertEquals(2, cipher.decryptCount)
    }

    @Test
    fun anotherRepositoryWriteWithSameMetadataInvalidatesThisRepositoryCache() {
        val file = file("same-metadata-repository.json")
        val first = profile(updatedAt = 11L, totalSentences = 10)
        val second = profile(updatedAt = 22L, totalSentences = 20)
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)
        repository.save(first)
        val originalTimestamp = file.lastModified()
        val originalLength = file.length()
        cipher.reset()

        TypingDnaRepository(file).save(second)
        assertEquals(originalLength, file.length())
        assertTrue(file.setLastModified(originalTimestamp))

        assertEquals(second, repository.load())
        assertEquals(1, cipher.decryptCount)
    }

    @Test
    fun vaultFileWriteWithSameMetadataInvalidatesThisRepositoryCache() {
        val file = file("same-metadata-vault.json")
        val first = profile(updatedAt = 11L, totalSentences = 10)
        val second = profile(updatedAt = 22L, totalSentences = 20)
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)
        repository.save(first)
        val originalTimestamp = file.lastModified()
        val originalLength = file.length()
        cipher.reset()

        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name)).writeText(second.toJson())
        assertEquals(originalLength, file.length())
        assertTrue(file.setLastModified(originalTimestamp))

        assertEquals(second, repository.load())
        assertEquals(1, cipher.decryptCount)
    }

    @Test
    fun externalMtimeRollbackAndDeletionInvalidateCachedProfile() {
        val file = file("external-change.json")
        val profile = profile(updatedAt = 11L, totalSentences = 10)
        TypingDnaRepository(file).save(profile)
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)
        assertEquals(profile, repository.load())
        assertEquals(1, cipher.decryptCount)
        cipher.reset()

        assertTrue(file.setLastModified(file.lastModified() - 1_000L))
        assertEquals(profile, repository.load())
        assertEquals(1, cipher.decryptCount)
        assertTrue(file.delete())

        assertEquals(0, repository.load().totalAnalyzedSentences)
        assertEquals(1, cipher.decryptCount)
    }

    @Test
    fun backupOnlyProfileLoadsAndFailedSaveKeepsCommittedProfile() {
        val file = file("backup-and-failure.json")
        val baseline = profile(updatedAt = 11L, totalSentences = 10)
        TypingDnaRepository(file).save(baseline)
        val backup = File(file.parentFile, "${file.name}.bak")
        assertTrue(file.renameTo(backup))
        val repository = TypingDnaRepository(file)

        assertEquals(baseline, repository.load())

        val failingCipher = FailingWriteCipher()
        val failingRepository = TypingDnaRepository(file, failingCipher)
        assertEquals(baseline, failingRepository.load())
        failingCipher.failWrites = true
        val failure = runCatching {
            failingRepository.save(profile(updatedAt = 22L, totalSentences = 20))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(baseline, failingRepository.load())
        assertEquals(baseline, TypingDnaRepository(file).load())
    }

    @Test
    fun failedClearDoesNotPinAnEmptyProfileInCache() {
        val file = DeleteFailingFile(file("clear-failure.json").path)
        val baseline = profile(updatedAt = 11L, totalSentences = 10)
        val cipher = CountingCipher()
        val repository = TypingDnaRepository(file, cipher)
        repository.save(baseline)
        repository.getStats()
        cipher.reset()
        file.failDelete = true

        repository.clear()
        val stats = repository.getStats()

        assertEquals(10, stats.totalSentences)
        assertEquals(1, cipher.decryptCount)
    }

    private fun file(name: String) = File(tempFolder.root, name)

    private fun profile(updatedAt: Long, totalSentences: Int) = TypingDnaProfile(
        updatedAt = updatedAt,
        totalAnalyzedSentences = totalSentences,
        personas = mapOf(
            "work" to PersonaDna(
                category = "work",
                habitualEndings = listOf("합니다"),
                frequentBigrams = listOf(DynamicBigram("확인", "합니다")),
                cannedPhrases = listOf("확인했습니다.")
            )
        )
    )

    private class CountingCipher : VaultCipher {
        override val id: String = PlainVaultCipher.id
        var decryptCount = 0

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray =
            PlainVaultCipher.encrypt(plain, aad)

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            decryptCount += 1
            return PlainVaultCipher.decrypt(blob, aad)
        }

        fun reset() {
            decryptCount = 0
        }
    }

    private class FailingWriteCipher : VaultCipher {
        override val id: String = PlainVaultCipher.id
        var failWrites = false

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            if (failWrites) throw IOException("Injected vault write failure")
            return PlainVaultCipher.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray =
            PlainVaultCipher.decrypt(blob, aad)
    }

    private class FailingReadOnceCipher : VaultCipher {
        override val id: String = PlainVaultCipher.id
        var decryptCount = 0

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray =
            PlainVaultCipher.encrypt(plain, aad)

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            decryptCount += 1
            if (decryptCount == 1) throw IOException("Injected vault read failure")
            return PlainVaultCipher.decrypt(blob, aad)
        }
    }

    private class DeleteFailingFile(path: String) : File(path) {
        var failDelete = false

        override fun delete(): Boolean = if (failDelete) false else super.delete()
    }
}
