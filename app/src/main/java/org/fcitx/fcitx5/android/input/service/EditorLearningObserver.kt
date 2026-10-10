/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.isEditorEmptied

/**
 * Feeds what happens in the editor to personal learning: Typing DNA continuity and the
 * correction-session tracker.
 *
 * Typing DNA lives in credential-protected storage, so Direct Boot never reaches it. The editor's
 * text is read only while text inspection is allowed, and a deleted character is read before the
 * deletion is sent, because it is gone afterwards.
 */
internal class EditorLearningObserver(private val host: Host) {

    /** The service state and learning sinks the observer reads or notifies. */
    data class Host(
        val allowsCredentialProtectedFeatures: () -> Boolean,
        val allowsTextInspection: () -> Boolean,
        val inputConnection: () -> InputConnection?,
        val packageName: () -> String?,
        val currentWordBeforeCursor: () -> String,
        val recordCorrectionBackspace: (wordBeforeCursor: String) -> Unit,
        val onEditorContinuityLost: (
            packageName: String?,
            removedText: String?,
            inspectionAllowed: Boolean
        ) -> Unit,
        val flushPending: () -> Unit,
        val observeCommittedText: (text: String) -> Unit,
        val onEditorSessionStarted: (packageName: String?, fieldId: Int, restarting: Boolean) -> Unit
    )

    /** Call before sending a deletion that removes [beforeCursorCount] characters before the cursor. */
    fun onDeleting(beforeCursorCount: Int) {
        notifyDeleting(beforeCursorCount, host.allowsTextInspection())
    }

    /** Records the word a Backspace is about to cut into, for correction-pattern learning. */
    fun onBackspace() {
        if (host.allowsTextInspection()) {
            host.recordCorrectionBackspace(host.currentWordBeforeCursor())
        }
    }

    /** A Backspace that deletes one character: continuity first, then the correction session. */
    fun onBackspaceKey() {
        val inspectionAllowed = host.allowsTextInspection()
        notifyDeleting(1, inspectionAllowed)
        if (inspectionAllowed) {
            host.recordCorrectionBackspace(host.currentWordBeforeCursor())
        }
    }

    /** A forwarded Backspace or forward-Delete key press, before the key reaches the editor. */
    fun onForwardedDeleteDown(backspace: Boolean) {
        if (backspace) onBackspace()
        onDeleting(if (backspace) 1 else 0)
    }

    fun flushPending() {
        if (!host.allowsCredentialProtectedFeatures()) return
        host.flushPending()
    }

    fun observeCommittedText(text: String) {
        if (!host.allowsCredentialProtectedFeatures()) return
        host.observeCommittedText(text)
    }

    /**
     * A messenger clears its input field after sending, so an emptied editor means the pending
     * sentence was sent and is flushed whole; any other external cursor move only breaks continuity.
     */
    fun onExternalCursorMove(newSelStart: Int, newSelEnd: Int) {
        val inspectionAllowed = host.allowsTextInspection()
        if (inspectionAllowed && isEditorEmptied(newSelStart, newSelEnd, host.inputConnection())) {
            flushPending()
        } else {
            notifyContinuityLost(null, inspectionAllowed)
        }
    }

    fun onEditorSessionStarted(
        packageName: String?,
        fieldId: Int,
        initialSelStart: Int,
        initialSelEnd: Int,
        restarting: Boolean
    ) {
        if (!host.allowsCredentialProtectedFeatures()) return
        // An app that clears its field after sending (setText("")) restarts the same field empty
        // instead of moving the cursor, so the pending text was sent and must not glue onto the next one.
        if (restarting && host.allowsTextInspection() &&
            isEditorEmptied(initialSelStart, initialSelEnd, host.inputConnection())
        ) {
            flushPending()
        }
        host.onEditorSessionStarted(packageName, fieldId, restarting)
    }

    private fun notifyDeleting(beforeCursorCount: Int, inspectionAllowed: Boolean) {
        val removedText = if (beforeCursorCount > 0 && inspectionAllowed) {
            host.inputConnection()?.getTextBeforeCursor(beforeCursorCount, 0)?.toString()
        } else {
            null
        }
        notifyContinuityLost(removedText, inspectionAllowed)
    }

    private fun notifyContinuityLost(removedText: String?, inspectionAllowed: Boolean) {
        if (!host.allowsCredentialProtectedFeatures()) return
        host.onEditorContinuityLost(host.packageName(), removedText, inspectionAllowed)
    }
}
