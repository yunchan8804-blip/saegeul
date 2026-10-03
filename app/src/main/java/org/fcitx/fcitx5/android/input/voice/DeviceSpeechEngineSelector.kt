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
