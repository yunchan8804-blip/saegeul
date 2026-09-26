/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.voice.VoiceProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EncryptedProviderCredentialStoresTest {
    @Test
    fun `voice profile saves encrypted and decrypts from a fresh store`() = withRoot { root ->
        val profile = VoiceProviderProfile(
            apiKey = "voice-secret-for-storage-test"
        ).validate()
        val file = File(root, VoiceProviderCredentialStore.RELATIVE_PATH)

        VoiceProviderCredentialStore(root, FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS))
            .save(profile)

        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(profile.apiKey))
        assertEquals(
            profile,
            VoiceProviderCredentialStore(
                root,
                FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS)
            ).load()
        )
    }

    @Test
    fun `corrupt voice profile is rejected without throwing`() = withRoot { root ->
        val voiceProfile = VoiceProviderProfile(apiKey = "isolated-voice-secret").validate()
        val voiceStore = VoiceProviderCredentialStore(
            root,
            FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS)
        )
        voiceStore.save(voiceProfile)

        File(root, VoiceProviderCredentialStore.RELATIVE_PATH).writeText("corrupt")

        assertNull(voiceStore.load())
        assertTrue(voiceStore.hasStoredProfile())
    }

    @Test
    fun `voice credential store clears its file and Keystore alias`() = withRoot { root ->
        val clearedAliases = mutableSetOf<String>()
        val voiceStore = VoiceProviderCredentialStore(
            root,
            FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS, clearedAliases)
        )
        val voiceProfile = VoiceProviderProfile(apiKey = "voice-clear-secret").validate()
        val voiceFile = File(root, VoiceProviderCredentialStore.RELATIVE_PATH)

        voiceStore.save(voiceProfile)
        assertTrue(voiceFile.isFile)
        assertEquals(voiceProfile, voiceStore.load())

        voiceStore.clear()

        assertFalse(voiceFile.exists())
        assertFalse(voiceStore.hasStoredProfile())
        assertNull(voiceStore.load())
        assertEquals(setOf(VoiceProviderCredentialStore.KEY_ALIAS), clearedAliases)
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("provider-credential-stores-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeCipher(
        private val alias: String,
        private val clearedAliases: MutableSet<String> = mutableSetOf()
    ) : EncryptedProfileCipher {
        private val iv = ByteArray(12) { index ->
            (alias[index % alias.length].code xor index).toByte()
        }

        override fun encrypt(plaintext: ByteArray): EncryptedProfilePayload =
            EncryptedProfilePayload(iv.copyOf(), plaintext.obfuscated())

        override fun decrypt(encrypted: EncryptedProfilePayload): ByteArray {
            check(encrypted.iv.contentEquals(iv)) { "Credential alias mismatch" }
            return encrypted.payload.obfuscated()
        }

        override fun clear() {
            clearedAliases += alias
        }

        private fun ByteArray.obfuscated(): ByteArray = mapIndexed { index, byte ->
            (byte.toInt() xor alias[index % alias.length].code xor MASK).toByte()
        }.toByteArray()

        private companion object {
            const val MASK = 0x5a
        }
    }
}
