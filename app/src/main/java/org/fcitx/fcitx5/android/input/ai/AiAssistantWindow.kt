/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow

/** Preview-first AI writing assistant. Network requests only begin after an action tap. */
class AiAssistantWindow : InputWindow.ExtendedInputWindow<AiAssistantWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val usageStore by lazy { AiUsageStore(context) }

    private lateinit var ui: AiAssistantUi
    private var snapshot: AiInputSnapshot? = null
    private var profile: AiProviderProfile? = null
    private var lastAction: AiAction? = null
    private var appliedEdit: AiAppliedEdit? = null
    private var requestJob: Job? = null

    override val title: String by lazy { context.getString(R.string.ai_assistant_title) }
    override val showTitle: Boolean = false

    override fun onCreateView(): View {
        ui = AiAssistantUi(context, theme).apply {
            onActionSelected = ::generate
            onSuggestionReplace = { applySuggestion(it, AiApplyMode.Replace) }
            onSuggestionAppend = { applySuggestion(it, AiApplyMode.Append) }
            onUndo = ::undo
            onRetry = { lastAction?.let(::generate) }
        }
        return ui.root
    }

    override fun onAttached() {
        when {
            !service.allowsTextInspectionFeatures() -> {
                ui.showError(context.getString(R.string.ai_private_disabled), canRetry = false)
                return
            }
            !service.allowsNetworkInputFeatures() -> {
                ui.showError(context.getString(R.string.ai_offline_disabled), canRetry = false)
                return
            }
        }
        val effective = AiProviderResolver.resolve(context)
        val resolved = effective.profile
        if (resolved == null) {
            ui.showError(context.getString(R.string.ai_not_configured), canRetry = false)
            return
        }
        profile = resolved
        snapshot = service.captureAiInputSnapshot()
        val captured = snapshot
        if (captured == null) {
            ui.showError(
                context.getString(R.string.ai_no_source),
                resolved.displayName,
                canRetry = false
            )
            return
        }
        ui.showSourcePreview(captured.source, resolved.displayName)
    }

    override fun onDetached() {
        requestJob?.cancel()
    }

    private fun generate(action: AiAction) {
        val source = snapshot ?: return
        val provider = profile ?: return
        requestJob?.cancel()
        lastAction = action
        ui.showLoading(action, provider.displayName)
        requestJob = service.lifecycleScope.launch {
            try {
                val result = OpenAiResponsesClient(provider).generate(action, source.source)
                usageStore.recordSuccess(
                    action,
                    provider.kind,
                    result.model,
                    result.inputCharacters,
                    result.outputCharacters
                )
                ui.showResults(action, provider.displayName, result.suggestions)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                usageStore.recordFailure(
                    action,
                    provider.kind,
                    provider.model(action.tier),
                    source.source.length
                )
                ui.showError(
                    exception.message ?: context.getString(R.string.ai_apply_failed),
                    provider.displayName,
                    canRetry = true
                )
            }
        }
    }

    private fun applySuggestion(suggestion: String, mode: AiApplyMode) {
        val source = snapshot ?: return
        val edit = service.applyAiSuggestion(source, suggestion, mode)
        if (edit == null) {
            ui.showError(
                context.getString(R.string.ai_editor_changed),
                profile?.displayName,
                canRetry = false
            )
            return
        }
        appliedEdit = edit
        ui.setUndoAvailable(true)
    }

    private fun undo() {
        val edit = appliedEdit ?: return
        if (!service.undoAiEdit(edit)) {
            ui.showError(
                context.getString(R.string.ai_undo_failed),
                profile?.displayName,
                canRetry = false
            )
            return
        }
        appliedEdit = null
        ui.setUndoAvailable(false)
    }
}
