/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

data class AiEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    /** The Android input session that captured this target; stale async replies must fail closed. */
    val inputSessionEpoch: Long = Long.MIN_VALUE
)

enum class AiSourceKind {
    Selection,
    BeforeCursor,
    /** A bounded editor range split at the current cursor, so both sides can be revalidated. */
    SurroundingEditor
}

/** Describes the exact user-visible scope sent to the writing provider. */
enum class AiSourceScope {
    Selection,
    EntireEditor,
    CursorContext,
    ExternalReply
}

data class AiInputSnapshot(
    val editor: AiEditorTarget,
    val source: String,
    val sourceKind: AiSourceKind,
    /** Exact text immediately before the cursor for [AiSourceKind.SurroundingEditor]. */
    val beforeCursor: String = "",
    /** Exact text immediately after the cursor for [AiSourceKind.SurroundingEditor]. */
    val afterCursor: String = "",
    val scope: AiSourceScope = AiSourceScope.CursorContext
)

sealed class AiInputCaptureResult {
    data class Captured(val snapshot: AiInputSnapshot) : AiInputCaptureResult()
    object NoText : AiInputCaptureResult()
    /** The editor has not yet synchronized the selection that the IME is bound to. */
    object EditorStateChanged : AiInputCaptureResult()
    object SelectionTooLarge : AiInputCaptureResult()
}

enum class AiApplyMode {
    Replace,
    Append
}

data class AiAppliedEdit(
    val editor: AiEditorTarget,
    val inserted: String,
    val restore: String,
    /** Cursor position relative to [restore] after a successful undo. */
    val restoreCursorOffset: Int = restore.length
)

/**
 * The result of applying a reviewed suggestion to the app editor.
 *
 * [EditorChanged] is reserved for a stale target or source precondition. [NotApplied] means the
 * same editor was still current, but it did not visibly accept the requested mutation. Keeping
 * those cases separate prevents a rejecting editor from being described as though the user had
 * changed its text or cursor.
 */
sealed class AiSuggestionApplyResult {
    data class Applied(val edit: AiAppliedEdit) : AiSuggestionApplyResult()
    object EditorChanged : AiSuggestionApplyResult()
    object NotApplied : AiSuggestionApplyResult()
}

/**
 * Keeps the small InputConnection mutation protocol testable without retaining editor text.
 *
 * The Android remote InputConnection protocol acknowledges that a command was delivered, not
 * that the editor applied it. Therefore [confirmCommit] must check the editor's visible state
 * after a successful dispatch. A rejected or unconfirmed command restores the exact selection
 * that was visible before this protocol moved it. The caller intentionally owns the connection
 * and does not expose it outside the IME.
 */
internal object AiEditorTransaction {
    fun replaceRange(
        start: Int,
        end: Int,
        replacement: String,
        restoreStart: Int,
        restoreEnd: Int,
        setSelection: (Int, Int) -> Boolean,
        commitText: (String) -> Boolean,
        confirmCommit: (String) -> Boolean
    ): Boolean {
        if (start < 0 || end < start || !runCatching { setSelection(start, end) }.getOrDefault(false)) {
            return false
        }
        val committed = runCatching { commitText(replacement) }.getOrDefault(false)
        if (committed && runCatching { confirmCommit(replacement) }.getOrDefault(false)) return true
        runCatching { setSelection(restoreStart, restoreEnd) }
        return false
    }

    fun commitAtCursor(
        cursor: Int,
        text: String,
        restoreStart: Int,
        restoreEnd: Int,
        setSelection: (Int, Int) -> Boolean,
        commitText: (String) -> Boolean,
        confirmCommit: (String) -> Boolean
    ): Boolean {
        if (cursor < 0 || !runCatching { setSelection(cursor, cursor) }.getOrDefault(false)) {
            return false
        }
        val committed = runCatching { commitText(text) }.getOrDefault(false)
        if (committed && runCatching { confirmCommit(text) }.getOrDefault(false)) return true
        runCatching { setSelection(restoreStart, restoreEnd) }
        return false
    }
}

object AiTextSource {
    const val MAX_CHARACTERS = 4_000

    data class SurroundingText(
        val beforeCursor: String,
        val afterCursor: String
    ) {
        val text: String
            get() = beforeCursor + afterCursor
    }

    /** Returns a selection only when it can be sent and later revalidated as a whole. */
    fun selectedText(text: String?): String? =
        text?.takeIf { it.isNotBlank() && it.length <= MAX_CHARACTERS }

    /**
     * Accepts an editor extraction only when it is complete and bounded. An extracted window
     * with a non-zero offset may omit text before it, so it must never be presented as the whole
     * editor or used for a whole-editor replacement.
     */
    fun completeEditor(
        text: String,
        startOffset: Int,
        partialStartOffset: Int,
        cursorOffset: Int,
        extractedSelectionStart: Int,
        extractedSelectionEnd: Int
    ): SurroundingText? {
        if (startOffset != 0 || partialStartOffset != -1 || text.length > MAX_CHARACTERS ||
            cursorOffset !in 0..text.length || text.isBlank() ||
            // ExtractedText reports its own selection relative to startOffset. A complete editor
            // extract is safe to present as the current whole field only when that snapshot agrees
            // with the selection captured immediately before the read.
            extractedSelectionStart != cursorOffset || extractedSelectionEnd != cursorOffset
        ) return null
        return SurroundingText(
            beforeCursor = text.substring(0, cursorOffset),
            afterCursor = text.substring(cursorOffset)
        )
    }

    /**
     * [android.view.inputmethod.ExtractedText] is authoritative about the editor selection at
     * the instant it was read. Never combine its text with a different IME selection snapshot:
     * doing so can calculate a replacement range for the wrong cursor.
     */
    fun matchesExtractedSelection(
        startOffset: Int,
        capturedSelectionStart: Int,
        capturedSelectionEnd: Int,
        extractedSelectionStart: Int,
        extractedSelectionEnd: Int
    ): Boolean = extractedSelectionStart >= 0 &&
        extractedSelectionEnd >= 0 &&
        startOffset + extractedSelectionStart == capturedSelectionStart &&
        startOffset + extractedSelectionEnd == capturedSelectionEnd

    /**
     * Returns the complete nearby text when it fits the privacy and request bound. For longer
     * editors, use the current paragraph around the cursor before applying the same bound. The
     * two halves stay separate so a later replacement can validate and restore both sides.
     */
    fun cursorContext(beforeCursor: String, afterCursor: String): SurroundingText? {
        val complete = SurroundingText(beforeCursor, afterCursor)
        if (complete.text.length <= MAX_CHARACTERS && complete.text.isNotBlank()) return complete

        val paragraph = SurroundingText(
            beforeCursor.substringAfterLast('\n'),
            afterCursor.substringBefore('\n')
        )
        return bounded(paragraph).takeIf { it.text.isNotBlank() }
            ?: bounded(complete).takeIf { it.text.isNotBlank() }
    }

    private fun bounded(text: SurroundingText): SurroundingText {
        if (text.text.length <= MAX_CHARACTERS) return text
        // Preserve the whole shorter side first, then divide the remaining budget around cursor.
        val afterBudget = minOf(text.afterCursor.length, MAX_CHARACTERS / 2)
        val beforeBudget = minOf(text.beforeCursor.length, MAX_CHARACTERS - afterBudget)
        val after = text.afterCursor.take(MAX_CHARACTERS - beforeBudget)
        return SurroundingText(
            beforeCursor = text.beforeCursor.takeLast(beforeBudget),
            afterCursor = after
        )
    }
}
