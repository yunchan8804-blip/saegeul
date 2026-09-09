/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Transport-agnostic entry for Typing DNA collection.
 * DirectCommit, SystemPaste, and CtrlV must all land here after the editor accepts text.
 */
class TypingDnaCommitSink(
    private val collector: UserTypingContextCollector
) {
    /** New editor metadata never proves continuity with an earlier text field. */
    fun onEditorSessionStarted() {
        collector.discardPending()
    }

    fun onEditorContinuityLost(packageName: String?, inspectionAllowed: Boolean) {
        if (!inspectionAllowed) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        collector.discardPending(pkg)
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
