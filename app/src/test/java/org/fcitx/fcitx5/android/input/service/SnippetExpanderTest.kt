/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetExpanderTest {

    private class Fixture(
        var credentialProtected: Boolean = true,
        var promptStarting: Boolean = false,
        var autoExpansion: Boolean = true,
        var textInspection: Boolean = true,
        var buffered: Boolean = false,
        cursor: Int = 5,
        selectionEnd: Int = cursor
    ) {
        val log = mutableListOf<String>()
        val selection = CursorTracker().apply { resetTo(cursor, selectionEnd) }

        val expander = SnippetExpander(
            SnippetExpander.Host(
                context = { error("no snippet matched, so no context is needed") },
                lifecycleScope = { error("Direct Boot must not launch a catalog load") },
                allowsCredentialProtectedFeatures = { credentialProtected },
                isPromptCaptureStarting = { promptStarting },
                autoExpansionEnabled = { autoExpansion },
                allowsTextInspection = { textInspection },
                selection = { selection },
                inputConnection = { fakeInputConnection(log, before = "plain text") },
                finishCompositionForDirectAction = {
                    log += "finish"
                    true
                }
            ),
            object : BufferedHangulSnippetPort {
                override val isBuffered: Boolean
                    get() = buffered

                override fun snapshot(): String {
                    log += "snapshot"
                    return ""
                }

                override fun dispatchBufferedText(text: String): Boolean {
                    log += "dispatch($text)"
                    return true
                }

                override fun clear() {
                    log += "clear"
                }

                override fun queueEngineReset() {
                    log += "queueReset"
                }

                override fun refreshPreedit() {
                    log += "refreshPreedit"
                }
            }
        )
    }

    @Test
    fun `boundary maps the keys that can end a snippet`() {
        assertEquals(SnippetBoundary.Space, SnippetBoundary.forText(" "))
        assertEquals(SnippetBoundary.Enter, SnippetBoundary.forText("\n"))
        assertEquals(SnippetBoundary.Enter, SnippetBoundary.forText("\r"))
        assertNull(SnippetBoundary.forText("a"))
        assertNull(SnippetBoundary.forText(""))
    }

    @Test
    fun `only Enter is consumed when an expansion fails`() {
        assertFalse(SnippetBoundary.Space.consumeOnFailure)
        assertTrue(SnippetBoundary.Enter.consumeOnFailure)
        assertEquals(" ", SnippetBoundary.Space.suffix)
        assertEquals("", SnippetBoundary.Enter.suffix)
    }

    @Test
    fun `a closed gate leaves the editor and the Hangul buffer untouched`() {
        val gates = listOf<(Fixture) -> Unit>(
            { it.promptStarting = true },
            { it.autoExpansion = false },
            { it.textInspection = false }
        )
        for (closeGate in gates) {
            val fixture = Fixture(buffered = true)
            closeGate(fixture)

            assertFalse(fixture.expander.tryExpand(SnippetBoundary.Enter))

            assertTrue(fixture.log.isEmpty())
        }
    }

    @Test
    fun `a selection leaves the editor and the Hangul buffer untouched`() {
        val fixture = Fixture(buffered = true, cursor = 2, selectionEnd = 5)

        assertFalse(fixture.expander.tryExpand(SnippetBoundary.Enter))

        assertTrue(fixture.log.isEmpty())
    }

    @Test
    fun `a buffered session reads the pending Hangul and an editor session reads the editor`() {
        val buffered = Fixture(buffered = true)
        assertFalse(buffered.expander.tryExpand(SnippetBoundary.Space))
        assertEquals(listOf("getTextBeforeCursor(128,0)", "snapshot"), buffered.log)

        val editor = Fixture(buffered = false)
        assertFalse(editor.expander.tryExpand(SnippetBoundary.Space))
        assertEquals(listOf("getTextBeforeCursor(128,0)"), editor.log)
    }

    @Test
    fun `text that matches no snippet is not consumed even for Enter`() {
        val fixture = Fixture()

        assertFalse(fixture.expander.tryExpand(SnippetBoundary.Enter))
        assertFalse(fixture.expander.tryExpandBuffered(SnippetBoundary.Enter))
    }

    @Test
    fun `direct boot refreshes to the built-in catalog without loading storage`() {
        val fixture = Fixture(credentialProtected = false)

        fixture.expander.refreshCatalog()

        assertTrue(fixture.log.isEmpty())
    }
}
