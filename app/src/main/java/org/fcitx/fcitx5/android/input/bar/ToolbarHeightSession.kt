/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * Keeps the IME's top bar at a stable height while transient content replaces the toolbar.
 *
 * Candidate, clipboard, inline-suggestion, and tool-title surfaces are transient. They must not
 * resize the editor merely because the compact toolbar they replaced has overflow. Only an
 * explicit toolbar visibility or row-expansion action, or a new editor, may alter the height.
 */
internal data class ToolbarHeightSession(
    val visibleRows: Int
) {
    init {
        require(visibleRows in ToolbarLayoutPolicy.COLLAPSED_ROWS..ToolbarLayoutPolicy.EXPANDED_ROWS)
    }
    val heightDp: Int
        get() = ToolbarLayoutPolicy.TOUCH_TARGET_DP * visibleRows

    fun onToolbarVisibilityChanged(
        visible: Boolean,
        needsSecondRow: Boolean
    ): ToolbarHeightSession = create(visible, needsSecondRow)

    fun onToolbarRowsChanged(
        toolbarVisible: Boolean,
        expanded: Boolean
    ): ToolbarHeightSession = if (toolbarVisible) {
        create(visible = true, needsSecondRow = expanded)
    } else {
        this
    }

    /** Transient content inherits the current height contract without changing it. */
    fun onTransientSurfaceChanged(): ToolbarHeightSession = this

    companion object {
        fun start(
            toolbarVisible: Boolean,
            needsSecondRow: Boolean
        ): ToolbarHeightSession = create(toolbarVisible, needsSecondRow)

        private fun create(
            visible: Boolean,
            needsSecondRow: Boolean
        ) = ToolbarHeightSession(
            ToolbarLayoutPolicy.visibleRows(visible && needsSecondRow)
        )
    }
}

/** Preserves the toolbar the user can actually see across an Android same-editor restart. */
internal object ToolbarInputRestartPolicy {
    fun manualToggleForStart(
        expandedForEditor: Boolean,
        preserveVisibleState: Boolean,
        previouslyRequested: Boolean
    ): Boolean = if (preserveVisibleState) {
        expandedForEditor != previouslyRequested
    } else {
        false
    }
}
