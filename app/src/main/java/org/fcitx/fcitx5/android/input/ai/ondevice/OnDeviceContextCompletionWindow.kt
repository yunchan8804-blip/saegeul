/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiInputCaptureResult
import org.fcitx.fcitx5.android.input.ai.AiInputSnapshot
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.panel.PanelButtonKind
import org.fcitx.fcitx5.android.input.panel.PanelStyle
import org.fcitx.fcitx5.android.input.panel.panelButton
import org.fcitx.fcitx5.android.input.panel.panelColumn
import org.fcitx.fcitx5.android.input.panel.panelScrollRoot
import org.fcitx.fcitx5.android.input.panel.panelSurface
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaModelActivity
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class OnDeviceContextCompletionWindow :
    InputWindow.ExtendedInputWindow<OnDeviceContextCompletionWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val gate = OnDeviceContextCompletionGate()
    private val runtime by lazy { OnDeviceContextCompletionRuntime(context) }
    private lateinit var ui: OnDeviceContextCompletionUi
    private var snapshot: AiInputSnapshot? = null
    private var ticket: OnDeviceContextCompletionGate.Ticket? = null
    private var requestJob: Job? = null
    private var currentCheckJob: Job? = null
    private var installStateJob: Job? = null
    private var invalidated = false
    private var finishedMessage: String? = null
    private var detached = false
    private val invalidationListener: () -> Unit = {
        if (snapshot != null || ticket != null) invalidateForEditorChange()
    }

    override val title: String by lazy { context.getString(R.string.gemma_context_title) }
    override val showTitle: Boolean = true

    override fun onCreateView(): View {
        ui = OnDeviceContextCompletionUi(context, theme).apply {
            onComplete = ::requestCompletion
            onCancel = ::cancelCompletion
            onApply = ::applyCompletion
            onInstall = ::openModelInstall
            onBack = { windowManager.attachWindow(KeyboardWindow) }
            onAutomaticSuggestionsEnabledChanged = ::setAutomaticSuggestionsEnabled
            onAutomaticSuggestionsUseGpuChanged = ::setAutomaticSuggestionsUseGpu
        }
        return ui.root
    }

    override fun onAttached() {
        detached = false
        service.setOnDeviceContextSnapshotInvalidationListener(invalidationListener)
        ui.updateAutomaticSuggestions(
            supported = service.automaticSuggestionsSupported,
            enabled = service.automaticSuggestionsEnabled,
            useGpu = service.automaticSuggestionsUseGpu,
            fallbackOccurred = service.automaticSuggestionBackendFallbackOccurred
        )
        observeInstallState()
    }

    override fun onDetached() {
        detached = true
        installStateJob?.cancel()
        installStateJob = null
        invalidateRunningCompletion(null, null)
        service.setOnDeviceContextSnapshotInvalidationListener(null)
        snapshot = null
        ui.clearSensitiveContent()
    }

    /**
     * Live-follows [GemmaModelInstaller.state] so the "설치하면 이어쓰기를 쓸 수 있어요" prompt's
     * download percentage updates while this window stays open, and switches to the normal
     * preview/complete flow the moment the model becomes [GemmaInstallState.Installed].
     */
    private fun observeInstallState() {
        installStateJob?.cancel()
        installStateJob = service.lifecycleScope.launch {
            GemmaModelInstaller.state(context).collect { state ->
                if (detached) return@collect
                if (state is GemmaInstallState.Installed) {
                    if (snapshot == null && ticket == null) capturePreview()
                } else {
                    invalidateRunningCompletion(null, null)
                    snapshot = null
                    val uiState = GemmaInstallUiState.from(state)
                    ui.showInstallPrompt(
                        promptTitle = context.getString(R.string.gemma_context_install_prompt_title),
                        detail = context.getString(uiState.titleRes) + (uiState.detailRes?.let {
                            " · " + context.getString(it, *uiState.detailArgs.toTypedArray())
                        } ?: ""),
                        buttonText = context.getString(R.string.gemma_context_install_button)
                    )
                }
            }
        }
    }

    private fun openModelInstall() {
        context.startActivity(
            Intent(context, GemmaModelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun capturePreview() {
        if (!service.allowsOnDeviceContextCompletionFeatures()) {
            ui.showMessage(
                context.getString(
                    if (service.allowsTextInspectionFeatures()) {
                        R.string.gemma_context_ai_blocked
                    } else {
                        R.string.gemma_context_private_disabled
                    }
                ),
                isError = true
            )
            return
        }
        when (val capture = service.captureOnDeviceContextSnapshot()) {
            is AiInputCaptureResult.Captured -> {
                snapshot = capture.snapshot
                ui.showPreview(capture.snapshot.source)
            }
            AiInputCaptureResult.EditorStateChanged ->
                ui.showMessage(context.getString(R.string.gemma_context_cursor_required))
            AiInputCaptureResult.SelectionTooLarge ->
                ui.showMessage(context.getString(R.string.gemma_context_too_long))
            AiInputCaptureResult.NoText ->
                ui.showMessage(context.getString(R.string.gemma_context_no_text))
        }
    }

    private fun requestCompletion() {
        val captured = snapshot ?: return
        if (!OnDeviceContextCompletionPolicy.isIncompleteContext(captured.source)) {
            ui.showMessage(
                context.getString(
                    if (isTerminalContext(captured.source)) {
                        R.string.gemma_context_terminal
                    } else {
                        R.string.gemma_context_unsupported_context
                    }
                )
            )
            snapshot = null
            return
        }
        if (!runtime.supported) {
            ui.showMessage(context.getString(R.string.gemma_context_unsupported), isError = true)
            return
        }
        if (!service.isOnDeviceContextSnapshotCurrent(captured)) {
            invalidateForEditorChange()
            return
        }
        val gateSnapshot = gateSnapshot(captured)
        val nextTicket = gate.begin(gateSnapshot) ?: run {
            ui.showMessage(context.getString(R.string.gemma_context_cancel_draining))
            return
        }
        ticket = nextTicket
        invalidated = false
        ui.showRunning()
        requestJob = service.lifecycleScope.launch {
            runCompletion(nextTicket, captured, gateSnapshot)
        }
        currentCheckJob = service.lifecycleScope.launch {
            while (ticket === nextTicket && !detached) {
                delay(CURRENT_CHECK_MILLIS)
                if (runtime.isRunning && !invalidated && isCurrentWindow()) {
                    ui.showRunning(runtime.isInferring)
                }
                if (!service.isOnDeviceContextSnapshotCurrent(captured)) {
                    invalidateForEditorChange()
                    return@launch
                }
            }
        }
    }

    private suspend fun runCompletion(
        activeTicket: OnDeviceContextCompletionGate.Ticket,
        captured: AiInputSnapshot,
        gateSnapshot: OnDeviceContextCompletionGate.Snapshot
    ) {
        var publishedResult: OnDeviceContextCompletionResult? = null
        try {
            val result = runtime.complete(captured.source)
            if (!result.nativeStopped || !service.isOnDeviceContextSnapshotCurrent(captured) ||
                !gate.publish(activeTicket, gateSnapshot, result.suffix)
            ) {
                gate.invalidate(activeTicket)
                invalidated = true
            } else {
                publishedResult = result
            }
        } catch (_: CancellationException) {
            gate.invalidate(activeTicket)
        } catch (exception: Throwable) {
            gate.invalidate(activeTicket)
            invalidated = true
            finishedMessage = completionFailureMessage(exception.message)
            if (isCurrentWindow()) {
                ui.showMessage(checkNotNull(finishedMessage), isError = true)
            }
        } finally {
            val nativeStopped = awaitNativeStopped(activeTicket)
            if (publishedResult != null && nativeStopped && !invalidated && ticket === activeTicket &&
                isCurrentWindow() && service.isOnDeviceContextSnapshotCurrent(captured)
            ) {
                ui.showCandidate(captured.source, publishedResult.suffix, publishedResult)
            } else if (ticket === activeTicket && !detached && isCurrentWindow()) {
                ui.showMessage(
                    finishedMessage ?: context.getString(R.string.gemma_context_cancelled),
                    isError = invalidated
                )
                ticket = null
                snapshot = null
            }
        }
    }

    private fun awaitNativeStopped(activeTicket: OnDeviceContextCompletionGate.Ticket): Boolean {
        if (runtime.isRunning) {
            gate.invalidate(activeTicket)
            invalidated = true
            finishedMessage = context.getString(R.string.gemma_context_timed_out)
            snapshot = null
            return false
        }
        return gate.nativeStopped(activeTicket)
    }

    private fun applyCompletion() {
        val activeTicket = ticket ?: return
        val captured = snapshot ?: run {
            invalidateForEditorChange()
            return
        }
        if (!service.isOnDeviceContextSnapshotCurrent(captured)) {
            invalidateForEditorChange()
            return
        }
        val suffix = gate.takeForApply(activeTicket, gateSnapshot(captured)) ?: run {
            invalidateForEditorChange()
            return
        }
        ticket = null
        currentCheckJob?.cancel()
        currentCheckJob = null
        snapshot = null
        when (service.applyOnDeviceContextCompletion(captured, suffix)) {
            is AiSuggestionApplyResult.Applied -> ui.showMessage(
                context.getString(R.string.gemma_context_applied)
            )
            AiSuggestionApplyResult.EditorChanged -> ui.showMessage(
                context.getString(R.string.gemma_context_editor_changed),
                isError = true
            )
            AiSuggestionApplyResult.NotApplied -> ui.showMessage(
                context.getString(R.string.gemma_context_apply_failed),
                isError = true
            )
        }
    }

    private fun cancelCompletion() {
        invalidateRunningCompletion(
            visibleMessage = context.getString(R.string.gemma_context_cancel_draining),
            finalMessage = context.getString(R.string.gemma_context_cancelled)
        )
    }

    private fun setAutomaticSuggestionsEnabled(enabled: Boolean) {
        AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.setValue(enabled)
        service.setAutomaticSuggestionsEnabled(enabled)
        ui.updateAutomaticSuggestions(
            supported = service.automaticSuggestionsSupported,
            enabled = service.automaticSuggestionsEnabled,
            useGpu = service.automaticSuggestionsUseGpu,
            fallbackOccurred = service.automaticSuggestionBackendFallbackOccurred
        )
    }

    private fun setAutomaticSuggestionsUseGpu(useGpu: Boolean) {
        service.setAutomaticSuggestionsUseGpu(useGpu)
        ui.updateAutomaticSuggestions(
            supported = service.automaticSuggestionsSupported,
            enabled = service.automaticSuggestionsEnabled,
            useGpu = service.automaticSuggestionsUseGpu,
            fallbackOccurred = service.automaticSuggestionBackendFallbackOccurred
        )
    }

    private fun invalidateForEditorChange() {
        val message = context.getString(R.string.gemma_context_editor_changed)
        invalidateRunningCompletion(message, message)
    }

    private fun invalidateRunningCompletion(visibleMessage: String?, finalMessage: String?) {
        val activeTicket = ticket
        snapshot = null
        currentCheckJob?.cancel()
        currentCheckJob = null
        if (activeTicket == null) {
            if (visibleMessage != null && !detached && ::ui.isInitialized) {
                ui.showMessage(visibleMessage, isError = true)
            }
            return
        }
        invalidated = true
        finishedMessage = finalMessage
        gate.invalidate(activeTicket)
        runtime.cancel()
        if (visibleMessage != null && !detached && ::ui.isInitialized) {
            ui.showMessage(visibleMessage, isError = false)
        }
        requestJob?.cancel()
    }

    private fun completionFailureMessage(code: String?): String = when {
        code == "MODEL_MISSING" -> context.getString(R.string.gemma_context_model_missing)
        code == "INVALID_COMPLETION" -> context.getString(R.string.gemma_context_no_candidate)
        code == "GENERATION_TIMEOUT" || code == "NATIVE_STOP_TIMEOUT" ->
            context.getString(R.string.gemma_context_timed_out)
        code?.startsWith("RESOURCE_") == true ->
            context.getString(R.string.gemma_context_resource_limited)
        code == "CANCELLED" -> context.getString(R.string.gemma_context_cancelled)
        else -> context.getString(R.string.gemma_context_failed)
    }

    private fun gateSnapshot(snapshot: AiInputSnapshot) = OnDeviceContextCompletionGate.Snapshot(
        editorSessionId = snapshot.editor.inputSessionEpoch,
        text = snapshot.source,
        selectionStart = snapshot.source.length,
        selectionEnd = snapshot.source.length
    )

    private fun isCurrentWindow(): Boolean = !detached && windowManager.isAttached(this)

    private fun isTerminalContext(text: String): Boolean =
        text.trimEnd().lastOrNull() in TERMINAL_PUNCTUATION

    private companion object {
        const val CURRENT_CHECK_MILLIS = 100L
        val TERMINAL_PUNCTUATION = setOf('.', '?', '!', '。', '！', '？')
    }
}

private class OnDeviceContextCompletionUi(
    private val context: Context,
    private val theme: Theme
) {
    private val column = context.panelColumn()
    val root = context.panelScrollRoot(theme, column)
    private val sourceLabel = TextView(context).apply {
        text = context.getString(R.string.gemma_context_source_label)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
    }
    private val source = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        typeface = Typeface.DEFAULT_BOLD
        setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP))
        background = context.panelSurface(theme.altKeyBackgroundColor)
    }
    private val candidateLabel = TextView(context).apply {
        text = context.getString(R.string.gemma_context_candidate_label)
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        visibility = View.GONE
    }
    private val candidate = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_EMPHASIS
        typeface = Typeface.DEFAULT_BOLD
        setPadding(context.dp(PanelStyle.CARD_PADDING_H_DP))
        background = context.panelSurface(theme.altKeyBackgroundColor)
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = PanelStyle.TEXT_BODY
        gravity = Gravity.CENTER_VERTICAL
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val timing = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = PanelStyle.TEXT_CAPTION
        visibility = View.GONE
    }
    private val automaticSuggestionsSwitch = Switch(context).apply {
        text = context.getString(R.string.gemma_automatic_enable)
        textSize = 16f
        showText = false
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        setOnCheckedChangeListener { _, enabled ->
            if (!updatingAutomaticSuggestions) onAutomaticSuggestionsEnabledChanged?.invoke(enabled)
        }
    }
    private val automaticSuggestionsDescription = TextView(context).apply {
        text = context.getString(R.string.gemma_automatic_enable_description)
        textSize = 16f
        setTextColor(theme.altKeyTextColor)
    }
    private val cpuCompatibilitySwitch = Switch(context).apply {
        text = context.getString(R.string.gemma_automatic_cpu_compatibility)
        textSize = 16f
        showText = false
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        setOnCheckedChangeListener { _, useCpu ->
            if (!updatingAutomaticSuggestions) onAutomaticSuggestionsUseGpuChanged?.invoke(!useCpu)
        }
    }
    private val cpuCompatibilityDescription = TextView(context).apply {
        text = context.getString(R.string.gemma_automatic_cpu_compatibility_description)
        textSize = 16f
        setTextColor(theme.altKeyTextColor)
    }
    private val backendFallbackNotice = TextView(context).apply {
        text = context.getString(R.string.gemma_automatic_backend_fallback_notice)
        textSize = 16f
        setTextColor(theme.altKeyTextColor)
        visibility = View.GONE
    }
    private val automaticSuggestionsControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(automaticSuggestionsSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        ))
        addView(automaticSuggestionsDescription, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        addView(cpuCompatibilitySwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(PanelStyle.BUTTON_HEIGHT_DP)
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        addView(cpuCompatibilityDescription, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        addView(backendFallbackNotice, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
    }
    private val completeButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        text = context.getString(R.string.gemma_context_complete)
        contentDescription = text
        setOnClickListener { onComplete?.invoke() }
    }
    private val cancelButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        text = context.getString(R.string.gemma_context_cancel)
        contentDescription = text
        visibility = View.GONE
        setOnClickListener { onCancel?.invoke() }
    }
    private val applyButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        text = context.getString(R.string.gemma_context_apply)
        contentDescription = text
        visibility = View.GONE
        setOnClickListener { onApply?.invoke() }
    }
    private val installButton = context.panelButton(theme, PanelButtonKind.Primary).apply {
        visibility = View.GONE
        setOnClickListener { onInstall?.invoke() }
    }
    private val backButton = context.panelButton(theme, PanelButtonKind.Secondary).apply {
        text = context.getString(R.string.gemma_context_back)
        setOnClickListener { onBack?.invoke() }
    }
    private val actions = LinearLayout(context).apply {
        gravity = Gravity.END
    }

    var onComplete: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onApply: (() -> Unit)? = null
    var onInstall: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onAutomaticSuggestionsEnabledChanged: ((Boolean) -> Unit)? = null
    var onAutomaticSuggestionsUseGpuChanged: ((Boolean) -> Unit)? = null
    private var updatingAutomaticSuggestions = false

    init {
        column.addView(sourceLabel)
        column.addView(source, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        column.addView(candidateLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        column.addView(candidate, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        column.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        column.addView(timing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_S_DP) })
        column.addView(automaticSuggestionsControls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
        actions.addView(completeButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f))
        actions.addView(cancelButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
        actions.addView(applyButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
        actions.addView(installButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
        actions.addView(backButton, LinearLayout.LayoutParams(0, context.dp(PanelStyle.BUTTON_HEIGHT_DP), 1f).apply {
            marginStart = context.dp(PanelStyle.GAP_M_DP)
        })
        column.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(PanelStyle.GAP_M_DP) })
    }

    fun updateAutomaticSuggestions(
        supported: Boolean,
        enabled: Boolean,
        useGpu: Boolean,
        fallbackOccurred: Boolean
    ) {
        automaticSuggestionsControls.visibility = if (supported) View.VISIBLE else View.GONE
        if (!supported) return
        updatingAutomaticSuggestions = true
        try {
            automaticSuggestionsSwitch.isChecked = enabled
            cpuCompatibilitySwitch.isChecked = !useGpu
            cpuCompatibilitySwitch.isEnabled = !enabled
            cpuCompatibilityDescription.alpha = if (enabled) PanelStyle.DISABLED_ALPHA else 1f
            backendFallbackNotice.visibility = if (fallbackOccurred) View.VISIBLE else View.GONE
            backendFallbackNotice.alpha = if (enabled) PanelStyle.DISABLED_ALPHA else 1f
        } finally {
            updatingAutomaticSuggestions = false
        }
    }

    fun showPreview(text: String) {
        sourceLabel.visibility = View.VISIBLE
        source.visibility = View.VISIBLE
        source.text = text
        source.contentDescription = "${context.getString(R.string.gemma_context_source_label)}: $text"
        candidateLabel.visibility = View.GONE
        candidate.visibility = View.GONE
        status.visibility = View.GONE
        timing.visibility = View.GONE
        completeButton.visibility = View.VISIBLE
        completeButton.isEnabled = true
        cancelButton.visibility = View.GONE
        applyButton.visibility = View.GONE
        installButton.visibility = View.GONE
    }

    fun showRunning(isInferring: Boolean = false) {
        status.text = context.getString(
            if (isInferring) R.string.gemma_context_running else R.string.gemma_context_preparing
        )
        status.contentDescription = status.text
        status.setTextColor(theme.keyTextColor)
        status.visibility = View.VISIBLE
        timing.visibility = View.GONE
        completeButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        applyButton.visibility = View.GONE
        installButton.visibility = View.GONE
    }

    fun showCandidate(text: String, suffix: String, result: OnDeviceContextCompletionResult) {
        val full = text + suffix
        candidateLabel.visibility = View.VISIBLE
        candidate.text = full
        candidate.contentDescription = "${context.getString(R.string.gemma_context_candidate_label)}: $full"
        candidate.visibility = View.VISIBLE
        status.text = context.getString(R.string.gemma_context_ready)
        status.contentDescription = status.text
        status.setTextColor(theme.keyTextColor)
        status.visibility = View.VISIBLE
        timing.text = result.firstTextMs?.let { firstTextMs ->
            context.getString(R.string.gemma_context_timing, firstTextMs, result.elapsedMs)
        } ?: context.getString(R.string.gemma_context_timing_no_first, result.elapsedMs)
        timing.visibility = View.VISIBLE
        completeButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        applyButton.visibility = View.VISIBLE
        applyButton.isEnabled = true
        installButton.visibility = View.GONE
    }

    fun showMessage(message: String, isError: Boolean = false) {
        candidateLabel.visibility = View.GONE
        candidate.visibility = View.GONE
        candidate.text = ""
        candidate.contentDescription = null
        status.text = message
        status.contentDescription = message
        status.setTextColor(if (isError) PanelStyle.errorTextColor(theme) else theme.keyTextColor)
        status.visibility = View.VISIBLE
        timing.visibility = View.GONE
        completeButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        applyButton.visibility = View.GONE
        installButton.visibility = View.GONE
    }

    /** Shown instead of the preview/complete flow while the on-device model isn't installed yet. */
    fun showInstallPrompt(promptTitle: String, detail: String?, buttonText: String?) {
        sourceLabel.visibility = View.GONE
        source.visibility = View.GONE
        candidateLabel.visibility = View.GONE
        candidate.visibility = View.GONE
        status.text = promptTitle
        status.contentDescription = promptTitle
        status.setTextColor(theme.keyTextColor)
        status.visibility = View.VISIBLE
        timing.text = detail ?: ""
        timing.visibility = if (detail != null) View.VISIBLE else View.GONE
        completeButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        applyButton.visibility = View.GONE
        if (buttonText != null) {
            installButton.text = buttonText
            installButton.contentDescription = buttonText
            installButton.visibility = View.VISIBLE
        } else {
            installButton.visibility = View.GONE
        }
    }

    fun clearSensitiveContent() {
        source.text = ""
        source.contentDescription = null
        candidate.text = ""
        candidate.contentDescription = null
        status.text = ""
        status.contentDescription = null
        timing.text = ""
    }
}
