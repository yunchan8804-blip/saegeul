/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OnDeviceContextCompletionGateTest {

    @Test
    fun `publish rejects text selection and session changes`() {
        val initial = snapshot()

        assertPublishRejected(initial, initial.copy(text = "회의 준비 변경"))
        assertPublishRejected(initial, initial.copy(selectionStart = 0))
        assertPublishRejected(initial, initial.copy(selectionEnd = 3))
        assertPublishRejected(initial, initial.copy(editorSessionId = 2))
    }

    @Test
    fun `cancel rejects a late response and keeps the slot until native stops`() {
        val gate = OnDeviceContextCompletionGate()
        val initial = snapshot()
        val ticket = requireNotNull(gate.begin(initial))

        assertTrue(gate.invalidate(ticket))
        assertNull(gate.begin(initial))
        assertFalse(gate.publish(ticket, initial, "회의를 준비할게요"))
        assertTrue(gate.nativeStopped(ticket))
        assertNotNull(gate.begin(initial))
    }

    @Test
    fun `apply waits for native termination and consumes exactly once`() {
        val gate = OnDeviceContextCompletionGate()
        val initial = snapshot()
        val ticket = requireNotNull(gate.begin(initial))

        assertTrue(gate.publish(ticket, initial, "회의를 준비할게요"))
        assertNull(gate.takeForApply(ticket, initial))
        assertTrue(gate.nativeStopped(ticket))
        assertEquals("회의를 준비할게요", gate.takeForApply(ticket, initial))
        assertNull(gate.takeForApply(ticket, initial))
        assertNotNull(gate.begin(initial))
    }

    @Test
    fun `apply rejects session text cursor and selection changes after native termination`() {
        val initial = snapshot()

        listOf(
            initial.copy(editorSessionId = 2),
            initial.copy(text = "회의 준비 변경"),
            initial.copy(selectionStart = 3, selectionEnd = 3),
            initial.copy(selectionEnd = 3)
        ).forEach { current ->
            val gate = OnDeviceContextCompletionGate()
            val ticket = requireNotNull(gate.begin(initial))

            assertTrue(gate.publish(ticket, initial, "회의를 준비할게요"))
            assertTrue(gate.nativeStopped(ticket))
            assertNull(gate.takeForApply(ticket, current))
            assertNotNull(gate.begin(initial))
        }
    }

    @Test
    fun `stale ticket cannot cancel end or publish into a newer run`() {
        val gate = OnDeviceContextCompletionGate()
        val initial = snapshot()
        val first = requireNotNull(gate.begin(initial))

        assertTrue(gate.invalidate(first))
        assertTrue(gate.nativeStopped(first))
        val second = requireNotNull(gate.begin(initial))

        assertFalse(gate.invalidate(first))
        assertFalse(gate.nativeStopped(first))
        assertFalse(gate.publish(first, initial, "오래된 응답"))
        assertTrue(gate.publish(second, initial, "새 응답"))
        assertTrue(gate.nativeStopped(second))
        assertEquals("새 응답", gate.takeForApply(second, initial))
    }

    @Test
    fun `invalid snapshots and responses are rejected`() {
        val gate = OnDeviceContextCompletionGate()
        val text = "회의"

        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, text, -1, 0)))
        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, text, 2, 1)))
        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, text, 0, 3)))
        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, "x".repeat(2049), 0, 0)))
        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, "", 0, 0)))
        assertNull(gate.begin(OnDeviceContextCompletionGate.Snapshot(1, " \t\n", 0, 0)))

        val initial = snapshot()
        val blankTicket = requireNotNull(gate.begin(initial))
        assertFalse(gate.publish(blankTicket, initial, "  \n"))
        assertTrue(gate.nativeStopped(blankTicket))

        val longTicket = requireNotNull(gate.begin(initial))
        assertFalse(gate.publish(longTicket, initial, "x".repeat(2049)))
        assertTrue(gate.nativeStopped(longTicket))
    }

    @Test
    fun `ready invalidation keeps the slot until native stops and releases it afterward`() {
        val initial = snapshot()
        val runningGate = OnDeviceContextCompletionGate()
        val runningTicket = requireNotNull(runningGate.begin(initial))

        assertTrue(runningGate.publish(runningTicket, initial, "회의를 준비할게요"))
        assertTrue(runningGate.invalidate(runningTicket))
        assertNull(runningGate.begin(initial))
        assertTrue(runningGate.nativeStopped(runningTicket))
        assertNotNull(runningGate.begin(initial))

        val stoppedGate = OnDeviceContextCompletionGate()
        val stoppedTicket = requireNotNull(stoppedGate.begin(initial))
        assertTrue(stoppedGate.publish(stoppedTicket, initial, "회의를 준비할게요"))
        assertTrue(stoppedGate.nativeStopped(stoppedTicket))
        assertTrue(stoppedGate.invalidate(stoppedTicket))
        assertNotNull(stoppedGate.begin(initial))
    }

    @Test
    fun `snapshot and gate string forms do not expose active payload`() {
        val secretContext = "비공개 문맥-회의-4731"
        val secretReply = "비공개 응답-완료-4731"
        val gate = OnDeviceContextCompletionGate()
        val initial = OnDeviceContextCompletionGate.Snapshot(9, secretContext, 2, 2)
        val ticket = requireNotNull(gate.begin(initial))

        assertTrue(gate.publish(ticket, initial, secretReply))
        assertFalse(initial.toString().contains(secretContext))
        assertFalse(gate.toString().contains(secretContext))
        assertFalse(gate.toString().contains(secretReply))
    }

    @Test
    fun `concurrent apply has one winner`() {
        val gate = OnDeviceContextCompletionGate()
        val initial = snapshot()
        val ticket = requireNotNull(gate.begin(initial))
        assertTrue(gate.publish(ticket, initial, "회의를 준비할게요"))
        assertTrue(gate.nativeStopped(ticket))

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String?> {
                barrier.await(1, TimeUnit.SECONDS)
                gate.takeForApply(ticket, initial)
            }
            val second = executor.submit<String?> {
                barrier.await(1, TimeUnit.SECONDS)
                gate.takeForApply(ticket, initial)
            }

            val results = listOf(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS))
            assertEquals(1, results.count { it == "회의를 준비할게요" })
            assertEquals(1, results.count { it == null })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertPublishRejected(
        initial: OnDeviceContextCompletionGate.Snapshot,
        current: OnDeviceContextCompletionGate.Snapshot
    ) {
        val gate = OnDeviceContextCompletionGate()
        val ticket = requireNotNull(gate.begin(initial))

        assertFalse(gate.publish(ticket, current, "회의를 준비할게요"))
        assertFalse(gate.publish(ticket, initial, "늦은 응답"))
        assertNull(gate.begin(initial))
        assertTrue(gate.nativeStopped(ticket))
        assertNotNull(gate.begin(initial))
    }

    private fun snapshot() = OnDeviceContextCompletionGate.Snapshot(
        editorSessionId = 1,
        text = "회의 준비",
        selectionStart = 2,
        selectionEnd = 2
    )
}
