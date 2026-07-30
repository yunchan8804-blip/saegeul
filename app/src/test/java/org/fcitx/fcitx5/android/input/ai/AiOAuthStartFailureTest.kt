/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

class AiOAuthStartFailureTest {
    @Test
    fun `missing AppAuth browser remains a browser-specific failure`() {
        assertEquals(
            AiOAuthStartFailure.BrowserUnavailable,
            AiOAuthStartFailure.fromAuthorizationIntentError(ActivityNotFoundException())
        )
    }

    @Test
    fun `other OAuth start failures do not masquerade as a missing browser`() {
        assertEquals(
            AiOAuthStartFailure.UnableToStart,
            AiOAuthStartFailure.fromAuthorizationIntentError(
                IllegalStateException("authorization launcher state")
            )
        )
    }
}
