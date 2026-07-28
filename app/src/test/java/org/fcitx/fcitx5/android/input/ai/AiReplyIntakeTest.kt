/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyIntakeTest {
    @Test
    fun explicitPlainTextShareIsConsumedExactlyOnce() {
        val store = AiPendingReplySourceStore()
        assertTrue(store.offerShared(AiPendingReplySourceStore.ACTION_SEND, "text/plain", "답장 원문", 100L))

        val source = store.consumeIfAllowed(allowed = true, nowMillis = 101L)

        assertEquals("답장 원문", source?.text)
        assertEquals(AiReplySourceOrigin.Shared, source?.origin)
        assertNull(store.consumeIfAllowed(allowed = true, nowMillis = 102L))
    }

    @Test
    fun wrongActionMimeBlankAndExpiredSharesFailClosed() {
        val store = AiPendingReplySourceStore(ttlMillis = 50L)
        assertFalse(store.offerShared("android.intent.action.VIEW", "text/plain", "text", 0L))
        assertFalse(store.offerShared(AiPendingReplySourceStore.ACTION_SEND, "text/html", "text", 0L))
        assertFalse(store.offerShared(AiPendingReplySourceStore.ACTION_SEND, "text/plain", "  ", 0L))
        assertTrue(store.offerShared(AiPendingReplySourceStore.ACTION_SEND, "text/plain", "text", 0L))
        assertNull(store.consumeIfAllowed(allowed = true, nowMillis = 51L))
    }

    @Test
    fun blockedEditorDoesNotConsumePendingShare() {
        val store = AiPendingReplySourceStore()
        store.offerShared(AiPendingReplySourceStore.ACTION_SEND, "text/plain", "kept", 10L)

        assertNull(store.consumeIfAllowed(allowed = false, nowMillis = 11L))
        assertEquals("kept", store.consumeIfAllowed(allowed = true, nowMillis = 12L)?.text)
    }

    @Test
    fun clipboardPolicyExposesOnlyExplicitNonSensitiveSelection() {
        val choices = AiClipboardIntakePolicy.choices(
            listOf(
                AiClipboardCandidate(1, "first", sensitive = false, deleted = false),
                AiClipboardCandidate(2, "secret", sensitive = true, deleted = false),
                AiClipboardCandidate(3, "deleted", sensitive = false, deleted = true)
            )
        )

        assertEquals(listOf(1), choices.map(AiClipboardCandidate::id))
        assertNull(AiClipboardIntakePolicy.select(choices, selectedId = 99, allowed = true))
        assertNull(AiClipboardIntakePolicy.select(choices, selectedId = 1, allowed = false))
        assertEquals(
            "first",
            AiClipboardIntakePolicy.select(choices, selectedId = 1, allowed = true)?.text
        )
    }

    @Test
    fun inlineClipboardPickerUsesBoundedLabelsForOnlySelectableRows() {
        val items = AiClipboardPickerPresentation.items(
            listOf(
                AiClipboardCandidate(1, "first\nline", sensitive = false, deleted = false),
                AiClipboardCandidate(2, "secret", sensitive = true, deleted = false),
                AiClipboardCandidate(3, "deleted", sensitive = false, deleted = true),
                AiClipboardCandidate(
                    4,
                    "가".repeat(AiClipboardPickerPresentation.MAX_LABEL_CHARACTERS + 1),
                    sensitive = false,
                    deleted = false
                )
            )
        )

        assertEquals(listOf(1, 4), items.map(AiClipboardPickerItem::id))
        assertEquals("first line", items.first().label)
        assertEquals(AiClipboardPickerPresentation.MAX_LABEL_CHARACTERS, items.last().label.length)
        assertFalse(items.any { it.label.contains("secret") || it.label.contains("deleted") })
    }

    @Test
    fun externalSourceBindsOnlyToCollapsedKnownEditorCursor() {
        val source = AiReplySource("context", AiReplySourceOrigin.Shared, 0L)
        val target = AiEditorTarget("chat.app", 7, 1, 3, 3)

        val snapshot = AiReplySourcePolicy.bindToEditor(source, target)

        assertEquals(target, snapshot?.editor)
        assertEquals("context", snapshot?.source)
        assertNull(AiReplySourcePolicy.bindToEditor(source, target.copy(selectionEnd = 4)))
        assertNull(
            AiReplySourcePolicy.bindToEditor(
                source,
                target.copy(selectionStart = -1, selectionEnd = -1)
            )
        )
    }

    @Test
    fun sourceLengthIsBoundedBeforePreviewOrNetwork() {
        val normalized = AiReplySourcePolicy.normalize("가".repeat(AiTextSource.MAX_CHARACTERS + 10))
        assertEquals(AiTextSource.MAX_CHARACTERS, normalized?.length)
    }

    @Test
    fun reviewedReplyCanApplyExactlyOnceUntilUndo() {
        val gate = AiExactlyOnceApplyGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())

        gate.resetAfterUndo()
        assertTrue(gate.claim())
    }
}
