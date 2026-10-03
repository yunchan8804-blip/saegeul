/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import java.util.Locale

/** How the window explains a recognizer failure. */
enum class DeviceSpeechErrorKind {
    NoSpeech,
    Language,
    Busy,
    Network,
    Permission,
    Other;

    companion object {
        fun of(errorCode: Int): DeviceSpeechErrorKind = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NoSpeech
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Language
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> Busy
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Network
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Permission
            else -> Other
        }
    }
}

object DeviceSpeechLanguage {
    const val KOREAN = "ko-KR"
    const val ENGLISH = "en-US"

    /** Korean and English input methods pin their language; anything else follows the device. */
    fun tagFor(languageCode: String, deviceLocale: Locale): String =
        when (languageCode.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
            "ko" -> KOREAN
            "en" -> ENGLISH
            else -> deviceLocale.toLanguageTag()
        }
}

object DeviceSpeechLevel {
    private const val QUIET_DB = -2f
    private const val LOUD_DB = 10f

    /** Maps the recognizer's rms dB (about -2 to 10) to a 0..1 bar length. */
    fun fraction(rmsDb: Float): Float =
        ((rmsDb - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)
}
