/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

/** Preview-first AI writing assistant. Network requests only begin after an action tap. */
class AiAssistantWindow : InputWindow.ExtendedInputWindow<AiAssistantWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val usageStore by lazy { AiUsageStore(context) }
    private val authorizationProvider by lazy { AndroidAiBearerTokenProvider(context) }

    private lateinit var ui: AiAssistantUi
    private var snapshot: AiInputSnapshot? = null
    private var profile: AiProviderProfile? = null
    private var lastAction: AiAction? = null
    private var lastCustomInstruction: String? = null
    private var appliedEdit: AiAppliedEdit? = null
    private var renderedSuggestions: List<String> = emptyList()
    private val applyGate = AiExactlyOnceApplyGate()
    private var requestJob: Job? = null
    private var intakeJob: Job? = null
    private var replySourceOrigin: AiReplySourceOrigin? = null
    private lateinit var clipboardBarButton: ToolButton
    private var clipboardBarAvailable = false
    private var clipboardBarInteractionEnabled = true
    private var pendingCustomInstruction: String? = null

    override val title: String by lazy { context.getString(R.string.ai_assistant_title) }
    override val showTitle: Boolean = true

    override fun onCreateView(): View {
        ui = AiAssistantUi(context, theme).apply {
            onActionSelected = { action -> generate(action) }
            onCustomPromptRequested = ::openPromptKeyboard
            onSuggestionReplace = { applySuggestion(it, AiApplyMode.Replace) }
            onSuggestionAppend = { applySuggestion(it, AiApplyMode.Append) }
            onSelectedChangesApply = ::applySelectedChanges
            onUndo = ::undo
            onRetry = { lastAction?.let { generate(it, lastCustomInstruction) } }
            onClipboardSourceRequested = ::showClipboardSourcePicker
            onSetupRequested = {
                service.prepareForSettingsActivity()
                AiSettingsNavigator.openWritingSetup(context)
            }
        }
        return ui.root
    }

    override fun onCreateBarExtension(): View = LinearLayout(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        clipboardBarButton = ToolButton(context, R.drawable.ic_clipboard, theme).apply {
            contentDescription = context.getString(R.string.ai_clipboard_source_select)
            visibility = View.GONE
            setOnClickListener { showClipboardSourcePicker() }
        }
        val size = KawaiiBarComponent.HEIGHT.dp()
        addView(clipboardBarButton, LinearLayout.LayoutParams(size, size))
        renderClipboardBarButton()
    }

    override fun onAttached() {
        inputView.setAssistantContentExpanded(true)
        setClipboardBarAvailable(false)
        val allowsTextInspection = service.allowsTextInspectionFeatures()
        val allowsAiInput = service.allowsAiInputFeatures()
        // Do not even open the local credential store in an editor that is already denied by
        // privacy or network policy.
        val resolved = if (allowsTextInspection && allowsAiInput) {
            AiProviderResolver.resolve(context).profile
        } else null
        when (AiFeatureEntryGate.evaluate(
            allowsTextInspection = allowsTextInspection,
            allowsAiInput = allowsAiInput,
            hasConfiguredProfile = resolved != null
        )) {
            AiFeatureEntryGate.PrivateEditor -> {
                ui.showError(context.getString(R.string.ai_private_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.NetworkPolicyBlocked -> {
                ui.showError(context.getString(R.string.ai_network_policy_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.SetupRequired -> {
                ui.showSetupRequired(context.getString(R.string.ai_setup_required))
                return
            }
            AiFeatureEntryGate.Ready -> Unit
        }
        resolved ?: return
        profile = resolved
        ui.setIntakeAvailable(true)
        setClipboardBarAvailable(true)
        // Reuse the established capture boundary to finish any composing span before binding an
        // external source. The captured editor text is ignored when an explicit source is present.
        val capturedEditorSource = service.captureAiInputSnapshot()
        val replyTarget = captureReplyTarget()
        val shared = AiReplyIntake.consumeSharedText(allowed = replyTarget != null)
        if (shared != null && replyTarget != null) {
            adoptReplySource(shared, replyTarget)
            return
        }
        replySourceOrigin = null
        snapshot = capturedEditorSource ?: captureReplyTarget()?.let { target ->
            AiInputSnapshot(target, "", AiSourceKind.BeforeCursor)
        }
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
        consumePendingCustomInstruction()
    }

    override fun onDetached() {
        requestJob?.cancel()
        intakeJob?.cancel()
        inputView.setAssistantContentExpanded(false)
    }

    private fun openPromptKeyboard(initialText: String) {
        if (!service.allowsAiInputFeatures()) {
            ui.showError(context.getString(R.string.ai_network_policy_disabled), canRetry = false)
            return
        }
        val started = inputView.beginAiPromptInput(
            initialText = initialText,
            onSubmit = { instruction ->
                pendingCustomInstruction = instruction
                windowManager.attachWindow(this)
            },
            onCancel = {
                windowManager.attachWindow(this)
            }
        )
        if (!started) {
            ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
        }
    }

    private fun consumePendingCustomInstruction() {
        val instruction = pendingCustomInstruction ?: return
        pendingCustomInstruction = null
        generate(AiAction.Custom, instruction)
    }

    private fun generate(action: AiAction, customInstruction: String? = null) {
        val source = snapshot ?: return
        val provider = profile ?: return
        if (!validateCurrentSource(source)) return
        requestJob?.cancel()
        lastAction = action
        lastCustomInstruction = customInstruction
        renderedSuggestions = emptyList()
        applyGate.resetForReviewedResult()
        ui.showLoading(action, provider.displayName)
        setClipboardBarInteractionEnabled(false)
        requestJob = service.lifecycleScope.launch {
            try {
                val result = OpenAiResponsesClient(
                    provider,
                    authorizationProvider = authorizationProvider
                ).generate(action, source.source, customInstruction)
                usageStore.recordSuccess(
                    action,
                    provider.kind,
                    result.model,
                    result.inputCharacters,
                    result.outputCharacters
                )
                renderedSuggestions = result.suggestions
                    .filter(String::isNotBlank)
                    .take(action.maxSuggestions.coerceIn(1, 3))
                ui.showResults(action, provider.displayName, source.source, renderedSuggestions)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                usageStore.recordFailure(
                    action,
                    provider.kind,
                    provider.model(action.tier),
                    source.source.length
                )
                when (exception) {
                    is AiReauthenticationRequiredException -> ui.showSetupRequired(
                        context.getString(R.string.ai_oauth_reauth_required)
                    )
                    is AiApiKeyRejectedException -> ui.showSetupRequired(
                        context.getString(R.string.ai_api_key_rejected)
                    )
                    is AiSuggestionContractException -> ui.showError(
                        context.getString(R.string.ai_suggestion_contract_failed),
                        provider.displayName,
                        canRetry = true
                    )
                    is AiResponseTooLargeException -> ui.showError(
                        context.getString(R.string.ai_response_too_large),
                        provider.displayName,
                        canRetry = true
                    )
                    is AiResponseRefusedException -> ui.showError(
                        context.getString(R.string.ai_response_refused),
                        provider.displayName,
                        canRetry = true
                    )
                    is AiIncompleteResponseException -> ui.showError(
                        context.getString(
                            when (exception.reason) {
                                AiIncompleteReason.OutputLimit -> R.string.ai_response_output_limit
                                AiIncompleteReason.ContentFilter -> R.string.ai_response_content_filter
                                AiIncompleteReason.Unknown -> R.string.ai_response_incomplete
                            }
                        ),
                        provider.displayName,
                        canRetry = true
                    )
                    else -> ui.showError(
                        exception.message ?: context.getString(R.string.ai_apply_failed),
                        provider.displayName,
                        canRetry = true
                    )
                }
            } finally {
                setClipboardBarInteractionEnabled(true)
            }
        }
    }

    private fun applySuggestion(suggestion: String, mode: AiApplyMode) {
        if (suggestion !in renderedSuggestions) return
        applyReviewedText(suggestion, mode)
    }

    private fun applySelectedChanges(patch: AiTextPatch, selectedChangeIds: Set<Int>) {
        val source = snapshot ?: return
        val partiallyPatched = AiPartialApplyGate.resolve(
            source.source,
            renderedSuggestions,
            patch,
            selectedChangeIds
        ) ?: return
        applyReviewedText(partiallyPatched, AiApplyMode.Replace)
    }

    private fun applyReviewedText(text: String, mode: AiApplyMode) {
        val source = snapshot ?: return
        if (!applyGate.claim()) return
        val edit = if (replySourceOrigin == null) {
            service.applyAiSuggestion(source, text, mode)
        } else {
            insertExternalReply(source, text)
        }
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
        ui.setIntakeInteractionEnabled(false)
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
        applyGate.resetAfterUndo()
        val action = lastAction
        val source = snapshot
        val provider = profile
        if (action != null && source != null && provider != null && renderedSuggestions.isNotEmpty()) {
            ui.showResults(action, provider.displayName, source.source, renderedSuggestions)
        }
        ui.setUndoAvailable(false)
        ui.setIntakeInteractionEnabled(true)
    }

    private fun showClipboardSourcePicker() {
        if (!service.allowsTextInspectionFeatures()) {
            ui.showError(context.getString(R.string.ai_private_disabled), canRetry = false)
            return
        }
        if (!service.allowsAiInputFeatures()) {
            ui.showError(context.getString(R.string.ai_network_policy_disabled), canRetry = false)
            return
        }
        val target = captureReplyTarget()
        if (target == null) {
            ui.showError(context.getString(R.string.ai_reply_cursor_required), canRetry = false)
            return
        }
        intakeJob?.cancel()
        ui.setIntakeInteractionEnabled(false)
        setClipboardBarInteractionEnabled(false)
        intakeJob = service.lifecycleScope.launch {
            try {
                val entries = ClipboardManager.searchableEntries(AiClipboardIntakePolicy.MAX_CHOICES)
                if (!service.allowsAiInputFeatures() || !matchesCurrentEditor(target)) {
                    ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
                    return@launch
                }
                val choices = AiClipboardIntakePolicy.choices(entries.map { entry ->
                    AiClipboardCandidate(
                        id = entry.id,
                        text = entry.text,
                        sensitive = entry.sensitive,
                        deleted = entry.deleted
                    )
                })
                if (choices.isEmpty()) {
                    Toast.makeText(context, R.string.ai_clipboard_source_empty, Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                }
                val labels = choices.map { choice ->
                    choice.text
                        .replace(Regex("\\s+"), " ")
                        .take(CLIPBOARD_LABEL_CHARACTERS)
                }.toTypedArray()
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.ai_clipboard_source_title)
                    .setItems(labels) { _, index ->
                        val source = AiClipboardIntakePolicy.select(
                            choices = choices,
                            selectedId = choices[index].id,
                            allowed = service.allowsAiInputFeatures() &&
                                matchesCurrentEditor(target)
                        )
                        if (source == null) {
                            ui.showError(
                                context.getString(R.string.ai_editor_changed),
                                canRetry = false
                            )
                        } else {
                            adoptReplySource(source, target)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                service.showDialog(dialog)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ui.showError(context.getString(R.string.ai_clipboard_source_failed), canRetry = false)
            } finally {
                ui.setIntakeInteractionEnabled(true)
                setClipboardBarInteractionEnabled(true)
            }
        }
    }

    private fun adoptReplySource(source: AiReplySource, target: AiEditorTarget) {
        if (!service.allowsAiInputFeatures() || !matchesCurrentEditor(target)) {
            ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
            return
        }
        val bound = AiReplySourcePolicy.bindToEditor(source, target)
        if (bound == null) {
            ui.showError(context.getString(R.string.ai_reply_cursor_required), canRetry = false)
            return
        }
        requestJob?.cancel()
        snapshot = bound
        replySourceOrigin = source.origin
        lastAction = null
        lastCustomInstruction = null
        renderedSuggestions = emptyList()
        applyGate.resetForReviewedResult()
        appliedEdit = null
        ui.setUndoAvailable(false)
        ui.showSourcePreview(bound.source, profile?.displayName, source.origin)
    }

    /** Inserts a reviewed reply at the bound cursor; source context is never target text to replace. */
    private fun insertExternalReply(source: AiInputSnapshot, text: String): AiAppliedEdit? {
        if (!service.allowsAiInputFeatures() || text.isBlank()) return null
        if (!matchesCurrentEditor(source.editor)) return null
        if (source.editor.selectionStart != source.editor.selectionEnd) return null
        if (!service.commitText(text)) return null
        val end = source.editor.selectionStart + text.length
        return AiAppliedEdit(
            editor = source.editor.copy(selectionStart = end, selectionEnd = end),
            inserted = text,
            restore = ""
        )
    }

    private fun validateCurrentSource(source: AiInputSnapshot): Boolean {
        val error = when {
            !service.allowsTextInspectionFeatures() -> R.string.ai_private_disabled
            !service.allowsAiInputFeatures() -> R.string.ai_network_policy_disabled
            !matchesCurrentEditor(source.editor) -> R.string.ai_editor_changed
            else -> return true
        }
        ui.showError(context.getString(error), profile?.displayName, canRetry = false)
        return false
    }

    private fun captureReplyTarget(): AiEditorTarget? {
        val selection = service.currentInputSelection
        if (selection.start < 0 || selection.start != selection.end) return null
        val info = service.currentInputEditorInfo
        return AiEditorTarget(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            selectionStart = selection.start,
            selectionEnd = selection.end
        )
    }

    private fun matchesCurrentEditor(target: AiEditorTarget): Boolean =
        service.matchesCurrentEditor(
            target.packageName,
            target.fieldId,
            target.inputType,
            target.selectionStart,
            target.selectionEnd
        )

    private fun setClipboardBarAvailable(available: Boolean) {
        clipboardBarAvailable = available
        renderClipboardBarButton()
    }

    private fun setClipboardBarInteractionEnabled(enabled: Boolean) {
        clipboardBarInteractionEnabled = enabled
        renderClipboardBarButton()
    }

    private fun renderClipboardBarButton() {
        if (!::clipboardBarButton.isInitialized) return
        clipboardBarButton.visibility = if (clipboardBarAvailable) View.VISIBLE else View.GONE
        clipboardBarButton.isEnabled = clipboardBarAvailable && clipboardBarInteractionEnabled
        clipboardBarButton.alpha = if (clipboardBarButton.isEnabled) 1f else 0.45f
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val CLIPBOARD_LABEL_CHARACTERS = 80
    }
}
