/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import timber.log.Timber

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
    private var pendingCustomRequest: AiPendingCustomRequest? = null
    private var promptReturnRequest = 0L
    private var clipboardCandidates: List<AiClipboardCandidate> = emptyList()
    private var clipboardTarget: AiEditorTarget? = null

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
            onClipboardSourceSelected = ::selectClipboardSource
            onClipboardSourceSelectionCancelled = ::cancelClipboardSourcePicker
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
        // Source review needs enough height to expose the full action catalog. Other states
        // keep their compact or purpose-built layout below.
        inputView.setAssistantContentExpanded(false)
        setClipboardBarAvailable(false)
        val allowsTextInspection = service.allowsTextInspectionFeatures()
        val allowsAiInput = service.allowsAiInputFeatures()
        // Do not even open the local credential store in an editor that is already denied by
        // privacy or network policy.
        val resolved = if (allowsTextInspection && allowsAiInput) {
            // The bridge is inert in release builds and unarmed for ordinary debug use. It lets
            // the debug-only E2E host exercise this exact window and its Replace callback without
            // configuring a provider, storing a key, or making a network request.
            AiDebugE2eBridge.profileForArmedRequest() ?: AiProviderResolver.resolve(context).profile
        } else null
        // A structurally valid OAuth profile without its encrypted AppAuth session cannot make a
        // request. Surface the existing reauthentication CTA before the user spends time typing
        // a prompt, while API-key profiles remain immediately usable.
        val profileReady = resolved?.let { candidate ->
            AiProviderReadinessPolicy.isReady(
                candidate,
                hasOAuthSession = candidate.authMode != AiAuthMode.OAuthPkce ||
                    AiOAuthSessionStore(context).hasSession(candidate)
            )
        } == true
        when (AiFeatureEntryGate.evaluate(
            allowsTextInspection = allowsTextInspection,
            allowsAiInput = allowsAiInput,
            hasConfiguredProfile = profileReady
        )) {
            AiFeatureEntryGate.PrivateEditor -> {
                pendingCustomRequest = null
                ui.showError(context.getString(R.string.ai_private_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.NetworkPolicyBlocked -> {
                pendingCustomRequest = null
                ui.showError(context.getString(R.string.ai_network_policy_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.SetupRequired -> {
                pendingCustomRequest = null
                ui.showSetupRequired(context.getString(
                    if (resolved?.authMode == AiAuthMode.OAuthPkce) {
                        R.string.ai_oauth_reauth_required
                    } else {
                        R.string.ai_setup_required
                    }
                ))
                return
            }
            AiFeatureEntryGate.Ready -> Unit
        }
        if (resolved == null) {
            pendingCustomRequest = null
            return
        }
        profile = resolved
        ui.setIntakeAvailable(true)
        setClipboardBarAvailable(true)
        // A direct request is bound to the exact preview the user saw before the prompt opened.
        // Reattaching this window must never silently recapture a newer editor value and send it
        // without another review step.
        pendingCustomRequest?.let { pending ->
            pendingCustomRequest = null
            val resumed = AiDirectPromptContinuation.resumeIfSnapshotCurrent(pending) {
                service.isAiSnapshotCurrent(it)
            } ?: run {
                ui.showError(
                    context.getString(R.string.ai_editor_changed),
                    profile?.displayName,
                    canRetry = false
                )
                return
            }
            snapshot = resumed.snapshot
            replySourceOrigin = resumed.replySourceOrigin
            inputView.setAssistantContentExpanded(true)
            generate(AiAction.Custom, resumed.instruction)
            return
        }
        // Reuse the established capture boundary to finish any composing span before binding an
        // external source. The captured editor text is ignored when an explicit source is present.
        val capturedEditorSource = when (val capture = service.captureAiInputSnapshot()) {
            is AiInputCaptureResult.Captured -> capture.snapshot
            AiInputCaptureResult.SelectionTooLarge -> {
                ui.showError(
                    context.getString(R.string.ai_source_too_large),
                    resolved.displayName,
                    canRetry = false
                )
                return
            }
            AiInputCaptureResult.EditorStateChanged -> {
                ui.showError(
                    context.getString(R.string.ai_editor_changed),
                    resolved.displayName,
                    canRetry = false
                )
                return
            }
            AiInputCaptureResult.NoText -> null
        }
        val replyTarget = captureReplyTarget()
        val shared = AiReplyIntake.consumeSharedText(allowed = replyTarget != null)
        if (shared != null && replyTarget != null) {
            adoptReplySource(shared, replyTarget)
            return
        }
        replySourceOrigin = null
        snapshot = capturedEditorSource ?: captureReplyTarget()?.let { target ->
            AiInputSnapshot(
                editor = target,
                source = "",
                sourceKind = AiSourceKind.BeforeCursor,
                scope = AiSourceScope.CursorContext
            )
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
        inputView.setAssistantContentExpanded(true)
        ui.showSourcePreview(
            source = captured.source,
            providerLabel = resolved.displayName,
            origin = replySourceOrigin,
            scope = captured.scope
        )
    }

    override fun onDetached() {
        promptReturnRequest += 1
        requestJob?.cancel()
        intakeJob?.cancel()
        clearClipboardSourcePicker()
        inputView.setAssistantContentExpanded(false)
    }

    private fun openPromptKeyboard(initialText: String) {
        if (!service.allowsAiInputFeatures()) {
            ui.showError(context.getString(R.string.ai_network_policy_disabled), canRetry = false)
            return
        }
        val source = snapshot ?: run {
            ui.showError(context.getString(R.string.ai_no_source), profile?.displayName, canRetry = false)
            return
        }
        // The capture start marker is asynchronous. Starting a second prompt before the first
        // one owns it would overwrite InputView's callbacks while the first capture stays live.
        if (service.isInternalPromptInputOwned) return
        val started = inputView.beginAiPromptInput(
            initialText = initialText,
            contextLabel = context.getString(
                AiDirectPromptContext.labelRes(replySourceOrigin, source.scope)
            ),
            onSubmit = { instruction ->
                pendingCustomRequest = AiDirectPromptContinuation.bind(
                    instruction = instruction,
                    snapshot = source,
                    replySourceOrigin = replySourceOrigin
                )
                windowManager.attachWindow(this)
            },
            onCancel = {
                pendingCustomRequest = null
                reattachAfterPromptDrain()
            }
        )
        if (!started) {
            ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
        }
    }

    /** Reopens source review only after the internal prompt input releases its capture gate. */
    private fun reattachAfterPromptDrain() {
        val request = ++promptReturnRequest
        fun reattachWhenSettled() {
            if (request != promptReturnRequest) return
            if (service.isInternalPromptInputOwned) {
                inputView.postDelayed({ reattachWhenSettled() }, PROMPT_DRAIN_POLL_MILLIS)
                return
            }
            // Cancellation may finish after the user intentionally switched to another tool or
            // editor. Never pull the AI surface back over that newer choice.
            if (!isPromptKeyboardActive() || snapshot?.let { !matchesCurrentEditorSession(it.editor) } == true) {
                return
            }
            if (!windowManager.isAttached(this)) {
                windowManager.attachWindow(this)
            }
        }
        inputView.post { reattachWhenSettled() }
    }

    private fun generate(action: AiAction, customInstruction: String? = null) {
        // beginInternalPromptCapture becomes active asynchronously. Reject a competing action
        // during that hand-off instead of letting it send editor content while prompt input owns
        // the keyboard transition.
        if (service.isInternalPromptInputOwned) return
        val source = snapshot ?: return
        val provider = profile ?: return
        if (!validateCurrentSource(source)) return
        requestJob?.cancel()
        lastAction = action
        lastCustomInstruction = customInstruction
        renderedSuggestions = emptyList()
        applyGate.resetForReviewedResult()
        inputView.setAssistantContentExpanded(true)
        ui.showLoading(action, provider.displayName)
        setClipboardBarInteractionEnabled(false)
        requestJob = service.lifecycleScope.launch {
            try {
                val debugResponse = AiDebugE2eBridge.consumeIfArmed(provider, action, source.source)
                // A debug-only, already-local result may deliberately suspend here so headed E2E
                // can inspect this production loading UI. The responder is consumed before the
                // delay, so cancelling the window cannot leak an armed fake response to a later
                // request. In release builds debugResponse is always null and this is a no-op.
                if (debugResponse != null && debugResponse.delayMillis > 0L) {
                    delay(debugResponse.delayMillis)
                }
                val result = debugResponse?.result ?: OpenAiResponsesClient(
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
        val result = if (replySourceOrigin == null) {
            service.applyAiSuggestion(source, text, mode)
        } else {
            insertExternalReply(source, text)
        }
        when (result) {
            is AiSuggestionApplyResult.Applied -> {
                appliedEdit = result.edit
                ui.setUndoAvailable(true)
                ui.setIntakeInteractionEnabled(false)
            }
            AiSuggestionApplyResult.EditorChanged -> ui.showError(
                context.getString(R.string.ai_editor_changed),
                profile?.displayName,
                canRetry = false
            )
            AiSuggestionApplyResult.NotApplied -> ui.showError(
                context.getString(R.string.ai_apply_failed),
                profile?.displayName,
                canRetry = false
            )
        }
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
                if (!windowManager.isAttached(this@AiAssistantWindow)) {
                    return@launch
                }
                if (!service.allowsAiInputFeatures() || !matchesCurrentEditor(target)) {
                    ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
                    return@launch
                }
                val candidates = entries.map { entry ->
                    AiClipboardCandidate(
                        id = entry.id,
                        text = entry.text,
                        sensitive = entry.sensitive,
                        deleted = entry.deleted
                    )
                }
                val items = AiClipboardPickerPresentation.items(candidates)
                if (items.isEmpty()) {
                    Toast.makeText(context, R.string.ai_clipboard_source_empty, Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                }
                clipboardCandidates = AiClipboardIntakePolicy.choices(candidates)
                clipboardTarget = target
                inputView.setAssistantContentExpanded(true)
                ui.showClipboardSourceChoices(items)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Never include a clipboard value in logs. The UI must not mistake an IME window
                // lifecycle race for a database read failure, so only report a read failure while
                // this assistant window remains current.
                Timber.w("AI clipboard source read failed: ${exception.javaClass.simpleName}")
                if (windowManager.isAttached(this@AiAssistantWindow)) {
                    ui.showError(context.getString(R.string.ai_clipboard_source_failed), canRetry = false)
                }
            } finally {
                ui.setIntakeInteractionEnabled(true)
                setClipboardBarInteractionEnabled(true)
            }
        }
    }

    private fun selectClipboardSource(selectedId: Int) {
        val target = clipboardTarget
        if (target == null) {
            clearClipboardSourcePicker()
            if (windowManager.isAttached(this)) {
                ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
            }
            return
        }
        val source = AiClipboardIntakePolicy.select(
            choices = clipboardCandidates,
            selectedId = selectedId,
            allowed = service.allowsAiInputFeatures() &&
                windowManager.isAttached(this) &&
                matchesCurrentEditor(target)
        )
        clearClipboardSourcePicker()
        if (source == null) {
            if (windowManager.isAttached(this)) {
                ui.showError(context.getString(R.string.ai_editor_changed), canRetry = false)
            }
            return
        }
        adoptReplySource(source, target)
    }

    private fun cancelClipboardSourcePicker() {
        clearClipboardSourcePicker()
        if (!windowManager.isAttached(this)) return
        val current = snapshot ?: return
        inputView.setAssistantContentExpanded(true)
        ui.showSourcePreview(
            source = current.source,
            providerLabel = profile?.displayName,
            origin = replySourceOrigin,
            scope = current.scope
        )
    }

    private fun clearClipboardSourcePicker() {
        clipboardCandidates = emptyList()
        clipboardTarget = null
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
        inputView.setAssistantContentExpanded(true)
        ui.showSourcePreview(
            source = bound.source,
            providerLabel = profile?.displayName,
            origin = source.origin,
            scope = bound.scope
        )
    }

    /** Inserts a reviewed reply at the bound cursor; source context is never target text to replace. */
    private fun insertExternalReply(source: AiInputSnapshot, text: String): AiSuggestionApplyResult =
        service.applyAiReplyAtCursor(source, text)

    private fun validateCurrentSource(source: AiInputSnapshot): Boolean {
        val error = when {
            !service.allowsTextInspectionFeatures() -> R.string.ai_private_disabled
            !service.allowsAiInputFeatures() -> R.string.ai_network_policy_disabled
            !service.isAiSnapshotCurrent(source) -> R.string.ai_editor_changed
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
            selectionEnd = selection.end,
            inputSessionEpoch = service.currentInputSessionEpoch
        )
    }

    private fun matchesCurrentEditor(target: AiEditorTarget): Boolean =
        service.matchesCurrentEditor(
            target.packageName,
            target.fieldId,
            target.inputType,
            target.selectionStart,
            target.selectionEnd,
            expectedInputSessionEpoch = target.inputSessionEpoch
        )

    /** Prompt cancellation may update cursor tracking, but it must not cross an editor session. */
    private fun matchesCurrentEditorSession(target: AiEditorTarget): Boolean {
        val info = service.currentInputEditorInfo
        return info.packageName == target.packageName &&
            info.fieldId == target.fieldId &&
            info.inputType == target.inputType &&
            service.currentInputSessionEpoch == target.inputSessionEpoch
    }

    private fun isPromptKeyboardActive(): Boolean = windowManager.isAttached(
        windowManager.getEssentialWindow(KeyboardWindow)
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
        const val PROMPT_DRAIN_POLL_MILLIS = 16L
    }

}
