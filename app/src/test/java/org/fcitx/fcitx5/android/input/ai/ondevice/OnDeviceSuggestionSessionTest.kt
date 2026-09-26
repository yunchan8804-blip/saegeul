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

class OnDeviceSuggestionSessionTest {

    @Test
    fun `late callback is rejected after ABA observation`() {
        val session = OnDeviceSuggestionSession()
        val initial = snapshot(text = "회의 준비")
        assertTrue(session.observe(initial))
        val ticket = requireNotNull(session.begin(0L))

        assertTrue(session.observe(snapshotAtEnd(initial, revision = 2L, textBeforeCursor = "회의 준비 중")))
        assertTrue(session.observe(snapshotAtEnd(initial, revision = 3L)))

        assertFalse(session.publish(ticket, "를 마칠게요.", 1L))
        assertNull(session.cached(1L))
    }

    @Test
    fun `late callback is rejected after explicit invalidation`() {
        val session = OnDeviceSuggestionSession()
        val initial = snapshot()
        assertTrue(session.observe(initial))
        val ticket = requireNotNull(session.begin(0L))

        assertTrue(session.invalidate(ticket))

        assertFalse(session.publish(ticket, "를 준비할게요.", 1L))
        assertNull(session.cached(1L))
    }

    @Test
    fun `scope changes discard a proposal even with matching text`() {
        val session = completedSession()
        val proposal = requireNotNull(session.cached(1L))

        assertTrue(session.observe(snapshot(packageName = "other.app", revision = 2L)))

        assertNull(session.cached(2L))
        assertNull(session.takeForApply(proposal, snapshot(packageName = "other.app", revision = 2L), 2L))

        val sameAppDifferentField = completedSession()
        assertTrue(sameAppDifferentField.observe(snapshot(fieldId = 8, revision = 2L)))
        assertNull(sameAppDifferentField.cached(2L))
    }

    @Test
    fun `selection and middle cursor discard state`() {
        val session = completedSession()
        val base = snapshot()

        assertFalse(session.observe(base.copy(selectionStart = 1, selectionEnd = 1)))
        assertNull(session.cached(1L))
        assertFalse(session.observe(base.copy(selectionStart = 1, selectionEnd = 2)))
        assertNull(session.begin(1L))
    }

