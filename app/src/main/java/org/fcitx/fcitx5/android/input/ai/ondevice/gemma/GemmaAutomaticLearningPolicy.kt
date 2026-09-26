/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

/**
 * Decisions for keeping "자동으로 배우기" (automatic material/graph generation) consistent with
 * whether the Gemma model is actually on disk. A cloud-restored `enabled=true` with no model present
 * (the model lives under `noBackupFilesDir` and is never restored) must not leave the toggle showing
 * on with nothing behind it - see [shouldForceDisableAutomaticLearning]. Conversely, finishing an
 * install should turn automatic learning on by default unless the user had explicitly turned it off
 * before - see [shouldAutoEnableAfterInstall].
 */
internal object GemmaAutomaticLearningPolicy {

    /** Whether app start should force `enabled` back to false because the model backing it is missing. */
    fun shouldForceDisableAutomaticLearning(modelInstalled: Boolean, currentlyEnabled: Boolean): Boolean =
        currentlyEnabled && !modelInstalled

    /** Whether a just-finished install should turn automatic learning on. */
    fun shouldAutoEnableAfterInstall(currentlyEnabled: Boolean, userOptedOut: Boolean): Boolean =
        !currentlyEnabled && !userOptedOut
}
