/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSpeechEngineSelectorTest {
    private fun select(
        sdkInt: Int,
        onDevice: Boolean,
        service: Boolean,
        network: Boolean
    ) = DeviceSpeechEngineSelector.select(sdkInt, onDevice, service, network)

    @Test
    fun onDeviceWinsFromAndroid12WhenAvailable() {
        assertEquals(DeviceSpeechEngine.OnDevice, select(31, true, true, true))
        assertEquals(DeviceSpeechEngine.OnDevice, select(34, true, false, false))
    }

    @Test
    fun onDeviceStaysAllowedInOfflineModeBecauseVoiceStaysOnTheDevice() {
        assertEquals(DeviceSpeechEngine.OnDevice, select(33, true, true, false))
    }

    @Test
    fun onDeviceIsNeverPickedBeforeAndroid12() {
        assertEquals(DeviceSpeechEngine.SystemService, select(30, true, true, true))
        assertEquals(DeviceSpeechEngine.Unavailable, select(30, true, true, false))
    }

    @Test
    fun systemServiceNeedsBothTheServiceAndNetworkInput() {
        assertEquals(DeviceSpeechEngine.SystemService, select(34, false, true, true))
        assertEquals(DeviceSpeechEngine.Unavailable, select(34, false, true, false))
        assertEquals(DeviceSpeechEngine.Unavailable, select(34, false, false, true))
    }

    @Test
    fun nothingAvailableMeansUnavailable() {
        assertEquals(DeviceSpeechEngine.Unavailable, select(34, false, false, false))
    }

    @Test
    fun environmentReportsTheSelectedEngine() {
        val environment = DeviceSpeechEnvironment(
            sdkInt = 34,
            onDeviceAvailable = false,
            serviceAvailable = true,
            allowsNetworkInput = true
        )
        assertEquals(DeviceSpeechEngine.SystemService, environment.engine)
    }
}

class DeviceSpeechFallbackPolicyTest {
    private fun environment(
        sdkInt: Int = 31,
        serviceAvailable: Boolean = true,
        allowsNetworkInput: Boolean = true
    ) = DeviceSpeechEnvironment(
        sdkInt = sdkInt,
        onDeviceAvailable = true,
        serviceAvailable = serviceAvailable,
        allowsNetworkInput = allowsNetworkInput
    )

    private fun retry(
        engine: DeviceSpeechEngine = DeviceSpeechEngine.OnDevice,
        environment: DeviceSpeechEnvironment = environment(),
        errorCode: Int = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        heardSpeech: Boolean = false,
        alreadyRetried: Boolean = false
    ) = DeviceSpeechFallbackPolicy.shouldRetryWithSystemService(
        engine, environment, errorCode, heardSpeech, alreadyRetried
    )

    @Test
    fun androidTwelveOnDeviceLanguageFailureRetriesOnTheSystemService() {
        assertTrue(retry(environment = environment(sdkInt = 31)))
        assertTrue(retry(environment = environment(sdkInt = 32)))
        assertTrue(retry(errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertTrue(retry(errorCode = SpeechRecognizer.ERROR_CLIENT))
        assertTrue(retry(errorCode = SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
    }

    @Test
    fun androidThirteenAndLaterRetriesOnlyWhenTheLanguageIsMissing() {
        assertTrue(retry(environment = environment(sdkInt = 33)))
        assertTrue(
            retry(
                environment = environment(sdkInt = 35),
                errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            )
        )
        assertFalse(
            retry(environment = environment(sdkInt = 33), errorCode = SpeechRecognizer.ERROR_CLIENT)
        )
        assertFalse(
            retry(
                environment = environment(sdkInt = 35),
                errorCode = SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            )
        )
    }

    @Test
    fun retriesAtMostOnceAndNeverAfterSpeechWasRecognized() {
        assertFalse(retry(alreadyRetried = true))
        assertFalse(retry(heardSpeech = true))
    }

    @Test
    fun onlyOnDeviceAttemptsAreRetried() {
        assertFalse(retry(engine = DeviceSpeechEngine.SystemService))
        assertFalse(retry(engine = DeviceSpeechEngine.Unavailable))
    }

    @Test
    fun errorsUnrelatedToTheEngineAreNotRetried() {
        assertFalse(retry(errorCode = SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(retry(errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(retry(errorCode = SpeechRecognizer.ERROR_AUDIO))
        assertFalse(retry(errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun noRetryWhenTheSystemServiceIsMissingOrNetworkInputIsBlocked() {
        assertFalse(retry(environment = environment(serviceAvailable = false)))
        assertFalse(retry(environment = environment(allowsNetworkInput = false)))
    }
}
