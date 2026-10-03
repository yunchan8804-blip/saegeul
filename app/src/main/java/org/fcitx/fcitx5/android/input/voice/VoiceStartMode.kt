/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

/** How the dictation window was opened, which decides whether it starts listening by itself. */
enum class VoiceStartMode {
    Manual,
    Tap,
    PushToTalk
}

internal enum class VoiceAttachStart {
    ResumePermission,
    Begin,
    Wait
}

internal object VoiceAttachStartPolicy {
    /** A finished permission screen always wins, because it carries the editor it was asked for. */
    fun decide(startMode: VoiceStartMode, hasPermissionResume: Boolean): VoiceAttachStart = when {
        hasPermissionResume -> VoiceAttachStart.ResumePermission
        startMode == VoiceStartMode.Manual -> VoiceAttachStart.Wait
        else -> VoiceAttachStart.Begin
    }
}

internal enum class VoiceKeyGate {
    Start,
    ShowBlockedNotice,
    Ignore
}

internal object VoiceKeyGatePolicy {
    /**
     * The microphone key stays visible in every editor, so what a press does depends on the
     * editor. The internal prompt owns its own input and hears nothing; an editor that forbids
     * text inspection (password, sensitive, no personalized learning) gets a notice instead.
     */
    fun decide(internalPromptInputOwned: Boolean, allowsTextInspection: Boolean): VoiceKeyGate =
        when {
            internalPromptInputOwned -> VoiceKeyGate.Ignore
            !allowsTextInspection -> VoiceKeyGate.ShowBlockedNotice
            else -> VoiceKeyGate.Start
        }
}
