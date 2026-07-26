/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiphyProviderCredentialStoreTest {
    @Test
    fun encryptedConfigurationRoundTripsAndTracksApprovalState() = withStore { store, file, _ ->
        val key = "fake-giphy-production-key"
        store.save(GiphyProviderConfiguration(key, productionApproved = false, mediaCachingApproved = false))

        assertEquals(GiphyCredentialState.KeyOnly, store.state())
        assertEquals(key, store.load()?.apiKey)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(key))

        store.save(GiphyProviderConfiguration(key, productionApproved = true, mediaCachingApproved = true))
        assertEquals(GiphyCredentialState.Ready, store.state())
        assertTrue(store.load()?.mediaCachingApproved == true)
    }

    @Test
    fun mediaCopyApprovalCannotExistWithoutProductionApproval() = withStore { store, _, _ ->
        val failure = runCatching {
            store.save(GiphyProviderConfiguration("fake-key", false, true))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun corruptPayloadFailsClosedAndClearRemovesKeyAlias() = withStore { store, file, cipher ->
        file.parentFile?.mkdirs()
        file.writeText("corrupt")
        assertEquals(GiphyCredentialState.Unreadable, store.state())
        assertNull(store.load())

        store.clear()
        assertEquals(GiphyCredentialState.Missing, store.state())
        assertTrue(cipher.cleared)
    }

    @Test
    fun providerSelectionDefaultsAndOverwritesAtomically() {
        val directory = Files.createTempDirectory("gif-provider-selection-test").toFile()
        try {
            val store = GifProviderSelectionStore(File(directory, "gif/provider-selection"))
            assertEquals(GifProviderSelection.Standard, store.load())
            store.save(GifProviderSelection.Giphy)
            assertEquals(GifProviderSelection.Giphy, store.load())
            store.save(GifProviderSelection.Standard)
            assertEquals(GifProviderSelection.Standard, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withStore(block: (GiphyProviderCredentialStore, File, FakeCipher) -> Unit) {
        val directory = Files.createTempDirectory("giphy-provider-store-test").toFile()
        try {
            val file = File(directory, "no-backup/gif/giphy-provider.bin")
            val cipher = FakeCipher()
            block(GiphyProviderCredentialStore(file, cipher), file, cipher)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeCipher : GifCredentialCipher {
        var cleared = false
        override fun encrypt(plaintext: ByteArray) = GifEncryptedPayload(
            IV.copyOf(),
            plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray()
        )
        override fun decrypt(encrypted: GifEncryptedPayload): ByteArray {
            check(encrypted.iv.contentEquals(IV))
            return encrypted.payload.map { (it.toInt() xor MASK).toByte() }.toByteArray()
        }
        override fun clear() { cleared = true }

        private companion object {
            const val MASK = 0x5a
            val IV = ByteArray(12) { (it + 1).toByte() }
        }
    }
}
