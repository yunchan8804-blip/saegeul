/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLearningObserverTest {

    private class Fixture(
        var credentialProtected: Boolean = true,
        var inspection: Boolean = true,
        before: String = "안녕하세요",
        after: String = "",
        hasConnection: Boolean = true
    ) {
        val log = mutableListOf<String>()
        private val connection: InputConnection? =
            if (hasConnection) fakeInputConnection(log, before, after) else null

        val observer = EditorLearningObserver(
            EditorLearningObserver.Host(
                allowsCredentialProtectedFeatures = { credentialProtected },
                allowsTextInspection = { inspection },
                inputConnection = { connection },
                packageName = { "com.example.chat" },
                currentWordBeforeCursor = {
                    log += "word"
                    "하세요"
                },
                recordCorrectionBackspace = { log += "correction($it)" },
                onEditorContinuityLost = { packageName, removedText, inspectionAllowed ->
                    log += "continuity($packageName,$removedText,$inspectionAllowed)"
                },
                flushPending = { log += "flush" },
                observeCommittedText = { log += "committed($it)" },
                onEditorSessionStarted = { packageName, fieldId, restarting ->
                    log += "session($packageName,$fieldId,$restarting)"
                }
            )
        )
    }

    @Test
    fun `deleted text is read before the loss is reported`() {
        val fixture = Fixture()

        fixture.observer.onDeleting(2)

        assertEquals(
            listOf("getTextBeforeCursor(2,0)", "continuity(com.example.chat,세요,true)"),
            fixture.log
        )
    }

    @Test
    fun `nothing is read when text inspection is not allowed`() {
        val fixture = Fixture(inspection = false)

        fixture.observer.onDeleting(1)

        assertEquals(listOf("continuity(com.example.chat,null,false)"), fixture.log)
    }

    @Test
    fun `a deletion with nothing before the cursor reports without reading`() {
        val fixture = Fixture()

        fixture.observer.onDeleting(0)

        assertEquals(listOf("continuity(com.example.chat,null,true)"), fixture.log)
    }

    @Test
    fun `a missing connection reports the loss without removed text`() {
        val fixture = Fixture(hasConnection = false)

        fixture.observer.onDeleting(1)

        assertEquals(listOf("continuity(com.example.chat,null,true)"), fixture.log)
    }

    @Test
    fun `direct boot reports nothing to typing dna`() {
        val fixture = Fixture(credentialProtected = false)

        fixture.observer.onDeleting(1)
        fixture.observer.onBackspaceKey()
        fixture.observer.flushPending()
        fixture.observer.observeCommittedText("글")
        fixture.observer.onExternalCursorMove(0, 0)
        fixture.observer.onEditorSessionStarted("com.example.chat", 7, 0, 0, true)

        assertTrue(fixture.log.none { it.startsWith("continuity") || it.startsWith("session") })
        assertTrue(fixture.log.none { it == "flush" || it.startsWith("committed") })
    }

    @Test
    fun `a backspace key reports the loss and then records the word it cuts into`() {
        val fixture = Fixture()

        fixture.observer.onBackspaceKey()

        assertEquals(
            listOf(
                "getTextBeforeCursor(1,0)",
                "continuity(com.example.chat,요,true)",
                "word",
                "correction(하세요)"
            ),
            fixture.log
        )
    }

    @Test
    fun `a backspace key in a private editor reports the loss but records no word`() {
        val fixture = Fixture(inspection = false)

        fixture.observer.onBackspaceKey()

        assertEquals(listOf("continuity(com.example.chat,null,false)"), fixture.log)
    }

    @Test
    fun `a forwarded backspace records the word before it reports the loss`() {
        val fixture = Fixture()

        fixture.observer.onForwardedDeleteDown(backspace = true)

        assertEquals(
            listOf(
                "word",
                "correction(하세요)",
                "getTextBeforeCursor(1,0)",
                "continuity(com.example.chat,요,true)"
            ),
            fixture.log
        )
    }

    @Test
    fun `a forwarded forward delete reports the loss without reading or correction`() {
        val fixture = Fixture()

        fixture.observer.onForwardedDeleteDown(backspace = false)

        assertEquals(listOf("continuity(com.example.chat,null,true)"), fixture.log)
    }

    @Test
    fun `a buffered backspace records the word only while text inspection is allowed`() {
        val allowed = Fixture()
        allowed.observer.onBackspace()
        assertEquals(listOf("word", "correction(하세요)"), allowed.log)

        val private = Fixture(inspection = false)
        private.observer.onBackspace()
        assertTrue(private.log.isEmpty())
    }

    @Test
    fun `an emptied editor flushes the sent sentence instead of breaking continuity`() {
        val fixture = Fixture(before = "", after = "")

        fixture.observer.onExternalCursorMove(0, 0)

        assertEquals(listOf("getTextBeforeCursor(1,0)", "getTextAfterCursor(1,0)", "flush"), fixture.log)
    }

    @Test
    fun `any other external cursor move only breaks continuity`() {
        val moved = Fixture()
        moved.observer.onExternalCursorMove(3, 3)
        assertEquals(listOf("continuity(com.example.chat,null,true)"), moved.log)

        val notEmpty = Fixture(before = "남은 글", after = "")
        notEmpty.observer.onExternalCursorMove(0, 0)
        assertEquals(
            listOf("getTextBeforeCursor(1,0)", "getTextAfterCursor(1,0)", "continuity(com.example.chat,null,true)"),
            notEmpty.log
        )
    }

    @Test
    fun `a private editor never reads emptiness`() {
        val fixture = Fixture(inspection = false, before = "", after = "")

        fixture.observer.onExternalCursorMove(0, 0)

        assertEquals(listOf("continuity(com.example.chat,null,false)"), fixture.log)
    }

    @Test
    fun `a restarted field that came back empty flushes before the new session starts`() {
        val fixture = Fixture(before = "", after = "")

        fixture.observer.onEditorSessionStarted("com.example.chat", 7, 0, 0, restarting = true)

        assertEquals(
            listOf(
                "getTextBeforeCursor(1,0)",
                "getTextAfterCursor(1,0)",
                "flush",
                "session(com.example.chat,7,true)"
            ),
            fixture.log
        )
    }

    @Test
    fun `a fresh session or a private editor starts without flushing`() {
        val fresh = Fixture(before = "", after = "")
        fresh.observer.onEditorSessionStarted("com.example.chat", 7, 0, 0, restarting = false)
        assertEquals(listOf("session(com.example.chat,7,false)"), fresh.log)

        val private = Fixture(inspection = false, before = "", after = "")
        private.observer.onEditorSessionStarted("com.example.chat", 7, 0, 0, restarting = true)
        assertEquals(listOf("session(com.example.chat,7,true)"), private.log)
    }

    @Test
    fun `flush and committed text reach personal learning when credential storage is open`() {
        val fixture = Fixture()

        fixture.observer.flushPending()
        fixture.observer.observeCommittedText("글")

        assertEquals(listOf("flush", "committed(글)"), fixture.log)
    }
}
