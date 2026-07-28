/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalPromptDirectCommitQueueTest {
    @Test
    fun `Search waits for the last reserved picker marker exactly once`() {
        val queue = InternalPromptDirectCommitQueue()
        val first = requireNotNull(queue.reserve())
        val second = requireNotNull(queue.reserve())

        assertEquals(
            InternalPromptDirectCommitQueue.SubmissionRequest.Started,
            queue.requestSubmit()
        )
        assertTrue(queue.isSubmissionPending)
        assertEquals(InternalPromptDirectCommitQueue.Completion.Accepted, queue.complete(first))
        assertEquals(InternalPromptDirectCommitQueue.Completion.Accepted, queue.complete(second))
        assertEquals(InternalPromptDirectCommitQueue.Completion.SubmitReady, queue.reachSubmitFence())
        assertFalse(queue.isSubmissionPending)
        assertEquals(InternalPromptDirectCommitQueue.Completion.Ignored, queue.complete(second))
    }

    @Test
    fun `closing prompt consumes later direct actions instead of reserving editor fallback`() {
        val queue = InternalPromptDirectCommitQueue()
        val sequence = requireNotNull(queue.reserve())

        assertEquals(
            InternalPromptDirectCommitQueue.SubmissionRequest.Started,
            queue.requestSubmit()
        )
        assertNull(queue.reserve())
        assertEquals(InternalPromptDirectCommitQueue.Completion.Accepted, queue.complete(sequence))
        assertEquals(InternalPromptDirectCommitQueue.Completion.SubmitReady, queue.reachSubmitFence())
    }

    @Test
    fun `failed direct worker clears a pending submit for explicit retry`() {
        val queue = InternalPromptDirectCommitQueue()
        val sequence = requireNotNull(queue.reserve())

        assertEquals(
            InternalPromptDirectCommitQueue.SubmissionRequest.Started,
            queue.requestSubmit()
        )
        assertTrue(queue.abandon(sequence))
        assertFalse(queue.isSubmissionPending)
        assertFalse(queue.hasPending)
    }

    @Test
    fun `submit fence waits for a late direct marker without reversing prompt text`() {
        val session = InternalPromptCaptureSession(maxCharacters = 80)
        val queue = InternalPromptDirectCommitQueue()
        session.updatePreedit("가")
        val sequence = requireNotNull(queue.reserve())

        assertEquals(
            InternalPromptDirectCommitQueue.SubmissionRequest.Started,
            queue.requestSubmit()
        )
        session.commitPreedit() // mirrors non-zh finishComposing before the stream marker
        assertEquals(InternalPromptDirectCommitQueue.Completion.Accepted, queue.reachSubmitFence())
        assertEquals(InternalPromptDirectCommitQueue.Completion.SubmitReady, queue.complete(sequence))
        session.commit("🙂") // marker payload is appended only after the engine preedit

        assertEquals("가🙂", session.submission())
    }

    @Test
    fun `plain virtual key submits only after its generic fence arrives`() {
        val queue = InternalPromptDirectCommitQueue()

        assertEquals(
            InternalPromptDirectCommitQueue.SubmissionRequest.Started,
            queue.requestSubmit()
        )
        assertEquals(InternalPromptDirectCommitQueue.Completion.SubmitReady, queue.reachSubmitFence())
        assertFalse(queue.isSubmissionPending)
    }
}
