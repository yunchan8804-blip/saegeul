/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.ActivityNotFoundException

/**
 * Product-safe classification for failures before the OAuth browser has opened.
 *
 * AppAuth uses [ActivityNotFoundException] while it prepares an authorization intent when it
 * cannot select a supported browser. Other failures can come from activity lifecycle or launch
 * state, so reporting all of them as a missing browser would give the wrong recovery advice.
 */
internal enum class AiOAuthStartFailure {
    BrowserUnavailable,
    UnableToStart;

    companion object {
        fun fromAuthorizationIntentError(error: Throwable): AiOAuthStartFailure =
            if (error is ActivityNotFoundException) BrowserUnavailable else UnableToStart
    }
}
