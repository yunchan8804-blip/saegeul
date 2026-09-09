/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.junit.Assert.assertEquals
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
        val vault = TypingDnaVault(thresholdPerCategory = 15)
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
