/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAccumulationNotificationDecisionTest {

    @Test
    fun announcesDoneOnlyWhenAtLeastOneContextWasAddedThisRun() {
        assertTrue(GemmaAccumulationNotificationDecision.shouldAnnounceDone(1))
        assertTrue(GemmaAccumulationNotificationDecision.shouldAnnounceDone(16))
    }

    @Test
    fun doesNotAnnounceDoneWhenNothingWasAddedThisRun() {
        assertFalse(GemmaAccumulationNotificationDecision.shouldAnnounceDone(0))
    }
}
