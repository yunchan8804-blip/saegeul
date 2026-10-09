/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class DeviceSpeechRecoveryPolicyTest {
    private fun recreate(
        errorCode: Int? = SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        heardSpeech: Boolean = false,
        alreadyRecreated: Boolean = false
    ) = DeviceSpeechRecoveryPolicy.shouldRecreate(errorCode, heardSpeech, alreadyRecreated)

    @Test
    fun aStartThatNeverReportedBackIsRecreated() {
        assertTrue(recreate(errorCode = null))
    }

    @Test
    fun serviceSideFailuresAreRecreated() {
        assertTrue(recreate(errorCode = SpeechRecognizer.ERROR_CLIENT))
        assertTrue(recreate(errorCode = SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertTrue(recreate(errorCode = SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        assertTrue(recreate(errorCode = SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
    }

    @Test
    fun errorsAFreshRecognizerCannotFixAreNotRecreated() {
        assertFalse(recreate(errorCode = SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(recreate(errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertFalse(recreate(errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(recreate(errorCode = SpeechRecognizer.ERROR_NETWORK))
        assertFalse(recreate(errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun recreatesAtMostOnceAndNeverAfterSpeechWasRecognized() {
        assertFalse(recreate(heardSpeech = true))
        assertFalse(recreate(errorCode = null, heardSpeech = true))
        assertFalse(recreate(alreadyRecreated = true))
        assertFalse(recreate(errorCode = null, alreadyRecreated = true))
    }

    private fun nextLanguage(
        errorCode: Int = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        heardSpeech: Boolean = false,
        currentIndex: Int = 0,
        candidateCount: Int = 2
    ) = DeviceSpeechLanguageFallbackPolicy.nextLanguageIndex(
        errorCode,
        heardSpeech,
        currentIndex,
        candidateCount
    )

    @Test
    fun languageErrorsMoveToTheNextCandidate() {
        assertEquals(1, nextLanguage(errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(1, nextLanguage(errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(2, nextLanguage(currentIndex = 1, candidateCount = 3))
    }

    @Test
    fun noNextLanguageAfterTheLastCandidate() {
        assertNull(nextLanguage(currentIndex = 1, candidateCount = 2))
        assertNull(nextLanguage(currentIndex = 0, candidateCount = 1))
    }

    @Test
    fun noNextLanguageAfterSpeechWasRecognized() {
        assertNull(nextLanguage(heardSpeech = true))
    }

    @Test
    fun otherErrorsDoNotChangeTheLanguage() {
        assertNull(nextLanguage(errorCode = SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(nextLanguage(errorCode = SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}

class DeviceSpeechFailurePolicyTest {
    private val environment = DeviceSpeechEnvironment(
        sdkInt = 33,
        onDeviceAvailable = true,
        serviceAvailable = true,
        allowsNetworkInput = true
    )

    private fun decide(
        engine: DeviceSpeechEngine = DeviceSpeechEngine.OnDevice,
        environment: DeviceSpeechEnvironment = this.environment,
        errorCode: Int? = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        heardSpeech: Boolean = false,
        stopRequested: Boolean = false,
        languageIndex: Int = 0,
        candidateCount: Int = 2,
        retriedWithSystemService: Boolean = false,
        recreated: Boolean = false
    ) = DeviceSpeechFailurePolicy.decide(
        engine = engine,
        environment = environment,
        errorCode = errorCode,
        heardSpeech = heardSpeech,
        stopRequested = stopRequested,
        languageIndex = languageIndex,
        candidateCount = candidateCount,
        retriedWithSystemService = retriedWithSystemService,
        recreated = recreated
    )

    @Test
    fun missingLanguagePackTriesTheSystemServiceBeforeAnotherLanguage() {
        assertEquals(DeviceSpeechFailureDecision.SystemService, decide())
        assertEquals(
            DeviceSpeechFailureDecision.SystemService,
            decide(errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        )
    }

    @Test
    fun movesToTheNextLanguageOnceTheSystemServiceWasTried() {
        assertEquals(
            DeviceSpeechFailureDecision.NextLanguage(1),
            decide(engine = DeviceSpeechEngine.SystemService, retriedWithSystemService = true)
        )
    }

    @Test
    fun movesToTheNextLanguageWhenTheSystemServiceIsNotAllowed() {
        val offline = environment.copy(allowsNetworkInput = false)
        assertEquals(
            DeviceSpeechFailureDecision.NextLanguage(1),
            decide(environment = offline)
        )
    }

    @Test
    fun reportsAfterTheLastLanguageCandidate() {
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(
                engine = DeviceSpeechEngine.SystemService,
                retriedWithSystemService = true,
                languageIndex = 1
            )
        )
    }

    @Test
    fun neverRestartsListeningAfterStopWasRequested() {
        val errors = listOf(
            null,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NO_MATCH
        )
        errors.forEach { code ->
            assertEquals(
                "error=$code",
                DeviceSpeechFailureDecision.Report,
                decide(errorCode = code, stopRequested = true)
            )
        }
    }

    @Test
    fun reportsOnceSpeechWasHeard() {
        assertEquals(DeviceSpeechFailureDecision.Report, decide(heardSpeech = true))
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(errorCode = SpeechRecognizer.ERROR_CLIENT, heardSpeech = true)
        )
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(errorCode = null, heardSpeech = true)
        )
    }

    @Test
    fun recoverableErrorRecreatesTheRecognizerOnce() {
        val serviceOnly = environment.copy(allowsNetworkInput = false)
        assertEquals(
            DeviceSpeechFailureDecision.Recreate,
            decide(
                environment = serviceOnly,
                errorCode = SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            )
        )
        assertEquals(DeviceSpeechFailureDecision.Recreate, decide(errorCode = null))
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(errorCode = SpeechRecognizer.ERROR_RECOGNIZER_BUSY, recreated = true)
        )
    }

    @Test
    fun noSpeechIsReported() {
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(errorCode = SpeechRecognizer.ERROR_NO_MATCH)
        )
        assertEquals(
            DeviceSpeechFailureDecision.Report,
            decide(errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        )
    }
}
