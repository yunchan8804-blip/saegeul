/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialState
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialStore
import org.fcitx.fcitx5.android.input.gif.GiphyCredentialState
import org.fcitx.fcitx5.android.input.gif.GiphyProviderConfiguration
import org.fcitx.fcitx5.android.input.gif.GiphyProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderProfile
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Pins the on-disk envelope each provider store wrote before the stores shared
 * [EncryptedProfileStorage]: files written by earlier releases must still load, and new writes
 * must stay readable by them.
 */
class EncryptedProfileStorageFormatTest {
    @Test
    fun `KLIPY credential matches the fixed legacy bytes in both directions`() = withRoot { root ->
        val file = File(root, "gif/provider.bin")
        file.parentFile?.mkdirs()
        file.writeBytes(hex(KLIPY_FIXTURE_HEX))

        val store = GifProviderCredentialStore(file, XorCipher())
        assertEquals("abc", store.loadKey())
        assertEquals(GifProviderCredentialState.Configured, store.state())

        file.delete()
        store.saveKey("abc")
        assertArrayEquals(hex(KLIPY_FIXTURE_HEX), file.readBytes())
    }

    @Test
    fun `KLIPY credential written by the legacy writer loads`() = withRoot { root ->
        val file = File(root, "gif/provider.bin")
        val key = "legacy-klipy-credential"
        writeLegacyEnvelope(file, KLIPY_MAGIC, xor(key.toByteArray(Charsets.UTF_8)))

        assertEquals(key, GifProviderCredentialStore(file, XorCipher()).loadKey())
    }

    @Test
    fun `GIPHY configuration written by the legacy writer loads`() = withRoot { root ->
        val file = File(root, "gif/giphy-provider.bin")
        val key = "legacy-giphy-credential"
        writeLegacyEnvelope(file, GIPHY_MAGIC, xor(legacyGiphyPayload(key, true, false)))

        val store = GiphyProviderCredentialStore(file, XorCipher())
        assertEquals(GiphyProviderConfiguration(key, true, false), store.load())
        assertEquals(GiphyCredentialState.Ready, store.state())
    }

    @Test
    fun `GIPHY configuration is written in the legacy layout`() = withRoot { root ->
        val file = File(root, "gif/giphy-provider.bin")
        val key = "new-giphy-credential"

        GiphyProviderCredentialStore(file, XorCipher())
            .save(GiphyProviderConfiguration(key, productionApproved = true, mediaCachingApproved = true))

        val envelope = readLegacyEnvelope(file, GIPHY_MAGIC)
        assertArrayEquals(IV, envelope.iv)
        assertArrayEquals(legacyGiphyPayload(key, true, true), xor(envelope.payload))
    }

    @Test
    fun `voice profile written by the legacy writer loads`() = withRoot { root ->
        val file = File(root, VoiceProviderCredentialStore.RELATIVE_PATH)
        val json = """{"apiKey":"legacy-voice-credential","transcriptionModel":"${VoiceTranscriptionModel.Efficient.id}"}"""
        writeLegacyEnvelope(file, VOICE_MAGIC, xor(json.toByteArray(Charsets.UTF_8)))

        val profile = VoiceProviderCredentialStore(root, XorCipher()).load()

        assertEquals("legacy-voice-credential", profile?.apiKey)
        assertEquals(VoiceTranscriptionModel.Efficient.id, profile?.transcriptionModel)
    }

    @Test
    fun `voice profile is written in the legacy layout`() = withRoot { root ->
        val profile = VoiceProviderProfile(apiKey = "new-voice-credential").validate()

        VoiceProviderCredentialStore(root, XorCipher()).save(profile)

        val envelope = readLegacyEnvelope(File(root, VoiceProviderCredentialStore.RELATIVE_PATH), VOICE_MAGIC)
        assertArrayEquals(IV, envelope.iv)
        val json = Json.parseToJsonElement(xor(envelope.payload).toString(Charsets.UTF_8)).jsonObject
        assertEquals(profile.apiKey, json.getValue("apiKey").jsonPrimitive.content)
        assertEquals(profile.transcriptionModel, json.getValue("transcriptionModel").jsonPrimitive.content)
        assertEquals(
            profile.realtimeTranscriptionModel,
            json.getValue("realtimeTranscriptionModel").jsonPrimitive.content
        )
        assertEquals(profile.diarizationModel, json.getValue("diarizationModel").jsonPrimitive.content)
        assertEquals(profile.baseUrl, json.getValue("baseUrl").jsonPrimitive.content)
    }

    /** The envelope layout every store wrote before the shared storage existed. */
    private fun writeLegacyEnvelope(file: File, magic: Int, payload: ByteArray) {
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(magic)
                output.writeInt(IV.size)
                output.write(IV)
                output.writeInt(payload.size)
                output.write(payload)
            }
            bytes.toByteArray()
        }
        file.writeBytes(bytes)
    }

    private fun readLegacyEnvelope(file: File, magic: Int): EncryptedProfilePayload =
        DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
            assertEquals(magic, input.readInt())
            val iv = ByteArray(input.readInt()).also(input::readFully)
            val payload = ByteArray(input.readInt()).also(input::readFully)
            assertEquals(-1, input.read())
            EncryptedProfilePayload(iv, payload)
        }

    /** The GIPHY plaintext layout: version, key length, key, production flag, media flag. */
    private fun legacyGiphyPayload(key: String, production: Boolean, media: Boolean): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                output.writeInt(1)
                output.writeInt(keyBytes.size)
                output.write(keyBytes)
                output.writeBoolean(production)
                output.writeBoolean(media)
            }
            bytes.toByteArray()
        }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("encrypted-profile-format-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class XorCipher : EncryptedProfileCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedProfilePayload =
            EncryptedProfilePayload(IV.copyOf(), xor(plaintext))

        override fun decrypt(encrypted: EncryptedProfilePayload): ByteArray {
            check(encrypted.iv.contentEquals(IV))
            return xor(encrypted.payload)
        }

        override fun clear() = Unit
    }

    private companion object {
        const val KLIPY_MAGIC = 0x47494631
        const val GIPHY_MAGIC = 0x47495031
        const val VOICE_MAGIC = 0x56505231
        const val MASK = 0x5a
        val IV = ByteArray(12) { index -> (index + 1).toByte() }

        /** Magic "GIF1", IV length 12, IV 01..0c, ciphertext length 3, "abc" XOR 0x5a. */
        const val KLIPY_FIXTURE_HEX = "47494631" + "0000000c" + "0102030405060708090a0b0c" +
            "00000003" + "3b3839"

        fun xor(bytes: ByteArray): ByteArray =
            ByteArray(bytes.size) { index -> (bytes[index].toInt() xor MASK).toByte() }

        fun hex(value: String): ByteArray =
            ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
