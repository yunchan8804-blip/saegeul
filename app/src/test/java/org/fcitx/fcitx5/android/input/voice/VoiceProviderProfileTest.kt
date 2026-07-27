/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProviderProfileTest {
    @Test
    fun `online voice mode waits for the dedicated STT credential before persisting`() {
        assertEquals(
            VoiceModeChangePlan(VoiceProviderMode.DeviceDictation, null),
            VoiceProviderModeSelectionPolicy.plan(
                selectedMode = VoiceProviderMode.DeviceDictation,
                hasCredential = false
            )
        )
        assertEquals(
            VoiceModeChangePlan(VoiceProviderMode.OpenAiRealtime, null),
            VoiceProviderModeSelectionPolicy.plan(
                selectedMode = VoiceProviderMode.OpenAiRealtime,
                hasCredential = true
            )
        )
        assertEquals(
            VoiceModeChangePlan(null, VoiceProviderMode.OpenAiApi),
            VoiceProviderModeSelectionPolicy.plan(
                selectedMode = VoiceProviderMode.OpenAiApi,
                hasCredential = false
            )
        )
    }

    @Test
    fun `successful STT credential save activates only the intended online mode`() {
        assertEquals(
            VoiceProviderMode.OpenAiRealtime,
            VoiceProviderModeSelectionPolicy.afterCredentialSaved(
                currentMode = VoiceProviderMode.DeviceDictation,
                requestedMode = VoiceProviderMode.OpenAiRealtime
            )
        )
        assertEquals(
            VoiceProviderMode.OpenAiApi,
            VoiceProviderModeSelectionPolicy.afterCredentialSaved(
                currentMode = VoiceProviderMode.DeviceDictation,
                requestedMode = null
            )
        )
        assertEquals(
            VoiceProviderMode.OpenAiRealtime,
            VoiceProviderModeSelectionPolicy.afterCredentialSaved(
                currentMode = VoiceProviderMode.OpenAiRealtime,
                requestedMode = null
            )
        )
    }

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
        assertEquals("gpt-realtime-whisper", accurate.realtimeTranscriptionModel)
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            accurate.endpoint
        )
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime-whisper",
            accurate.realtimeEndpoint
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
        assertFalse(
            VoiceProviderProfile(
                apiKey = "key",
                realtimeTranscriptionModel = "arbitrary-realtime-model"
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
                VoiceProviderMode.OpenAiRealtime,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceProviderPolicy.allowsSelectedMode(
                VoiceProviderMode.OpenAiRealtime,
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

    @Test
    fun `STT credential opens only for an allowed online voice transport`() {
        assertFalse(VoiceProviderPolicy.requiresCredential(VoiceProviderMode.DeviceDictation))
        assertTrue(VoiceProviderPolicy.requiresCredential(VoiceProviderMode.OpenAiRealtime))
        assertTrue(VoiceProviderPolicy.requiresCredential(VoiceProviderMode.OpenAiApi))

        assertFalse(
            VoiceProviderPolicy.allowsCredentialAccess(
                mode = VoiceProviderMode.DeviceDictation,
                allowsTextInspection = true,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceProviderPolicy.allowsCredentialAccess(
                mode = VoiceProviderMode.OpenAiApi,
                allowsTextInspection = false,
                allowsNetworkInput = true
            )
        )
        assertFalse(
            VoiceProviderPolicy.allowsCredentialAccess(
                mode = VoiceProviderMode.OpenAiRealtime,
                allowsTextInspection = true,
                allowsNetworkInput = false
            )
        )
        assertTrue(
            VoiceProviderPolicy.allowsCredentialAccess(
                mode = VoiceProviderMode.OpenAiApi,
                allowsTextInspection = true,
                allowsNetworkInput = true
            )
        )
    }

    @Test
    fun `private editor resolution never reads the STT credential`() {
        var credentialReads = 0

        val blocked = VoiceProviderResolver.resolve(
            mode = VoiceProviderMode.OpenAiApi,
            allowsCredentialAccess = false,
            loadCredential = {
                credentialReads += 1
                VoiceProviderProfile(apiKey = "must-not-be-read")
            }
        )

        assertEquals(VoiceProviderMode.OpenAiApi, blocked.mode)
        assertNull(blocked.profile)
        assertEquals(0, credentialReads)
    }
}