    @Test
    fun `deletion and backtyping never resurrect a cached suffix`() {
        val session = completedSession(suffix = "를 준비할게요.")
        val base = snapshot()

        assertTrue(session.observe(snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의")))
        assertNull(session.cached(2L))
        assertTrue(session.observe(snapshotAtEnd(base, revision = 3L)))

        assertNull(session.cached(3L))
    }

    @Test
    fun `revision-only change discards cache because it may hide an ABA edit`() {
        val session = completedSession()
        val base = snapshot()

        assertTrue(session.observe(base.copy(revision = 2L)))

        assertNull(session.cached(2L))
    }

    @Test
    fun `matching append transfers only remaining generated suffix`() {
        val session = completedSession(suffix = "를 준비할게요.")
        val base = snapshot()

        val appended = snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비를 준")
        assertTrue(session.observe(appended))

        val proposal = requireNotNull(session.cached(2L))
        assertEquals("비할게요.", proposal.suffix)
        assertEquals(OnDeviceSuggestionSession.Origin.CONTINUATION_CACHE, proposal.origin)
        assertEquals("비할게요.", session.takeForApply(proposal, appended, 2L))
    }

    @Test
    fun `nonmatching append discards cache without historical prefix lookup`() {
        val session = completedSession(suffix = "를 준비할게요.")
        val base = snapshot()

        assertTrue(session.observe(snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비를 다시")))

        assertNull(session.cached(2L))
    }

    @Test
    fun `cache TTL starts at generation and append does not extend it`() {
        val session = completedSession(suffix = "를 준비할게요.", createdAt = 10L)
        val base = snapshot()
        assertTrue(session.observe(snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비를")))

        assertNotNull(session.cached(30_009L))
        assertNull(session.cached(30_010L))
    }

    @Test
    fun `apply consumes proposal exactly once and rejects stale proposal`() {
        val session = completedSession()
        val base = snapshot()
        val proposal = requireNotNull(session.cached(1L))

        assertEquals("를 준비할게요.", session.takeForApply(proposal, base, 1L))
        assertNull(session.takeForApply(proposal, base, 1L))

        val staleSession = completedSession()
        val staleProposal = requireNotNull(staleSession.cached(1L))
        val changed = snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비를")
        assertTrue(staleSession.observe(changed))
        assertNull(staleSession.takeForApply(staleProposal, changed, 2L))
    }

    @Test
    fun `append that leaves only whitespace does not become a cached proposal`() {
        val session = completedSession(suffix = " 다음 ")
        val base = snapshot()

        assertTrue(session.observe(snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비 다음")))

        assertNull(session.cached(2L))
    }

    @Test
    fun `dangerous or oversized suffixes cannot be published`() {
        listOf("\n다음", "\r다음", "다\u0000음", "다\u200F음", "가".repeat(121), "  ").forEach { suffix ->
            val session = OnDeviceSuggestionSession()
            val base = snapshot()
            assertTrue(session.observe(base))
            val ticket = requireNotNull(session.begin(0L))

            assertFalse(session.publish(ticket, suffix, 1L))
            assertNull(session.cached(1L))
        }

        val session = OnDeviceSuggestionSession()
        assertFalse(session.observe(snapshot(text = "가".repeat(2049))))
        assertFalse(session.observe(snapshot(text = " \t\n")))
    }

    @Test
    fun `text allows existing line feed and tab while suffix does not`() {
        val session = OnDeviceSuggestionSession()
        val multiline = snapshot(text = "첫 문장입니다.\n\t다음 문장을")

        assertTrue(session.observe(multiline))
        val ticket = requireNotNull(session.begin(0L))
        assertTrue(session.publish(ticket, " 이어갑니다.", 1L))
        assertEquals(" 이어갑니다.", requireNotNull(session.cached(1L)).suffix)
    }

    @Test
    fun `string forms never expose text suffix or package name`() {
        val packageName = "private.example.package"
        val text = "비공개 원문-4731"
        val suffix = " 비공개 접미부-4731"
        val session = OnDeviceSuggestionSession()
        val snapshot = snapshot(packageName = packageName, text = text)
        assertTrue(session.observe(snapshot))
        val ticket = requireNotNull(session.begin(0L))
        assertTrue(session.publish(ticket, suffix, 1L))
        val proposal = requireNotNull(session.cached(1L))

        listOf(snapshot.toString(), snapshot.scope.toString(), proposal.toString(), session.toString()).forEach {
            assertFalse(it.contains(packageName))
            assertFalse(it.contains(text))
            assertFalse(it.contains(suffix))
        }
    }

    @Test
    fun `rebase updates a ready proposal so a newer revision can still apply it`() {
        val session = completedSession()
        val proposal = requireNotNull(session.cached(1L))
        val rebased = snapshot(revision = 2L)

        assertTrue(session.rebase(rebased))

        assertEquals(rebased, requireNotNull(session.cached(1L)).snapshot)
        assertEquals("를 준비할게요.", session.takeForApply(proposal, rebased, 1L))
    }

    @Test
    fun `rebase moves an active ticket so a later publish matches the current snapshot`() {
        val session = OnDeviceSuggestionSession()
        assertTrue(session.observe(snapshot()))
        val ticket = requireNotNull(session.begin(0L))
        val rebased = snapshot(revision = 2L)

        assertTrue(session.rebase(rebased))

        assertTrue(session.publish(ticket, "를 준비할게요.", 1L))
        assertEquals(rebased, requireNotNull(session.cached(1L)).snapshot)
    }

    @Test
    fun `rebase to a different position changes nothing and reports failure`() {
        val session = completedSession()
        val proposalBefore = requireNotNull(session.cached(1L))
        val base = snapshot()

        assertFalse(session.rebase(snapshotAtEnd(base, revision = 2L, textBeforeCursor = "회의 준비를")))

        val proposalAfter = requireNotNull(session.cached(1L))
        assertEquals(base, proposalAfter.snapshot)
        assertEquals(proposalBefore.snapshot, proposalAfter.snapshot)
    }

    @Test
    fun `rebase without a tracked snapshot does nothing`() {
        val session = OnDeviceSuggestionSession()
        assertFalse(session.rebase(snapshot(revision = 2L)))
    }

    @Test
    fun `clear drops active ticket and cached proposal`() {
        val session = completedSession()
        val proposal = requireNotNull(session.cached(1L))
        val snapshot = snapshot()

        session.clear()

        assertNull(session.cached(1L))
        assertNull(session.takeForApply(proposal, snapshot, 1L))
        assertNull(session.begin(1L))

        val activeSession = OnDeviceSuggestionSession()
        assertTrue(activeSession.observe(snapshot))
        val ticket = requireNotNull(activeSession.begin(1L))
        activeSession.clear()
        assertFalse(activeSession.publish(ticket, "를 준비할게요.", 2L))
    }

    private fun completedSession(
        suffix: String = "를 준비할게요.",
        createdAt: Long = 0L
    ): OnDeviceSuggestionSession {
        val session = OnDeviceSuggestionSession()
        assertTrue(session.observe(snapshot()))
        val ticket = requireNotNull(session.begin(createdAt))
        assertTrue(session.publish(ticket, suffix, createdAt))
        return session
    }

    private fun snapshot(
        packageName: String = "example.app",
        fieldId: Int = 7,
        editorSessionId: Long = 99L,
        revision: Long = 1L,
        text: String = "회의 준비",
        selectionStart: Int = text.length,
        selectionEnd: Int = text.length
    ): OnDeviceSuggestionSession.Snapshot = OnDeviceSuggestionSession.Snapshot(
        scope = OnDeviceSuggestionSession.Scope(packageName, fieldId, editorSessionId),
        revision = revision,
        textBeforeCursor = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd
    )

    private fun snapshotAtEnd(
        base: OnDeviceSuggestionSession.Snapshot,
        revision: Long,
        textBeforeCursor: String = base.textBeforeCursor
    ): OnDeviceSuggestionSession.Snapshot = OnDeviceSuggestionSession.Snapshot(
        scope = base.scope,
        revision = revision,
        textBeforeCursor = textBeforeCursor,
        selectionStart = textBeforeCursor.length,
        selectionEnd = textBeforeCursor.length
    )
}
