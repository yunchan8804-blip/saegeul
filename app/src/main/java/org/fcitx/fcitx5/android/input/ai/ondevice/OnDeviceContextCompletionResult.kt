/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

data class OnDeviceContextCompletionResult(
    val suffix: String,
    val firstTextMs: Long?,
    val elapsedMs: Long,
    val nativeStopped: Boolean
) {
    override fun toString(): String =
        "OnDeviceContextCompletionResult(suffixLength=${suffix.length}, firstTextMs=$firstTextMs, " +
            "elapsedMs=$elapsedMs, nativeStopped=$nativeStopped)"
}
