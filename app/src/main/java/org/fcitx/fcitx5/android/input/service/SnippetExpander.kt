/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.content.Context
import android.view.inputmethod.InputConnection
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseTemplate
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseValues
import org.fcitx.fcitx5.android.data.quickphrase.snippet.SnippetCatalog
import org.fcitx.fcitx5.android.data.quickphrase.snippet.SnippetRepository
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import timber.log.Timber
import java.time.ZonedDateTime

/** The key that ended a word and may therefore trigger a snippet. */
internal enum class SnippetBoundary(val suffix: String, val consumeOnFailure: Boolean) {
    Space(" ", false),
    // A recognized snippet must never be sent to a chat accidentally because its personal
    // profile value is missing. The first Enter expands (or reports the issue); a second one
    // performs the editor action.
    Enter("", true);

    companion object {
        fun forText(text: String): SnippetBoundary? = when (text) {
            " " -> Space
            "\n", "\r" -> Enter
            else -> null
        }
    }
}

/** What snippet expansion needs from the buffered Hangul compatibility mode. */
internal interface BufferedHangulSnippetPort {
    val isBuffered: Boolean

    /** The unsent text: the captured prefix followed by the engine's live preedit. */
    fun snapshot(): String
    fun dispatchBufferedText(text: String): Boolean
    fun clear()
    fun queueEngineReset()
    fun refreshPreedit()
}

/**
 * Expands a typed snippet trigger when Space or Enter ends it. Expansion is atomic: the trigger is
 * selected, replaced by one commit, and put back untouched when that commit fails.
 */
internal class SnippetExpander(
    private val host: Host,
    private val buffered: BufferedHangulSnippetPort
) {

    /** The service state and actions snippet expansion reads or triggers. */
    data class Host(
        val context: () -> Context,
        val lifecycleScope: () -> CoroutineScope,
        val allowsCredentialProtectedFeatures: () -> Boolean,
        val isPromptCaptureStarting: () -> Boolean,
        val autoExpansionEnabled: () -> Boolean,
        val allowsTextInspection: () -> Boolean,
        val selection: () -> CursorTracker,
        val inputConnection: () -> InputConnection?,
        val finishCompositionForDirectAction: () -> Boolean
    )

    @Volatile
    private var catalog = SnippetCatalog.builtIns()
    private var refreshJob: Job? = null

    fun refreshCatalog() {
        refreshJob?.cancel()
        if (!host.allowsCredentialProtectedFeatures()) {
            refreshJob = null
            catalog = SnippetCatalog.builtIns()
            return
        }
        refreshJob = host.lifecycleScope().launch(Dispatchers.IO) {
            catalog = runCatching { SnippetRepository.load() }
                .onFailure { Timber.w(it, "Unable to refresh snippet catalog") }
                .getOrElse { SnippetCatalog.builtIns() }
        }
    }

    /** Returns true when the boundary key was consumed by an expansion (or its reported failure). */
    fun tryExpand(boundary: SnippetBoundary): Boolean {
        // A boundary ahead of an internal-prompt start marker is historical editor input. Do not
        // let it become a deferred direct action while starting freezes new editor writes.
        if (host.isPromptCaptureStarting() || !host.autoExpansionEnabled() ||
            !host.allowsTextInspection() || host.selection().latest.isNotEmpty()
        ) return false
        return if (buffered.isBuffered) {
            tryExpandBuffered(boundary)
        } else {
            tryExpandEditor(boundary)
        }
    }

    fun tryExpandBuffered(boundary: SnippetBoundary): Boolean {
        val ic = host.inputConnection() ?: return false
        val selection = host.selection()
        val originalStart = selection.latest.start
        val originalEnd = selection.latest.end
        if (originalStart != originalEnd) return false
        val editorBefore = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString()
            ?: return false
        val pending = buffered.snapshot()
        val plan = catalog.plan(editorBefore, pending) ?: return false
        val expanded = resolveTemplate(plan.template)
            ?: return boundary.consumeOnFailure
        val selectionStart = originalStart - plan.deleteBeforeCursor
        if (selectionStart < 0 || !ic.setSelection(selectionStart, originalEnd)) {
            return boundary.consumeOnFailure
        }
        selection.resetTo(selectionStart, originalEnd)
        val replacement = plan.replacement(expanded, boundary.suffix)
        val dispatched = buffered.dispatchBufferedText(replacement)
        if (dispatched) {
            buffered.clear()
            buffered.queueEngineReset()
            buffered.refreshPreedit()
            selection.resetTo(selectionStart + replacement.length)
            return true
        }

        ic.setSelection(originalStart, originalEnd)
        selection.resetTo(originalStart, originalEnd)
        Toast.makeText(host.context(), R.string.snippet_insert_failed, Toast.LENGTH_SHORT).show()
        return boundary.consumeOnFailure
    }

    private fun tryExpandEditor(boundary: SnippetBoundary): Boolean {
        val ic = host.inputConnection() ?: return false
        val selection = host.selection()
        val before = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString() ?: return false
        val initialPlan = catalog.plan(before) ?: return false
        val expanded = resolveTemplate(initialPlan.template)
            ?: return boundary.consumeOnFailure
        if (!host.finishCompositionForDirectAction()) return boundary.consumeOnFailure

        // Finishing a composing span can change what the editor exposes. Match again and only
        // mutate when the same trigger/template still ends exactly at the cursor.
        val verifiedBefore = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString()
            ?: return boundary.consumeOnFailure
        val plan = catalog.plan(verifiedBefore)
            ?.takeIf { it.trigger == initialPlan.trigger && it.template == initialPlan.template }
            ?: return boundary.consumeOnFailure
        val originalStart = selection.latest.start
        val originalEnd = selection.latest.end
        val selectionStart = originalStart - plan.deleteBeforeCursor
        if (selectionStart < 0 || !ic.setSelection(selectionStart, originalEnd)) {
            return boundary.consumeOnFailure
        }
        selection.resetTo(selectionStart, originalEnd)
        val replacement = plan.replacement(expanded, boundary.suffix)
        val dispatched = ic.commitText(replacement, 1)
        if (dispatched) {
            selection.resetTo(selectionStart + replacement.length)
            return true
        }

        // setSelection is non-destructive. Restore the cursor so a failed commit leaves the
        // literal trigger available for editing instead of silently deleting it.
        ic.setSelection(originalStart, originalEnd)
        selection.resetTo(originalStart, originalEnd)
        Toast.makeText(host.context(), R.string.snippet_insert_failed, Toast.LENGTH_SHORT).show()
        return boundary.consumeOnFailure
    }

    private fun resolveTemplate(template: String): String? {
        val context = host.context()
        val clipboard = ClipboardManager.lastEntry
        val result = DynamicPhraseTemplate.expand(
            template,
            DynamicPhraseValues(
                now = ZonedDateTime.now(),
                profile = DynamicPhraseProfileStore(context).load(),
                clipboardText = clipboard?.takeUnless { it.sensitive }?.text,
                clipboardSensitive = clipboard?.sensitive == true,
                privateEditor = false
            )
        )
        if (!result.canInsert) {
            Toast.makeText(context, R.string.snippet_missing_value, Toast.LENGTH_SHORT).show()
            return null
        }
        return result.text
    }

    private companion object {
        const val SNIPPET_CONTEXT_CHARS = 128
    }
}
