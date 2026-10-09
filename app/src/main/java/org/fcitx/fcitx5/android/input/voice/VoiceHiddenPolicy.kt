/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

internal enum class VoiceHiddenEngine {
    Segment,
    Realtime,
    Device
}

internal enum class VoiceHiddenPhase {
    Idle,
    Recording,
    Sending
}

internal enum class VoiceHiddenAction {
    CancelRecording,
    ReleaseDevice,
    Keep
}

internal object VoiceHiddenPolicy {
    /**
     * Decides what a hidden keyboard does to a dictation in progress. A cloud recording that has
     * not been sent is discarded whole; one the user already stopped keeps its request going. The
     * on-device recognizer is always released.
     */
    fun decide(engine: VoiceHiddenEngine?, phase: VoiceHiddenPhase): VoiceHiddenAction =
        when (engine) {
            null -> VoiceHiddenAction.Keep
            VoiceHiddenEngine.Device -> VoiceHiddenAction.ReleaseDevice
            VoiceHiddenEngine.Segment,
            VoiceHiddenEngine.Realtime ->
                if (phase == VoiceHiddenPhase.Recording) {
                    VoiceHiddenAction.CancelRecording
                } else {
                    VoiceHiddenAction.Keep
                }
        }
}
