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

class GifProviderCredentialStoreTest {
    @Test
    fun encryptedStoreRoundTripsWithoutWritingPlaintext() = withStore { store, file, cipher ->
        val credential = "fake-value-for-storage-contract"

        store.saveKey(credential)

        assertEquals(GifProviderCredentialState.Configured, store.state())
        assertEquals(credential, store.loadKey())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(credential))
        assertEquals(1, cipher.encryptions)
    }

    @Test
    fun clearRemovesPayloadAndEncryptionAlias() = withStore { store, file, cipher ->
        store.saveKey("fake-value-to-delete")

        store.clear()

        assertFalse(file.exists())
        assertNull(store.loadKey())
        assertEquals(GifProviderCredentialState.Missing, store.state())
        assertTrue(cipher.cleared)
    }

    @Test
    fun corruptPayloadIsUnreadableAndNeverReturned() = withStore { store, file, _ ->
        file.parentFile?.mkdirs()
        file.writeText("not an encrypted credential")

        assertNull(store.loadKey())
        assertEquals(GifProviderCredentialState.Unreadable, store.state())
    }

    @Test
    fun interruptedReplaceRecoversPreviousEncryptedPayload() = withStore { store, file, _ ->
        val credential = "fake-value-before-interruption"
        store.saveKey(credential)
        val backup = File(file.parentFile, "${file.name}.bak")
        assertTrue(file.renameTo(backup))

        assertEquals(credential, store.loadKey())
        assertTrue(file.isFile || backup.isFile)
    }

    @Test
    fun rejectsBlankOrOversizedCredentials() = withStore { store, _, _ ->
        listOf("   ", "x".repeat(513)).forEach { invalid ->
            val failure = runCatching { store.saveKey(invalid) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(failure?.message.orEmpty().contains(invalid))
        }
    }

    private fun withStore(
        block: (GifProviderCredentialStore, File, FakeCipher) -> Unit
    ) {
        val directory = Files.createTempDirectory("gif-provider-store-test").toFile()
        try {
            val file = File(directory, "no-backup/gif/provider.bin")
            val cipher = FakeCipher()
            block(GifProviderCredentialStore(file, cipher), file, cipher)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeCipher : GifCredentialCipher {
        var encryptions = 0
        var cleared = false

        override fun encrypt(plaintext: ByteArray): GifEncryptedPayload {
            encryptions++
            return GifEncryptedPayload(IV.copyOf(), plaintext.map { it.obfuscated() }.toByteArray())
        }

        override fun decrypt(encrypted: GifEncryptedPayload): ByteArray {
            check(encrypted.iv.contentEquals(IV))
            return encrypted.payload.map { it.obfuscated() }.toByteArray()
        }

        override fun clear() {
            cleared = true
        }

        private fun Byte.obfuscated(): Byte = (toInt() xor MASK).toByte()

        private companion object {
            const val MASK = 0x5a
            val IV = ByteArray(12) { index -> (index + 1).toByte() }
        }
    }
}
