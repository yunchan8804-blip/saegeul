/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typingdna

import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector

/**
 * Transport-agnostic entry for Typing DNA collection.
 * DirectCommit, SystemPaste, and CtrlV must all land here after the editor accepts text.
 */
class TypingDnaCommitSink(
    private val collector: UserTypingContextCollector
) {
    /**
     * A new editor session started for [packageName]/[fieldId]. Pending text is discarded
     * unless this is a same-field restart (see [UserTypingContextCollector.onEditorSessionStarted]).
     * A missing/blank package cannot be tied to a prior session, so it always discards everything.
     */
    fun onEditorSessionStarted(packageName: String?, fieldId: Int, restarting: Boolean) {
        val pkg = packageName?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            collector.discardPending()
            return
        }
        collector.onEditorSessionStarted(pkg, fieldId, restarting)
    }

    /**
     * A backspace/Delete or an unpredicted cursor move broke continuity with the pending
     * buffer. [removedText], when known, lets only the actually-removed suffix be cut; otherwise
     * only the last whitespace-separated token is dropped.
     */
    fun onEditorContinuityLost(packageName: String?, removedText: String? = null, inspectionAllowed: Boolean) {
        if (!inspectionAllowed) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        collector.onBackspaceContinuityLost(pkg, removedText)
    }

    fun onEditorSuffixDeleted(
        packageName: String?,
        removedText: String?,
        inspectionAllowed: Boolean
    ) {
        if (!inspectionAllowed) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        collector.removePendingSuffix(pkg, removedText)
    }

    fun onEditorTextCommitted(
        packageName: String?,
        text: String,
        inspectionAllowed: Boolean
    ) {
        if (!inspectionAllowed || text.isEmpty()) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        collector.recordCommittedText(pkg, text)
    }

    fun onEditorSubmit(packageName: String?, inspectionAllowed: Boolean) {
        onEditorFinished(packageName, inspectionAllowed)
    }

    fun onEditorFinished(packageName: String?, inspectionAllowed: Boolean) {
        if (!inspectionAllowed) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        collector.flushPending(pkg)
    }
}
