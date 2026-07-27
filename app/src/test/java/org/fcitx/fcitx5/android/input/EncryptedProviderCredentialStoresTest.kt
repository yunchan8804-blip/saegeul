/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.voice.VoiceProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EncryptedProviderCredentialStoresTest {
    @Test
    fun `AI profile saves encrypted and decrypts from a fresh store`() = withRoot { root ->
        val profile = AiProviderProfile(
            displayName = "Test writing provider",
            apiKey = "writing-secret-for-storage-test"
        ).validate()
        val file = File(root, AiProviderCredentialStore.RELATIVE_PATH)

        AiProviderCredentialStore(root, FakeCipher(AiProviderCredentialStore.KEY_ALIAS))
            .save(profile)

        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(profile.apiKey))
        assertEquals(
            profile,
            AiProviderCredentialStore(root, FakeCipher(AiProviderCredentialStore.KEY_ALIAS)).load()
        )
    }

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
    fun `corrupt profile is rejected without affecting the other credential`() = withRoot { root ->
        val aiProfile = AiProviderProfile(apiKey = "isolated-writing-secret").validate()
        val voiceProfile = VoiceProviderProfile(apiKey = "isolated-voice-secret").validate()
        val aiStore = AiProviderCredentialStore(
            root,
            FakeCipher(AiProviderCredentialStore.KEY_ALIAS)
        )
        val voiceStore = VoiceProviderCredentialStore(
            root,
            FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS)
        )
        aiStore.save(aiProfile)
        voiceStore.save(voiceProfile)

        File(root, AiProviderCredentialStore.RELATIVE_PATH).writeText("corrupt")

        assertNull(aiStore.load())
        assertEquals(voiceProfile, voiceStore.load())
        assertTrue(voiceStore.hasStoredProfile())
    }

    @Test
    fun `AI and voice use distinct files and aliases and clear independently`() = withRoot { root ->
        val clearedAliases = mutableSetOf<String>()
        val aiStore = AiProviderCredentialStore(
            root,
            FakeCipher(AiProviderCredentialStore.KEY_ALIAS, clearedAliases)
        )
        val voiceStore = VoiceProviderCredentialStore(
            root,
            FakeCipher(VoiceProviderCredentialStore.KEY_ALIAS, clearedAliases)
        )
        val aiProfile = AiProviderProfile(apiKey = "writing-clear-secret").validate()
        val voiceProfile = VoiceProviderProfile(apiKey = "voice-clear-secret").validate()
        val aiFile = File(root, AiProviderCredentialStore.RELATIVE_PATH)
        val voiceFile = File(root, VoiceProviderCredentialStore.RELATIVE_PATH)

        assertNotEquals(
            AiProviderCredentialStore.RELATIVE_PATH,
            VoiceProviderCredentialStore.RELATIVE_PATH
        )
        assertNotEquals(
            AiProviderCredentialStore.KEY_ALIAS,
            VoiceProviderCredentialStore.KEY_ALIAS
        )

        aiStore.save(aiProfile)
        voiceStore.save(voiceProfile)
        aiStore.clear()

        assertFalse(aiFile.exists())
        assertFalse(aiStore.hasCustomProfile())
        assertNull(aiStore.load())
        assertEquals(setOf(AiProviderCredentialStore.KEY_ALIAS), clearedAliases)
        assertTrue(voiceFile.isFile)
        assertEquals(voiceProfile, voiceStore.load())

        voiceStore.clear()

        assertFalse(voiceFile.exists())
        assertFalse(voiceStore.hasStoredProfile())
        assertNull(voiceStore.load())
        assertEquals(
            setOf(
                AiProviderCredentialStore.KEY_ALIAS,
                VoiceProviderCredentialStore.KEY_ALIAS
            ),
            clearedAliases
        )
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
