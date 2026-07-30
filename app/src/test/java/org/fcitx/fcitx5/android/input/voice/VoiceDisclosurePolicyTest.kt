/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDisclosurePolicyTest {
    @Test
    fun onlyCurrentExplicitAcceptanceIsValid() {
        assertFalse(VoiceDisclosurePolicy.isCurrent(null))
        assertFalse(VoiceDisclosurePolicy.isCurrent(""))
        assertFalse(VoiceDisclosurePolicy.isCurrent("0"))
        assertFalse(VoiceDisclosurePolicy.isCurrent("2"))
        assertTrue(
            VoiceDisclosurePolicy.isCurrent(
                VoiceDisclosurePolicy.CURRENT_VERSION.toString()
            )
        )
    }

    @Test
    fun microphoneAndMeetingUseSeparateConsentMarkers() {
        assertTrue(
            VoiceDisclosureKind.Microphone.markerName !=
                VoiceDisclosureKind.MeetingAudioFile.markerName
        )
    }
}
