/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.context.KoreanParticleCommitContract
import org.fcitx.fcitx5.android.input.context.KoreanParticleEditorTarget
import org.fcitx.fcitx5.android.input.context.KoreanParticleSnapshot
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggester
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.search.KoreanDictionaryQuery
import org.fcitx.fcitx5.android.input.typo.KoreanTypoRecovery
import org.fcitx.fcitx5.android.input.typo.TypoRecoveryEditorTarget
import org.fcitx.fcitx5.android.input.typo.TypoRecoverySnapshot
import org.fcitx.fcitx5.android.input.voice.DictationErasePolicy
import org.fcitx.fcitx5.android.utils.withBatchEdit

/**
 * Editor actions the user triggers from an IME window (OCR, dictation, dictionary, particles, typo
 * recovery). Each one first passes its privacy gate, then settles any composition through
 * [Host.finishCompositionForDirectAction], which also refuses while an internal prompt owns the
 * keyboard, and fails instead of falling back when the editor is not exactly as expected.
 */
internal class EditorDirectActions(private val host: Host) {

    /** The service state and actions a direct editor action reads or triggers. */
    data class Host(
        val allowsTextInspection: () -> Boolean,
        val allowsNetworkInput: () -> Boolean,
        val finishCompositionForDirectAction: () -> Boolean,
        val editorInfo: () -> EditorInfo,
        val inputConnection: () -> InputConnection?,
        val selection: () -> CursorTracker,
        val markEditorStateChanged: () -> Unit,
        val notifySnapshotInvalidated: () -> Unit,
        val postRefreshContextualCandidates: () -> Unit,
        val commitTextToEditor: (text: String, cursor: Int) -> Boolean
    )

    /**
     * Commit the current Hangul/composing segment before a rich-content transaction. Returning
     * false is final: callers must not attach content or silently fall back to inserting a URL.
     */
    fun prepareRichContentCommit(): Boolean {
        if (!host.allowsNetworkInput()) return false
        return host.finishCompositionForDirectAction()
    }

    /** Finishes composition for on-device OCR or dictation, which never needs the network. */
    fun prepareTextInspectionCommit(): Boolean {
        if (!host.allowsTextInspection()) return false
        return host.finishCompositionForDirectAction()
    }

    /**
     * Removes [expected] in front of the cursor, but only while it is exactly what is there. Text
     * typed or a cursor moved since it was dictated makes this do nothing and return false.
     */
    fun eraseDictatedText(expected: String): Boolean {
        if (!host.allowsTextInspection() || host.selection().latest.isNotEmpty()) return false
        if (!host.finishCompositionForDirectAction()) return false
        val ic = host.inputConnection() ?: return false
        if (!DictationErasePolicy.canErase(ic.getTextBeforeCursor(expected.length, 0), expected)) {
            return false
        }
        host.markEditorStateChanged()
        val deleted = ic.deleteSurroundingText(expected.length, 0)
        if (deleted) {
            host.selection().predictOffset(-expected.length)
            host.notifySnapshotInvalidated()
            host.postRefreshContextualCandidates()
        }
        return deleted
    }

    /** Captures only the explicitly selected or cursor-adjacent Korean headword for local lookup. */
    fun captureKoreanDictionaryQuery(): String? {
        if (!host.allowsTextInspection()) return null
        if (!host.finishCompositionForDirectAction()) return null
        val connection = host.inputConnection() ?: return null
        return KoreanDictionaryQuery.extract(
            selectedText = connection.getSelectedText(0)?.toString(),
            beforeCursor = connection.getTextBeforeCursor(
                KoreanDictionaryQuery.MAX_LENGTH * 2,
                0
            )?.toString()
        )
    }

    /** Captures a bounded local context only after the user explicitly opens particle suggestions. */
    fun captureKoreanParticleSnapshot(): KoreanParticleSnapshot? {
        if (!host.allowsTextInspection() || host.selection().latest.isNotEmpty()) return null
        if (!host.finishCompositionForDirectAction()) return null
        val info = host.editorInfo()
        val cursor = host.selection().latest.start
        if (cursor < 0) return null
        val tail = host.inputConnection()?.getTextBeforeCursor(
            KOREAN_PARTICLE_CONTEXT_CHARACTERS,
            0
        )?.toString() ?: return null
        val suggestions = KoreanParticleSuggester.suggest(tail)
        if (suggestions.isEmpty()) return null
        return KoreanParticleSnapshot(
            editor = particleTarget(info, cursor),
            contextTail = tail,
            suggestions = suggestions
        )
    }

    /** Inserts one explicitly selected particle. A stale editor/context fails without fallback. */
    fun commitKoreanParticle(snapshot: KoreanParticleSnapshot, text: String): Boolean {
        if (!host.allowsTextInspection() || host.selection().latest.isNotEmpty()) return false
        val currentEditor = particleTarget(host.editorInfo(), host.selection().latest.start)
        if (currentEditor != snapshot.editor || snapshot.suggestions.none { it.text == text }) {
            return false
        }
        if (!host.finishCompositionForDirectAction()) return false
        val connection = host.inputConnection() ?: return false
        val currentTail = connection.getTextBeforeCursor(
            KOREAN_PARTICLE_CONTEXT_CHARACTERS,
            0
        )?.toString() ?: return false
        if (!KoreanParticleCommitContract.canCommit(snapshot, currentEditor, currentTail, text)) {
            return false
        }
        return host.commitTextToEditor(text, 1)
    }

    fun captureTypoRecoverySnapshot(): TypoRecoverySnapshot? {
        if (!host.allowsTextInspection() || host.selection().latest.isNotEmpty()) return null
        if (!host.finishCompositionForDirectAction()) return null
        val info = host.editorInfo()
        val beforeCursor = host.inputConnection()?.getTextBeforeCursor(64, 0)?.toString()
            ?: return null
        val chunk = KoreanTypoRecovery.lastChunk(beforeCursor) ?: return null
        val proposals = KoreanTypoRecovery.proposals(chunk)
        if (proposals.isEmpty()) return null
        return TypoRecoverySnapshot(
            editor = TypoRecoveryEditorTarget(info.packageName, info.fieldId, info.inputType),
            chunk = chunk,
            proposals = proposals
        )
    }

    fun replaceTypoRecoveryText(
        editor: TypoRecoveryEditorTarget,
        expected: String,
        replacement: String
    ): Boolean {
        if (!host.allowsTextInspection() || host.selection().latest.isNotEmpty()) return false
        if (!EditorIdentity.of(host.editorInfo()).sameField(editor.identity())) return false
        if (!host.finishCompositionForDirectAction()) return false
        val ic = host.inputConnection() ?: return false
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        val previousCursor = host.selection().latest.start
        host.markEditorStateChanged()
        var dispatched = true
        ic.withBatchEdit {
            dispatched = deleteSurroundingText(expected.length, 0) && dispatched
            if (dispatched) dispatched = commitText(replacement, 1) && dispatched
        }
        if (dispatched) {
            host.selection().predict(previousCursor - expected.length + replacement.length)
        }
        return dispatched
    }

    private fun particleTarget(info: EditorInfo, cursor: Int) = KoreanParticleEditorTarget(
        packageName = info.packageName,
        fieldId = info.fieldId,
        inputType = info.inputType,
        cursor = cursor
    )

    private fun TypoRecoveryEditorTarget.identity() = EditorIdentity(packageName, fieldId, inputType)

    private companion object {
        const val KOREAN_PARTICLE_CONTEXT_CHARACTERS = 64
    }
}
