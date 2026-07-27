/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptEntryPolicyTest {
    @Test
    fun readyEntryOpensPromptExactlyOnce() {
        val policy = AiPromptEntryPolicy()

        assertTrue(policy.consumeShouldOpen(featureReady = true, editorTargetBound = true))
        assertFalse(policy.consumeShouldOpen(featureReady = true, editorTargetBound = true))
    }

    @Test
    fun blockedOrUnboundEntryDoesNotConsumeFutureReadyEntry() {
        val policy = AiPromptEntryPolicy()

        assertFalse(policy.consumeShouldOpen(featureReady = false, editorTargetBound = true))
        assertFalse(policy.consumeShouldOpen(featureReady = true, editorTargetBound = false))
        assertTrue(policy.consumeShouldOpen(featureReady = true, editorTargetBound = true))
    }
}
