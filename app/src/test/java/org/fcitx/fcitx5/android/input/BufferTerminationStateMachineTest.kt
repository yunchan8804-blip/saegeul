/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD RED test suite for P0-03 Silent Data Loss Elimination.
 * Tests BufferTerminationResult, BufferDiscardReason, user explicit retry/copy/cancel,
 * and state transition listeners.
 */
class BufferTerminationStateMachineTest {

    private lateinit var controller: BufferedInputController

    @Before
    fun setUp() {
        controller = BufferedInputController()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(BufferedSessionState.Idle, controller.state)
        assertTrue(controller.isEmpty)
        assertEquals("", controller.prefix)
    }

    @Test
    fun testCaptureTransitionsToComposing() {
        controller.capture("안녕하세요")
        val state = controller.state
        assertTrue("Expected Composing state but got $state", state is BufferedSessionState.Composing)
        if (state is BufferedSessionState.Composing) {
            assertEquals("안녕하세요", state.committedPrefix)
        }
    }

    @Test
    fun testSuccessfulDeliveryTransitionsToSubmitted() {
        var notifiedEvent: BufferTerminationEvent? = null
        controller.setTerminationListener { event -> notifiedEvent = event }

        controller.capture("새글 키보드")
        val submittedText = controller.markSubmitted(BufferedInputTransport.DirectCommit)

        assertEquals("새글 키보드", submittedText)
        assertTrue(controller.isEmpty)
        val state = controller.state
        assertTrue("Expected Submitted state but got $state", state is BufferedSessionState.Submitted)
        if (state is BufferedSessionState.Submitted) {
            assertEquals("새글 키보드", state.submittedText)
            assertEquals(BufferedInputTransport.DirectCommit, state.transport)
        }

        assertNotNull(notifiedEvent)
        assertEquals(BufferTerminationResult.Submitted, notifiedEvent?.result)
        assertEquals(6, notifiedEvent?.characterCount)
        assertNull(notifiedEvent?.reason)
    }

    @Test
    fun testFailedDeliveryPreservesBufferForRetry() {
        var notifiedEvent: BufferTerminationEvent? = null
        controller.setTerminationListener { event -> notifiedEvent = event }

        controller.capture("전송 실패 테스트")
        controller.markDeliveryFailed(BufferedInputTransport.SystemPaste, currentPreedit = "중")

        // Buffer must be preserved (prefix + preedit merged) and not lost!
        assertEquals("전송 실패 테스트중", controller.prefix)
        assertFalse(controller.isEmpty)

        val state = controller.state
        assertTrue("Expected PreservedForRetry state but got $state", state is BufferedSessionState.PreservedForRetry)
        if (state is BufferedSessionState.PreservedForRetry) {
            assertEquals("전송 실패 테스트중", state.preservedText)
            assertEquals(BufferedInputTransport.SystemPaste, state.failedTransport)
        }

        assertNotNull(notifiedEvent)
        assertEquals(BufferTerminationResult.PreservedForRetry, notifiedEvent?.result)
        assertEquals(10, notifiedEvent?.characterCount)
    }

    @Test
    fun testExplicitUserRetryRetrievesPreservedBuffer() {
        controller.capture("재시도 문장")
        controller.markDeliveryFailed(BufferedInputTransport.CtrlV)

        val retryPayload = controller.prepareRetry()
        assertEquals("재시도 문장", retryPayload)

        // After successful retry submission
        controller.markSubmitted(BufferedInputTransport.DirectCommit)
        assertTrue(controller.isEmpty)
        assertTrue(controller.state is BufferedSessionState.Submitted)
    }

    @Test
    fun testExplicitUserCancelClearsBufferSafely() {
        var notifiedEvent: BufferTerminationEvent? = null
        controller.setTerminationListener { event -> notifiedEvent = event }

        controller.capture("취소할 내용")
        val cancelledText = controller.cancel()

        assertEquals("취소할 내용", cancelledText)
        assertTrue(controller.isEmpty)
        assertEquals(BufferedSessionState.UserCancelled, controller.state)

        assertNotNull(notifiedEvent)
        assertEquals(BufferTerminationResult.UserCancelled, notifiedEvent?.result)
        assertEquals(6, notifiedEvent?.characterCount)
    }

    @Test
    fun testPolicyDiscardForExternalSelectionChange() {
        var notifiedEvent: BufferTerminationEvent? = null
        controller.setTerminationListener { event -> notifiedEvent = event }

        controller.capture("선택영역 변경")
        controller.discardByPolicy(BufferDiscardReason.ExternalSelectionChanged)

        assertTrue(controller.isEmpty)
        val state = controller.state
        assertTrue("Expected Discarded state but got $state", state is BufferedSessionState.Discarded)
        if (state is BufferedSessionState.Discarded) {
            assertEquals(BufferDiscardReason.ExternalSelectionChanged, state.reason)
        }

        assertNotNull(notifiedEvent)
        assertEquals(BufferTerminationResult.DiscardedByPolicy, notifiedEvent?.result)
        assertEquals(BufferDiscardReason.ExternalSelectionChanged, notifiedEvent?.reason)
        assertEquals(7, notifiedEvent?.characterCount)
    }

    @Test
    fun testPolicyDiscardForEditorChangePreventsCrossEditorLeak() {
        controller.capture("비밀 대화 내용")
        controller.discardByPolicy(BufferDiscardReason.EditorChanged)

        assertTrue(controller.isEmpty)
        assertEquals("", controller.prefix)
        val state = controller.state
        assertTrue(state is BufferedSessionState.Discarded)
        if (state is BufferedSessionState.Discarded) {
            assertEquals(BufferDiscardReason.EditorChanged, state.reason)
        }
    }

    @Test
    fun testExplicitCopyExtraction() {
        controller.capture("클립보드로 복사할 문장")
        val copyText = controller.extractForCopy()
        assertEquals("클립보드로 복사할 문장", copyText)
        // Extracting for copy does not destroy the active buffer
        assertEquals("클립보드로 복사할 문장", controller.prefix)
    }
}
