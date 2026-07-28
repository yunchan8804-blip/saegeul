/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalPromptCaptureGateTest {
    @Test
    fun `prompt target rejects a same-metadata field from a later input session`() {
        val target = InternalPromptEditorTarget(
            packageName = "org.example.app",
            fieldId = 0,
            inputType = 1,
            selectionStart = 4,
            selectionEnd = 4,
            inputSessionEpoch = 11
        )

        assertTrue(target.matches("org.example.app", 0, 1, 4, 4, 11))
        assertFalse(target.matches("org.example.app", 0, 1, 4, 4, 12))
        assertFalse(target.matches("org.example.app", 0, 1, 5, 5, 11))
    }

    @Test
    fun `start barrier does not capture older engine callbacks`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.beginStarting())

        assertTrue(gate.isStarting)
        assertFalse(gate.ownsInput)
        assertTrue(gate.blocksNewInput)
        assertFalse(gate.isActive(token))
        assertTrue(gate.activateStart(token))
        assertFalse(gate.isStarting)
        assertTrue(gate.ownsInput)
        assertTrue(gate.isActive(token))
    }

    @Test
    fun `cancelled start marker cannot activate a later prompt`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.beginStarting())

        assertTrue(gate.cancelStart(token))
        assertFalse(gate.activateStart(token))
        assertTrue(gate.blocksNewInput)
        assertFalse(gate.ownsInput)
        assertTrue(gate.releaseCancelledStart(token))
        assertFalse(gate.blocksNewInput)
    }

    @Test
    fun `editor change quarantines a cancelled start until its original marker`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.beginStarting())

        assertTrue(gate.discardStart(token))
        assertFalse(gate.isStarting)
        assertTrue(gate.blocksNewInput)
        assertTrue(gate.ownsInput)
        assertFalse(gate.activateStart(token))
        assertFalse(gate.releaseDiscardedStart(token + 1))
        assertTrue(gate.ownsInput)

        assertTrue(gate.releaseDiscardedStart(token))
        assertFalse(gate.blocksNewInput)
        assertFalse(gate.ownsInput)
    }

    @Test
    fun `editor change upgrades a user cancelled start to a discard quarantine`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.beginStarting())

        assertTrue(gate.cancelStart(token))
        assertFalse(gate.ownsInput)
        assertTrue(gate.discardPendingStart())
        assertTrue(gate.ownsInput)
        assertTrue(gate.releaseDiscardedStart(token))
        assertFalse(gate.blocksNewInput)
    }

    @Test
    fun `engine restart can release a pending marker only after its collector is discarded`() {
        val gate = InternalPromptCaptureGate()
        requireNotNull(gate.beginStarting())

        gate.resetForEngineRestart()

        assertFalse(gate.blocksNewInput)
        assertFalse(gate.ownsInput)
    }

    @Test
    fun `closing prompt keeps late engine input owned until its matching barrier`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.begin())

        assertTrue(gate.isActive(token))
        assertTrue(gate.ownsInput)
        assertTrue(gate.beginDrain(token))

        assertFalse(gate.isActive(token))
        assertTrue(gate.isDraining)
        assertTrue(gate.isDraining(token))
        assertFalse(gate.isDraining(token + 1))
        assertTrue(gate.ownsInput)
        assertFalse(gate.releaseDrain(token + 1))
        assertTrue(gate.ownsInput)

        assertTrue(gate.releaseDrain(token))
        assertFalse(gate.ownsInput)
    }

    @Test
    fun `next prompt waits for the prior reset barrier`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.begin())

        assertTrue(gate.beginDrain(token))
        assertNull(gate.begin())
        assertTrue(gate.releaseDrain(token))

        val next = requireNotNull(gate.begin())
        assertTrue(next > token)
        assertFalse(gate.releaseDrain(token))
        assertTrue(gate.isActive(next))
    }

    @Test
    fun `a direct picker action cannot reuse a closed prompt token`() {
        val gate = InternalPromptCaptureGate()
        val token = requireNotNull(gate.begin())

        // A direct picker action is accepted only while the original capture is active.
        assertTrue(gate.isActive(token))
        assertTrue(gate.beginDrain(token))
        assertFalse(gate.isActive(token))
        assertTrue(gate.releaseDrain(token))
        assertFalse(gate.isActive(token))
    }
}
