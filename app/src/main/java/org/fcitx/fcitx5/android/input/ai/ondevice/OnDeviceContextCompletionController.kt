/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.ai.AiAppliedEdit
import org.fcitx.fcitx5.android.input.ai.AiEditorTarget
import org.fcitx.fcitx5.android.input.ai.AiInputCaptureResult
import org.fcitx.fcitx5.android.input.ai.AiInputSnapshot
import org.fcitx.fcitx5.android.input.ai.AiSourceKind
import org.fcitx.fcitx5.android.input.ai.AiSourceScope
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.cursor.CursorRange

/**
 * Owns the editor side of explicit on-device context completion: the active completion window's
 * snapshot invalidation listener and the extracted-text monitor that watches its captured prefix.
 */
class OnDeviceContextCompletionController(
    private val tokens: ExtractedTextTokens,
    private val host: Host
) {

    /** The editor state and commit helpers context completion reads or triggers. */
    data class Host(
        val allowsCompletion: () -> Boolean,
        val editorInfo: () -> EditorInfo?,
        val selection: () -> CursorRange,
        val inputSessionEpoch: () -> Long,
        val inputConnection: () -> InputConnection?,
        val finishCompositionForDirectAction: () -> Boolean,
        val matchesCurrentEditor: (
            identity: EditorIdentity,
            selection: EditorSelection,
            expectedInputSessionEpoch: Long?
        ) -> Boolean,
        val commitAiTextAtCursor: (
            connection: InputConnection,
            cursor: Int,
            text: String,
            restore: EditorSelection
        ) -> Boolean,
        val predictSelection: (position: Int) -> Unit,
        /** Runs after every snapshot invalidation, once this controller has updated itself. */
        val onSnapshotInvalidated: () -> Unit
    )

    private var onDeviceContextSnapshotInvalidationListener: (() -> Unit)? = null
    private var activeOnDeviceContextExtractedTextToken: Int? = null
    private var activeOnDeviceContextExtractedTextEpoch: Long? = null

    /** The token of the monitor watching a captured prefix, or null while none is active. */
    val activeExtractedTextToken: Int?
        get() = activeOnDeviceContextExtractedTextToken

    val hasSnapshotInvalidationListener: Boolean
        get() = onDeviceContextSnapshotInvalidationListener != null

    private val AiEditorTarget.identity: EditorIdentity
        get() = EditorIdentity(packageName, fieldId, inputType)

    private val AiEditorTarget.selection: EditorSelection
        get() = EditorSelection(selectionStart, selectionEnd)

    /** Only the active local completion window may receive invalidation events. */
    fun setOnDeviceContextSnapshotInvalidationListener(listener: (() -> Unit)?) {
        check(listener == null || onDeviceContextSnapshotInvalidationListener == null ||
            onDeviceContextSnapshotInvalidationListener === listener) {
            "On-device context completion already has an active listener"
        }
        if (listener == null) clearOnDeviceContextExtractedTextMonitor()
        onDeviceContextSnapshotInvalidationListener = listener
        host.onSnapshotInvalidated()
    }

    fun notifyOnDeviceContextSnapshotInvalidated() {
        clearOnDeviceContextExtractedTextMonitor()
        onDeviceContextSnapshotInvalidationListener?.invoke()
        host.onSnapshotInvalidated()
    }

    /** The editor reported a change under the active monitor; drop it and tell the window. */
    fun onExtractedTextUpdated() {
        clearOnDeviceContextExtractedTextMonitor()
        onDeviceContextSnapshotInvalidationListener?.invoke()
    }

    private fun beginOnDeviceContextExtractedTextMonitor(epoch: Long): ExtractedTextRequest {
        val token = tokens.next()
        activeOnDeviceContextExtractedTextToken = token
        activeOnDeviceContextExtractedTextEpoch = epoch
        return ExtractedTextRequest().apply {
            this.token = token
            hintMaxChars = ON_DEVICE_CONTEXT_MAX_CHARS + 1
            hintMaxLines = 1
        }
    }

    private fun clearOnDeviceContextExtractedTextMonitor() {
        activeOnDeviceContextExtractedTextToken = null
        activeOnDeviceContextExtractedTextEpoch = null
    }

    /** Captures only a complete, bounded editor prefix with a collapsed cursor at its end. */
    fun captureOnDeviceContextSnapshot(): AiInputCaptureResult {
        clearOnDeviceContextExtractedTextMonitor()
        if (!host.allowsCompletion()) return AiInputCaptureResult.NoText
        if (host.selection().isNotEmpty()) return AiInputCaptureResult.EditorStateChanged
        val capturedSessionEpoch = host.inputSessionEpoch()
        if (!host.finishCompositionForDirectAction()) return AiInputCaptureResult.NoText
        val info = host.editorInfo()
        val capturedSelectionStart = host.selection().start
        val capturedSelectionEnd = host.selection().end
        if (capturedSelectionStart != capturedSelectionEnd || host.inputSessionEpoch() != capturedSessionEpoch) {
            return AiInputCaptureResult.EditorStateChanged
        }
        val connection = host.inputConnection() ?: return AiInputCaptureResult.NoText
        val extractedRequest = beginOnDeviceContextExtractedTextMonitor(capturedSessionEpoch)
        val extracted = runCatching {
            connection.getExtractedText(extractedRequest, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        }.getOrNull() ?: return onDeviceContextEditorStateChanged()
        val extractedText = extracted.text ?: return onDeviceContextEditorStateChanged()
        if (extractedText.length > ON_DEVICE_CONTEXT_MAX_CHARS) {
            clearOnDeviceContextExtractedTextMonitor()
            return AiInputCaptureResult.SelectionTooLarge
        }
        val source = extractedText.toString()
        if (extracted.startOffset != 0 || extracted.partialStartOffset != -1 ||
            extracted.partialEndOffset != -1 ||
            extracted.selectionStart != capturedSelectionStart ||
            extracted.selectionEnd != capturedSelectionEnd ||
            activeOnDeviceContextExtractedTextToken != extractedRequest.token ||
            activeOnDeviceContextExtractedTextEpoch != capturedSessionEpoch
        ) return onDeviceContextEditorStateChanged()
        if (!host.matchesCurrentEditor(
                EditorIdentity.of(info!!),
                EditorSelection(capturedSelectionStart, capturedSelectionEnd),
                capturedSessionEpoch
            )) {
            return onDeviceContextEditorStateChanged()
        }
        if (source.length != capturedSelectionStart) return onDeviceContextEditorStateChanged()
        if (source.isBlank()) {
            clearOnDeviceContextExtractedTextMonitor()
            return AiInputCaptureResult.NoText
        }
        return AiInputCaptureResult.Captured(
            AiInputSnapshot(
                editor = AiEditorTarget(
                    packageName = info.packageName,
                    fieldId = info.fieldId,
                    inputType = info.inputType,
                    selectionStart = capturedSelectionStart,
                    selectionEnd = capturedSelectionEnd,
                    inputSessionEpoch = host.inputSessionEpoch()
                ),
                source = source,
                sourceKind = AiSourceKind.BeforeCursor,
                scope = AiSourceScope.CursorContext
            )
        )
    }

    /** Revalidates the complete captured prefix, its end cursor, and the current editor session. */
    fun isOnDeviceContextSnapshotCurrent(snapshot: AiInputSnapshot): Boolean {
        if (!host.allowsCompletion() ||
            snapshot.sourceKind != AiSourceKind.BeforeCursor ||
            snapshot.editor.selectionStart != snapshot.editor.selectionEnd ||
            snapshot.source.length !in 1..ON_DEVICE_CONTEXT_MAX_CHARS ||
            activeOnDeviceContextExtractedTextEpoch != snapshot.editor.inputSessionEpoch ||
            !host.matchesCurrentEditor(snapshot.editor.identity, snapshot.editor.selection, snapshot.editor.inputSessionEpoch)
        ) return false
        val connection = host.inputConnection() ?: return false
        val beforeCursor = connection.getTextBeforeCursor(ON_DEVICE_CONTEXT_MAX_CHARS + 1, 0)
            ?.toString() ?: return false
        val afterCursor = connection.getTextAfterCursor(1, 0)?.toString() ?: return false
        return beforeCursor == snapshot.source &&
            beforeCursor.length == snapshot.editor.selectionStart &&
            afterCursor == "" &&
            activeOnDeviceContextExtractedTextEpoch == snapshot.editor.inputSessionEpoch &&
            host.matchesCurrentEditor(snapshot.editor.identity, snapshot.editor.selection, snapshot.editor.inputSessionEpoch)
    }

    private fun onDeviceContextEditorStateChanged(): AiInputCaptureResult {
        clearOnDeviceContextExtractedTextMonitor()
        return AiInputCaptureResult.EditorStateChanged
    }

    /** Inserts one reviewed on-device suffix at the captured end cursor without disturbing the rest of the editor. */
    fun applyOnDeviceContextCompletion(
        snapshot: AiInputSnapshot,
        suffix: String
    ): AiSuggestionApplyResult {
        if (suffix.isBlank() || !OnDeviceContextCompletionPolicy.isIncompleteContext(snapshot.source) ||
            OnDeviceContextCompletionPolicy.parseCompletion(snapshot.source, snapshot.source + suffix) != suffix ||
            !isOnDeviceContextSnapshotCurrent(snapshot)
        ) return AiSuggestionApplyResult.EditorChanged
        if (!host.finishCompositionForDirectAction() || !isOnDeviceContextSnapshotCurrent(snapshot)) {
            return AiSuggestionApplyResult.EditorChanged
        }
        val connection = host.inputConnection() ?: return AiSuggestionApplyResult.NotApplied
        val cursor = host.selection().start
        if (!host.commitAiTextAtCursor(connection, cursor, suffix, EditorSelection.collapsed(cursor))) {
            return AiSuggestionApplyResult.NotApplied
        }
        val end = cursor + suffix.length
        host.predictSelection(end)
        notifyOnDeviceContextSnapshotInvalidated()
        return AiSuggestionApplyResult.Applied(
            AiAppliedEdit(
                editor = snapshot.editor.copy(selectionStart = end, selectionEnd = end),
                inserted = suffix,
                restore = ""
            )
        )
    }
}
