/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer

enum class DeviceSpeechEngine {
    /** Recognizer that runs inside the device. Audio never leaves it. */
    OnDevice,

    /** Recognition service the device has chosen. It may reach the network, so it is opt-in. */
    SystemService,
    Unavailable
}

/** What this device and the current editor allow right now, before any recognizer exists. */
data class DeviceSpeechEnvironment(
    val sdkInt: Int,
    val onDeviceAvailable: Boolean,
    val serviceAvailable: Boolean,
    val allowsNetworkInput: Boolean
) {
    val engine: DeviceSpeechEngine
        get() = DeviceSpeechEngineSelector.select(
            sdkInt = sdkInt,
            onDeviceAvailable = onDeviceAvailable,
            serviceAvailable = serviceAvailable,
            allowsNetworkInput = allowsNetworkInput
        )
}

object DeviceSpeechEngineSelector {
    const val ON_DEVICE_MIN_SDK = 31
    const val ON_DEVICE_AVAILABILITY_CHECK_MIN_SDK = 33

    /**
     * On-device recognition is allowed even in offline mode because the voice stays on the device.
     * The system service is used only when network input is allowed.
     */
    fun select(
        sdkInt: Int,
        onDeviceAvailable: Boolean,
        serviceAvailable: Boolean,
        allowsNetworkInput: Boolean
    ): DeviceSpeechEngine = when {
        sdkInt >= ON_DEVICE_MIN_SDK && onDeviceAvailable -> DeviceSpeechEngine.OnDevice
        serviceAvailable && allowsNetworkInput -> DeviceSpeechEngine.SystemService
        else -> DeviceSpeechEngine.Unavailable
    }
}

/**
 * Android 12 offers no availability query for on-device recognition, so the first start is the
 * probe: a language or connection failure before any speech was recognized moves to the system
 * service once, when the environment allows that service. From Android 13 the query only says an
 * on-device recognizer exists, not that it has the requested language, so a language failure
 * still moves to the system service once.
 */
object DeviceSpeechFallbackPolicy {
    private val languageErrors = setOf(
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
    )
    private val startupErrors = languageErrors + setOf(
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED
    )

    fun shouldRetryWithSystemService(
        engine: DeviceSpeechEngine,
        environment: DeviceSpeechEnvironment,
        errorCode: Int,
        heardSpeech: Boolean,
        alreadyRetried: Boolean
    ): Boolean = engine == DeviceSpeechEngine.OnDevice &&
        !alreadyRetried &&
        !heardSpeech &&
        errorCode in (
            if (environment.sdkInt < DeviceSpeechEngineSelector.ON_DEVICE_AVAILABILITY_CHECK_MIN_SDK) {
                startupErrors
            } else {
                languageErrors
            }
        ) &&
        DeviceSpeechEngineSelector.select(
            sdkInt = environment.sdkInt,
            onDeviceAvailable = false,
            serviceAvailable = environment.serviceAvailable,
            allowsNetworkInput = environment.allowsNetworkInput
        ) == DeviceSpeechEngine.SystemService
}

/**
 * A language the device has no speech pack for fails before any speech. The next candidate
 * language is tried on the same engine before the engine itself is given up.
 */
object DeviceSpeechLanguageFallbackPolicy {
    fun nextLanguageIndex(
        errorCode: Int,
        heardSpeech: Boolean,
        currentIndex: Int,
        candidateCount: Int
    ): Int? = (currentIndex + 1).takeIf {
        DeviceSpeechErrorKind.of(errorCode) == DeviceSpeechErrorKind.Language &&
            !heardSpeech &&
            it < candidateCount
    }
}

/**
 * A recognizer that fails before any speech, or never reports back, is usually stuck on the
 * recognition service (still unbinding, busy or disconnected). A new recognizer gets one more try
 * per start. A null error code means the start timed out.
 */
object DeviceSpeechRecoveryPolicy {
    private val recoverableErrors = setOf(
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
    )

    fun shouldRecreate(
        errorCode: Int?,
        heardSpeech: Boolean,
        alreadyRecreated: Boolean
    ): Boolean = !heardSpeech &&
        !alreadyRecreated &&
        (errorCode == null || errorCode in recoverableErrors)
}

sealed interface DeviceSpeechFailureDecision {
    /** Listen again on the same engine with the language candidate at [index]. */
    data class NextLanguage(val index: Int) : DeviceSpeechFailureDecision

    /** Listen again on the system service with the pinned language. */
    data object SystemService : DeviceSpeechFailureDecision

    /** Listen again on the same engine with a new recognizer. */
    data object Recreate : DeviceSpeechFailureDecision

    /** Give up and tell the listener. */
    data object Report : DeviceSpeechFailureDecision
}

/**
 * What to do after a recognizer failed. Listening never restarts once stop was requested or speech
 * was heard. The system service comes before another language so that a pinned language is tried
 * on the service before the device language replaces it. A null error code means the start timed
 * out.
 */
object DeviceSpeechFailurePolicy {
    fun decide(
        engine: DeviceSpeechEngine,
        environment: DeviceSpeechEnvironment,
        errorCode: Int?,
        heardSpeech: Boolean,
        stopRequested: Boolean,
        languageIndex: Int,
        candidateCount: Int,
        retriedWithSystemService: Boolean,
        recreated: Boolean
    ): DeviceSpeechFailureDecision {
        if (stopRequested) return DeviceSpeechFailureDecision.Report
        if (errorCode != null) {
            if (DeviceSpeechFallbackPolicy.shouldRetryWithSystemService(
                    engine = engine,
                    environment = environment,
                    errorCode = errorCode,
                    heardSpeech = heardSpeech,
                    alreadyRetried = retriedWithSystemService
                )
            ) {
                return DeviceSpeechFailureDecision.SystemService
            }
            val nextLanguageIndex = DeviceSpeechLanguageFallbackPolicy.nextLanguageIndex(
                errorCode = errorCode,
                heardSpeech = heardSpeech,
                currentIndex = languageIndex,
                candidateCount = candidateCount
            )
            if (nextLanguageIndex != null) {
                return DeviceSpeechFailureDecision.NextLanguage(nextLanguageIndex)
            }
        }
        if (DeviceSpeechRecoveryPolicy.shouldRecreate(errorCode, heardSpeech, recreated)) {
            return DeviceSpeechFailureDecision.Recreate
        }
        return DeviceSpeechFailureDecision.Report
    }
}
