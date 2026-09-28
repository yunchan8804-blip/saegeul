/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/** The longest before-cursor editor text that on-device completion and suggestions ever read. */
internal const val ON_DEVICE_CONTEXT_MAX_CHARS = 2048

/**
 * Hands out extracted-text monitor tokens. On-device context completion and automatic
 * suggestions share one instance so their monitors never reuse each other's token: tokens count
 * down from -1 and wrap back to -1 after [Int.MIN_VALUE].
 */
class ExtractedTextTokens {
    private var next = -1

    fun next(): Int {
        val token = next
        next = if (token == Int.MIN_VALUE) -1 else token - 1
        return token
    }
}
