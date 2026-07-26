/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProviderProfileTest {
    @Test
    fun `voice profile accepts only the official OpenAI STT models`() {
        val accurate = VoiceProviderProfile(apiKey = " voice-secret ").validate()
        val efficient = VoiceProviderProfile(
            apiKey = "voice-secret",
            transcriptionModel = VoiceTranscriptionModel.Efficient.id
        ).validate()

        assertEquals("voice-secret", accurate.apiKey)
        assertEquals("gpt-4o-transcribe", accurate.transcriptionModel)
        assertEquals("gpt-4o-mini-transcribe", efficient.transcriptionModel)
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            accurate.endpoint
        )
    }

    @Test
    fun `voice profile rejects missing key custom endpoint and arbitrary models`() {
        assertFalse(VoiceProviderProfile(apiKey = "").isConfigured)
        assertFalse(
            VoiceProviderProfile(
                apiKey = "key",
                baseUrl = "https://compatible.example/v1"
            ).isConfigured
        )
        assertFalse(
            VoiceProviderProfile(
                apiKey = "key",
                transcriptionModel = "writing-model"
            ).isConfigured
        )
        assertFalse(VoiceProviderProfile(apiKey = "bad\nkey").isConfigured)
        assertTrue(VoiceProviderProfile(apiKey = "key").isConfigured)
    }

    @Test
    fun `device dictation does not inherit writing AI or app network policy`() {
        assertTrue(
            VoiceProviderPolicy.allowsSelectedMode(
                VoiceProviderMode.DeviceDictation,
                allowsNetworkInput = false
            )
        )
        assertFalse(
            VoiceProviderPolicy.allowsSelectedMode(
                VoiceProviderMode.OpenAiApi,
                allowsNetworkInput = false
            )
        )
        assertTrue(
            VoiceProviderPolicy.allowsSelectedMode(
                VoiceProviderMode.OpenAiApi,
                allowsNetworkInput = true
            )
        )
    }
}
