/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarHeightSessionTest {
    @Test
    fun sameEditorRestartPreservesManuallyOpenedToolbar() {
        val manualToggle = ToolbarInputRestartPolicy.manualToggleForStart(
            expandedForEditor = false,
            preserveVisibleState = true,
            previouslyRequested = true
        )

        assertTrue(false != manualToggle)
    }

    @Test
    fun sameEditorRestartPreservesVisibleStateWhenProfileDefaultChanges() {
        val manualToggle = ToolbarInputRestartPolicy.manualToggleForStart(
            expandedForEditor = true,
            preserveVisibleState = true,
            previouslyRequested = true
        )

        assertTrue(true != manualToggle)
    }

    @Test
    fun newEditorUsesItsResolvedDefaultInsteadOfPreviousManualState() {
        val manualToggle = ToolbarInputRestartPolicy.manualToggleForStart(
            expandedForEditor = false,
            preserveVisibleState = false,
            previouslyRequested = true
        )

        assertFalse(false != manualToggle)
    }

    @Test
    fun compactExpandedToolbarKeepsHeightAcrossCandidateAndToolSurfaces() {
        val toolbar = ToolbarHeightSession.start(
            toolbarVisible = true,
            needsSecondRow = true
        )

        val candidate = toolbar.onTransientSurfaceChanged()
        val toolTitle = candidate.onTransientSurfaceChanged()

        assertEquals(96, toolbar.heightDp)
        assertEquals(toolbar, candidate)
        assertEquals(toolbar, toolTitle)
    }

    @Test
    fun defaultOneRowToolbarDoesNotMoveWhenSuggestionsAppearWhileTyping() {
        val toolbar = ToolbarHeightSession.start(
            toolbarVisible = true,
            needsSecondRow = false
        )

        val afterSuggestionUpdates = (1..20).fold(toolbar) { session, _ ->
            session.onTransientSurfaceChanged()
        }

        assertEquals(48, toolbar.heightDp)
        assertEquals(toolbar, afterSuggestionUpdates)
    }

    @Test
    fun explicitlyExpandedTwoRowToolbarDoesNotCollapseDuringSuggestionUpdates() {
        val expanded = ToolbarHeightSession.start(
            toolbarVisible = true,
            needsSecondRow = false
        ).onToolbarRowsChanged(
            toolbarVisible = true,
            expanded = true
        )

        val afterSuggestionUpdates = (1..20).fold(expanded) { session, _ ->
            session.onTransientSurfaceChanged()
        }
        val explicitlyCollapsed = afterSuggestionUpdates.onToolbarRowsChanged(
            toolbarVisible = true,
            expanded = false
        )

        assertEquals(96, expanded.heightDp)
        assertEquals(expanded, afterSuggestionUpdates)
        assertEquals(48, explicitlyCollapsed.heightDp)
    }

    @Test
    fun explicitlyHidingToolbarIsTheOnlyTransientSessionCollapse() {
        val expanded = ToolbarHeightSession.start(
            toolbarVisible = true,
            needsSecondRow = true
        )
        val hidden = expanded.onToolbarVisibilityChanged(
            visible = false,
            needsSecondRow = true
        )

        assertEquals(48, hidden.heightDp)
        assertEquals(hidden, hidden.onTransientSurfaceChanged())
    }

    @Test
    fun showingToolbarAgainRestoresMeasuredRows() {
        val hidden = ToolbarHeightSession.start(
            toolbarVisible = false,
            needsSecondRow = true
        )
        val shown = hidden.onToolbarVisibilityChanged(
            visible = true,
            needsSecondRow = true
        )

        assertEquals(48, hidden.heightDp)
        assertEquals(96, shown.heightDp)
    }

    @Test
    fun explicitRowActionUpdatesVisibleToolbarButNotHiddenToolbar() {
        val compact = ToolbarHeightSession.start(
            toolbarVisible = true,
            needsSecondRow = true
        )
        val collapsed = compact.onToolbarRowsChanged(
            toolbarVisible = true,
            expanded = false
        )
        val hidden = compact.onToolbarVisibilityChanged(
            visible = false,
            needsSecondRow = true
        )
        val hiddenAfterExpansion = hidden.onToolbarRowsChanged(
            toolbarVisible = false,
            expanded = true
        )

        assertEquals(48, collapsed.heightDp)
        assertEquals(hidden, hiddenAfterExpansion)
    }
}
