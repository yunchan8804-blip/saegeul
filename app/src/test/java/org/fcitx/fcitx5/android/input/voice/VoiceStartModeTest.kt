/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAttachStartPolicyTest {
    @Test
    fun manualOpenWaitsForTheStartButton() {
        assertEquals(
            VoiceAttachStart.Wait,
            VoiceAttachStartPolicy.decide(VoiceStartMode.Manual, hasPermissionResume = false)
        )
    }

    @Test
    fun tapAndPushToTalkStartListeningAtOnce() {
        assertEquals(
            VoiceAttachStart.Begin,
            VoiceAttachStartPolicy.decide(VoiceStartMode.Tap, hasPermissionResume = false)
        )
        assertEquals(
            VoiceAttachStart.Begin,
            VoiceAttachStartPolicy.decide(VoiceStartMode.PushToTalk, hasPermissionResume = false)
        )
    }

    @Test
    fun aFinishedPermissionScreenAlwaysWins() {
        VoiceStartMode.entries.forEach { mode ->
            assertEquals(
                VoiceAttachStart.ResumePermission,
                VoiceAttachStartPolicy.decide(mode, hasPermissionResume = true)
            )
        }
    }
}

class VoiceKeyGatePolicyTest {
    @Test
    fun anOrdinaryEditorStartsDictation() {
        assertEquals(
            VoiceKeyGate.Start,
            VoiceKeyGatePolicy.decide(internalPromptInputOwned = false, allowsTextInspection = true)
        )
    }

    @Test
    fun anEditorThatForbidsTextInspectionGetsANoticeInsteadOfDictation() {
        assertEquals(
            VoiceKeyGate.ShowBlockedNotice,
            VoiceKeyGatePolicy.decide(internalPromptInputOwned = false, allowsTextInspection = false)
        )
    }

    @Test
    fun theInternalPromptOwningTheInputIsIgnoredWhateverTheEditorAllows() {
        listOf(true, false).forEach { allowsTextInspection ->
            assertEquals(
                VoiceKeyGate.Ignore,
                VoiceKeyGatePolicy.decide(
                    internalPromptInputOwned = true,
                    allowsTextInspection = allowsTextInspection
                )
            )
        }
    }
}
