/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.context.KoreanParticleEditorTarget
import org.fcitx.fcitx5.android.input.context.KoreanParticleKind
import org.fcitx.fcitx5.android.input.context.KoreanParticleSnapshot
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggestion
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.typo.TypoRecoveryEditorTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDirectActionsTest {

    private class Fixture(
        var textInspection: Boolean = true,
        var networkInput: Boolean = true,
        var finishSucceeds: Boolean = true,
        before: String = "",
        cursor: Int = 0,
        selectionEnd: Int = cursor,
        hasConnection: Boolean = true
    ) {
        val log = mutableListOf<String>()
        val selection = CursorTracker().apply { resetTo(cursor, selectionEnd) }
        val info = EditorInfo().apply {
            packageName = "com.example.chat"
            fieldId = 7
            inputType = 1
        }
        private val connection: InputConnection? =
            if (hasConnection) fakeInputConnection(log, before) else null

        val actions = EditorDirectActions(
            EditorDirectActions.Host(
                allowsTextInspection = { textInspection },
                allowsNetworkInput = { networkInput },
                finishCompositionForDirectAction = {
                    log += "finish"
                    finishSucceeds
                },
                editorInfo = { info },
                inputConnection = { connection },
                selection = { selection },
                markEditorStateChanged = { log += "marked" },
                notifySnapshotInvalidated = { log += "invalidated" },
                postRefreshContextualCandidates = { log += "refreshed" },
                commitTextToEditor = { text, cursorOffset ->
                    log += "commit($text,$cursorOffset)"
                    true
                }
            )
        )
    }

    @Test
    fun `rich content needs the network gate and then a finished composition`() {
        val blocked = Fixture(networkInput = false)
        assertFalse(blocked.actions.prepareRichContentCommit())
        assertTrue(blocked.log.isEmpty())

        val open = Fixture()
        assertTrue(open.actions.prepareRichContentCommit())
        assertEquals(listOf("finish"), open.log)

        val failed = Fixture(finishSucceeds = false)
        assertFalse(failed.actions.prepareRichContentCommit())
    }

    @Test
    fun `on-device text commits need text inspection but not the network`() {
        val blocked = Fixture(textInspection = false)
        assertFalse(blocked.actions.prepareTextInspectionCommit())
        assertTrue(blocked.log.isEmpty())

        val offline = Fixture(networkInput = false)
        assertTrue(offline.actions.prepareTextInspectionCommit())
        assertEquals(listOf("finish"), offline.log)
    }

    @Test
    fun `erasing dictation removes exactly the dictated text`() {
        val fixture = Fixture(before = "오늘 날씨 좋다", cursor = 8)

        assertTrue(fixture.actions.eraseDictatedText("날씨 좋다"))

        assertEquals(
            listOf(
                "finish",
                "getTextBeforeCursor(5,0)",
                "marked",
                "deleteSurroundingText(5,0)",
                "invalidated",
                "refreshed"
            ),
            fixture.log
        )
        assertEquals(3, fixture.selection.latest.start)
    }

    @Test
    fun `erasing dictation refuses changed text, a selection or a blocked gate before touching anything`() {
        val changed = Fixture(before = "오늘 날씨 좋다", cursor = 8)
        assertFalse(changed.actions.eraseDictatedText("날씨 나쁘다"))
        assertEquals(listOf("finish", "getTextBeforeCursor(6,0)"), changed.log)

        val selected = Fixture(before = "오늘 날씨 좋다", cursor = 4, selectionEnd = 8)
        assertFalse(selected.actions.eraseDictatedText("날씨 좋다"))
        assertTrue(selected.log.isEmpty())

        val blocked = Fixture(textInspection = false, before = "날씨")
        assertFalse(blocked.actions.eraseDictatedText("날씨"))
        assertTrue(blocked.log.isEmpty())
    }

    @Test
    fun `a failed delete does not move the predicted cursor`() {
        val log = mutableListOf<String>()
        val selection = CursorTracker().apply { resetTo(8) }
        val actions = EditorDirectActions(
            EditorDirectActions.Host(
                allowsTextInspection = { true },
                allowsNetworkInput = { true },
                finishCompositionForDirectAction = { true },
                editorInfo = { EditorInfo() },
                inputConnection = { fakeInputConnection(log, "날씨 좋다", deleteSucceeds = false) },
                selection = { selection },
                markEditorStateChanged = {},
                notifySnapshotInvalidated = { log += "invalidated" },
                postRefreshContextualCandidates = { log += "refreshed" },
                commitTextToEditor = { _, _ -> true }
            )
        )

        assertFalse(actions.eraseDictatedText("날씨 좋다"))

        assertEquals(8, selection.latest.start)
        assertTrue(log.none { it == "invalidated" || it == "refreshed" })
    }

    @Test
    fun `particle capture and typo capture refuse a selection before finishing composition`() {
        val fixture = Fixture(before = "학교", cursor = 1, selectionEnd = 2)

        assertNull(fixture.actions.captureKoreanParticleSnapshot())
        assertNull(fixture.actions.captureTypoRecoverySnapshot())
        assertTrue(fixture.log.isEmpty())
    }

    @Test
    fun `dictionary capture needs text inspection and a finished composition`() {
        val blocked = Fixture(textInspection = false)
        assertNull(blocked.actions.captureKoreanDictionaryQuery())
        assertTrue(blocked.log.isEmpty())

        val failed = Fixture(finishSucceeds = false)
        assertNull(failed.actions.captureKoreanDictionaryQuery())
        assertEquals(listOf("finish"), failed.log)
    }

    @Test
    fun `a particle from a stale snapshot is refused before composition is touched`() {
        val fixture = Fixture(before = "학교", cursor = 2)
        val snapshot = KoreanParticleSnapshot(
            editor = KoreanParticleEditorTarget("com.example.chat", 7, 1, cursor = 1),
            contextTail = "학교",
            suggestions = listOf(KoreanParticleSuggestion(KoreanParticleKind.Topic, "는"))
        )

        assertFalse(fixture.actions.commitKoreanParticle(snapshot, "는"))

        assertTrue(fixture.log.isEmpty())
    }

    @Test
    fun `a particle outside the offered suggestions is refused`() {
        val fixture = Fixture(before = "학교", cursor = 2)
        val snapshot = KoreanParticleSnapshot(
            editor = KoreanParticleEditorTarget("com.example.chat", 7, 1, cursor = 2),
            contextTail = "학교",
            suggestions = listOf(KoreanParticleSuggestion(KoreanParticleKind.Topic, "는"))
        )

        assertFalse(fixture.actions.commitKoreanParticle(snapshot, "가"))

        assertTrue(fixture.log.isEmpty())
    }

    @Test
    fun `typo recovery refuses another field before finishing composition`() {
        val fixture = Fixture(before = "dkssud", cursor = 6)

        val otherField = TypoRecoveryEditorTarget("com.example.chat", 8, 1)
        assertFalse(fixture.actions.replaceTypoRecoveryText(otherField, "dkssud", "안녕"))
        val otherPackage = TypoRecoveryEditorTarget("com.example.other", 7, 1)
        assertFalse(fixture.actions.replaceTypoRecoveryText(otherPackage, "dkssud", "안녕"))
        val otherType = TypoRecoveryEditorTarget("com.example.chat", 7, 2)
        assertFalse(fixture.actions.replaceTypoRecoveryText(otherType, "dkssud", "안녕"))

        assertTrue(fixture.log.isEmpty())
    }

    @Test
    fun `typo recovery replaces the expected text in one batch and moves the predicted cursor`() {
        val fixture = Fixture(before = "dkssud", cursor = 6)
        val sameField = TypoRecoveryEditorTarget("com.example.chat", 7, 1)

        assertTrue(fixture.actions.replaceTypoRecoveryText(sameField, "dkssud", "안녕"))

        assertEquals(
            listOf(
                "finish",
                "getTextBeforeCursor(6,0)",
                "marked",
                "beginBatchEdit()",
                "deleteSurroundingText(6,0)",
                "commitText(안녕,1)",
                "endBatchEdit()"
            ),
            fixture.log
        )
        assertEquals(2, fixture.selection.latest.start)
    }

    @Test
    fun `typo recovery refuses text that is no longer in front of the cursor`() {
        val fixture = Fixture(before = "dkssud", cursor = 6)
        val sameField = TypoRecoveryEditorTarget("com.example.chat", 7, 1)

        assertFalse(fixture.actions.replaceTypoRecoveryText(sameField, "rktskd", "가나"))

        assertEquals(listOf("finish", "getTextBeforeCursor(6,0)"), fixture.log)
    }
}
