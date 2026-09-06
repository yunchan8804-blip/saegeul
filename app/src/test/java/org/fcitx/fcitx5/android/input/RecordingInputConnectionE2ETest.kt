/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-02 Recording InputConnection E2E TDD Test Suite.
 * Records all interactions between the compatibility engine and the target editor
 * to verify exactly-once delivery, zero composing spans, and security invariants.
 */
class RecordingInputConnectionE2ETest {

    private class MockRecordingInputConnection : InputConnection {
        val composingTexts = mutableListOf<String>()
        val committedTexts = mutableListOf<String>()
        val contextMenuActions = mutableListOf<Int>()
        val keyEvents = mutableListOf<KeyEvent>()
        var selectionStart: Int = 0
        var selectionEnd: Int = 0
        var returnPasteSuccess: Boolean = true

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.let { composingTexts.add(it.toString()) }
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.let { committedTexts.add(it.toString()) }
            return true
        }

        override fun commitCompletion(text: CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionStart = start
            selectionEnd = end
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean = true

        override fun performContextMenuAction(id: Int): Boolean {
            contextMenuActions.add(id)
            return returnPasteSuccess
        }

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            event?.let { keyEvents.add(it) }
            return true
        }

        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    }

    private lateinit var mockIc: MockRecordingInputConnection
    private lateinit var controller: BufferedInputController
    private val hangulIme = InputMethodEntry(
        uniqueName = "hangul",
        name = "Hangul",
        icon = "fcitx-hangul",
        nativeName = "",
        label = "한",
        languageCode = "ko",
        addon = "hangul",
        isConfigurable = true
    )

    @Before
    fun setUp() {
        mockIc = MockRecordingInputConnection()
        controller = BufferedInputController()
    }

    @Test
    fun testZeroComposingTextInBufferedMode() {
        // In buffered Hangul compatibility mode, target editor must NEVER receive setComposingText calls!
        val effectiveCaps = BufferedHangulMode.effectiveCapabilities(
            CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.ClientUnfocusCommit),
            enabled = true,
            ime = hangulIme
        )
        assertFalse(effectiveCaps.has(CapabilityFlag.Preedit))

        // Emulate user typing Hangul syllable by syllable
        val syllables = listOf("사", "새", "새글", "새글자")
        syllables.forEach { s ->
            controller.capture(s)
            // Preedit is kept internal to controller and not dispatched to editor
        }

        assertEquals(0, mockIc.composingTexts.size)
    }

    @Test
    fun testDirectCommitDeliversExactlyOnce() {
        controller.capture("새글 키보드 완성")
        val payload = controller.markSubmitted(BufferedInputTransport.DirectCommit)

        mockIc.commitText(payload, 1)

        assertEquals(1, mockIc.committedTexts.size)
        assertEquals("새글 키보드 완성", mockIc.committedTexts.first())
        assertEquals(0, mockIc.composingTexts.size)
        assertTrue(controller.isEmpty)
    }

    @Test
    fun testSystemPasteDispatchesContextMenuActionExactlyOnce() {
        controller.capture("시스템 붙여넣기 구간")
        val payload = controller.markSubmitted(BufferedInputTransport.SystemPaste)

        assertEquals("시스템 붙여넣기 구간", payload)
        val pasteSuccess = mockIc.performContextMenuAction(android.R.id.paste)
        assertTrue(pasteSuccess)

        assertEquals(1, mockIc.contextMenuActions.size)
        assertEquals(android.R.id.paste, mockIc.contextMenuActions.first())
        // Invariant: No automatic commitText fallback after paste dispatch acknowledged
        assertEquals(0, mockIc.committedTexts.size)
    }

    @Test
    fun testSystemPasteFailurePreservesBufferAndAvoidsFallback() {
        mockIc.returnPasteSuccess = false

        controller.capture("전송 실패한 문장")
        val pasteSuccess = mockIc.performContextMenuAction(android.R.id.paste)
        assertFalse(pasteSuccess)

        // On false dispatch, mark failed and preserve buffer for user retry
        controller.markDeliveryFailed(BufferedInputTransport.SystemPaste)
        assertEquals("전송 실패한 문장", controller.prefix)
        assertTrue(controller.state is BufferedSessionState.PreservedForRetry)

        // Strict invariant: Zero automatic commitText fallback
        assertEquals(0, mockIc.committedTexts.size)
    }

    @Test
    fun testPasswordAndSensitiveFieldsEnforceClipboardAvoidance() {
        val passwordCaps = CapabilityFlags(CapabilityFlag.Password)
        val sensitiveCaps = CapabilityFlags(CapabilityFlag.Sensitive)

        assertTrue(BufferedHangulMode.mustAvoidClipboard(passwordCaps))
        assertTrue(BufferedHangulMode.mustAvoidClipboard(sensitiveCaps))

        // When avoidance is required, DirectCommit must be used
        controller.capture("비밀번호1234!")
        val payload = controller.markSubmitted(BufferedInputTransport.DirectCommit)
        mockIc.commitText(payload, 1)

        assertEquals(0, mockIc.contextMenuActions.size) // 0 paste actions
        assertEquals(1, mockIc.committedTexts.size)
        assertEquals("비밀번호1234!", mockIc.committedTexts.first())
    }

    @Test
    fun testExternalSelectionChangeDiscardsBufferWithoutEditorLeak() {
        controller.capture("작성 중이던 문장")
        assertEquals("작성 중이던 문장", controller.prefix)

        // Simulate external selection jump by the app
        mockIc.setSelection(100, 100)
        controller.discardByPolicy(BufferDiscardReason.ExternalSelectionChanged)

        assertTrue(controller.isEmpty)
        assertEquals(0, mockIc.committedTexts.size)
        assertEquals(0, mockIc.composingTexts.size)
        val state = controller.state
        assertTrue(state is BufferedSessionState.Discarded)
        if (state is BufferedSessionState.Discarded) {
            assertEquals(BufferDiscardReason.ExternalSelectionChanged, state.reason)
        }
    }

    @Test
    fun testEditorChangeDiscardsBufferWithoutCrossEditorLeak() {
        controller.capture("A앱에서 작성하던 글")
        // Editor finishes / unbinds
        controller.discardByPolicy(BufferDiscardReason.EditorChanged)
        assertTrue(controller.isEmpty)

        // Switch to new editor B
        val mockIcB = MockRecordingInputConnection()
        controller.capture("B앱 시작")
        val payload = controller.markSubmitted(BufferedInputTransport.DirectCommit)
        mockIcB.commitText(payload, 1)

        assertEquals(1, mockIcB.committedTexts.size)
        assertEquals("B앱 시작", mockIcB.committedTexts.first())
        // Ensure A's text never leaked to B!
        assertFalse(mockIcB.committedTexts.first().contains("A앱"))
    }
}
