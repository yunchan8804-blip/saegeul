/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeviceSpeechFormatTest {
    @Test
    fun errorCodesMapToTheMessageTheWindowShows() {
        assertEquals(
            DeviceSpeechErrorKind.NoSpeech,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_NO_MATCH)
        )
        assertEquals(
            DeviceSpeechErrorKind.NoSpeech,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        )
        assertEquals(
            DeviceSpeechErrorKind.Language,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        )
        assertEquals(
            DeviceSpeechErrorKind.Language,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        )
        assertEquals(
            DeviceSpeechErrorKind.Busy,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        )
        assertEquals(
            DeviceSpeechErrorKind.Network,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_NETWORK)
        )
        assertEquals(
            DeviceSpeechErrorKind.Network,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_SERVER)
        )
        assertEquals(
            DeviceSpeechErrorKind.Permission,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertEquals(
            DeviceSpeechErrorKind.Other,
            DeviceSpeechErrorKind.of(SpeechRecognizer.ERROR_AUDIO)
        )
        assertEquals(DeviceSpeechErrorKind.Other, DeviceSpeechErrorKind.of(-1))
    }

    @Test
    fun koreanAndEnglishInputMethodsPinTheirLanguage() {
        val device = Locale.forLanguageTag("ja-JP")
        assertEquals("ko-KR", DeviceSpeechLanguage.tagFor("ko", device))
        assertEquals("ko-KR", DeviceSpeechLanguage.tagFor("ko_KR", device))
        assertEquals("en-US", DeviceSpeechLanguage.tagFor("en", device))
        assertEquals("en-US", DeviceSpeechLanguage.tagFor("en-GB", device))
    }

    @Test
    fun otherLanguagesFollowTheDeviceLocale() {
        assertEquals(
            "ja-JP",
            DeviceSpeechLanguage.tagFor("zh", Locale.forLanguageTag("ja-JP"))
        )
        assertEquals("fr-FR", DeviceSpeechLanguage.tagFor("", Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun levelBarStaysWithinZeroAndOne() {
        assertEquals(0f, DeviceSpeechLevel.fraction(-2f), 0f)
        assertEquals(0f, DeviceSpeechLevel.fraction(-30f), 0f)
        assertEquals(1f, DeviceSpeechLevel.fraction(10f), 0f)
        assertEquals(1f, DeviceSpeechLevel.fraction(40f), 0f)
        assertEquals(0.5f, DeviceSpeechLevel.fraction(4f), 0.0001f)
    }
}
