/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.DirectBootInputPolicy
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.ai.AiAppliedEdit
import org.fcitx.fcitx5.android.input.ai.AiEditorTarget
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import timber.log.Timber

private const val AUTOMATIC_SUGGESTION_TTL_MS = 30_000L
private const val AUTOMATIC_SUGGESTION_HIDE_GRACE_MS = 600_000L
private const val AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRIES = 20
private const val AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRY_MS = 500L

// 이미 따뜻한 공유 엔진에 다시 붙는 워밍업도 타이핑이 잠시 멈춘 뒤에 한다.
private const val AUTOMATIC_SUGGESTION_WARMUP_IDLE_MS = 1_500L
// 차가운 엔진의 워밍업이 키보드가 숨겨지기를 기다리며 확인하는 간격.
private const val AUTOMATIC_SUGGESTION_WARMUP_HIDDEN_POLL_MS = 500L

/**
 * Owns automatic on-device sentence suggestions: the opt-in state, the runtime and coordinator
 * with their warm-up job and recovery budget, the editor snapshot and its extracted-text monitor,
 * and the TTL and keyboard-hide grace timers, which run on this controller's own main-thread
 * [Handler].
 */
class AutomaticSuggestionController(
    private val tokens: ExtractedTextTokens,
    private val host: Host
) {

    /** The editor, composition and neighbouring feature state automatic suggestions read. */
    data class Host(
        val createRuntime: (useGpu: Boolean) -> OnDeviceAutomaticSuggestionRuntime,
        val createStatusStore: () -> AiRuntimeStatusStore,
        val lifecycleScope: () -> CoroutineScope,
        val inputView: () -> InputView?,
        val editorInfo: () -> EditorInfo?,
        val capabilityFlags: () -> CapabilityFlags,
        val inputConnection: () -> InputConnection?,
        val selection: () -> CursorRange,
        val inputSessionEpoch: () -> Long,
        val isDirectBootMode: () -> Boolean,
        val appAiPolicy: () -> AppFeaturePolicy?,
        val allowsCompletion: () -> Boolean,
        val isPromptInputOwned: () -> Boolean,
        val isPromptCaptureActive: () -> Boolean,
        val isContextCompletionMonitorActive: () -> Boolean,
        val hasContextCompletionListener: () -> Boolean,
        val personalSentenceVault: () -> PersonalSentenceVault,
        val recentSentSentences: () -> RecentSentSentences,
        val lastEditorActivityAtMs: () -> Long,
        val isBufferedHangulSession: () -> Boolean,
        val bufferedHangulPrefix: () -> String,
        val isBufferedEngineResetPending: () -> Boolean,
        val enginePreedit: () -> FormattedText,
        val composing: () -> CursorRange,
        val composingText: () -> FormattedText,
        val finishCompositionForDirectAction: () -> Boolean,
        val commitAiTextAtCursor: (
            connection: InputConnection,
            cursor: Int,
            text: String,
            restore: EditorSelection
        ) -> Boolean,
        val predictSelection: (position: Int) -> Unit
    )

    private val prefs = AppPrefs.getInstance()

    private var automaticSuggestionInvalidationListener: (() -> Unit)? = null
    private var activeAutomaticSuggestionExtractedTextToken: Int? = null
    private var activeAutomaticSuggestionExtractedTextEpoch: Long? = null
    private var automaticSuggestionRevision = 0L
    private var automaticSuggestionClientPreedit: String? = null
    private var automaticSuggestionInputPanelPreedit: String? = null
    private var automaticSuggestionsEnabledInternal = false
    private var automaticSuggestionOptInRestored = false
    private var automaticSuggestionsUseGpuInternal = prefs.internal.automaticOnDeviceSuggestionsUseGpu.getValue()
    private var automaticSuggestionBackendFallbackUsed = false
    private var automaticSuggestionRuntime: OnDeviceAutomaticSuggestionRuntime? = null
    private var automaticSuggestionCoordinator: OnDeviceSuggestionCoordinator? = null
    private var automaticSuggestionWarmupJob: Job? = null
    private var automaticSuggestionWarmupStateInternal = OnDeviceAutomaticSuggestionWarmupState.Idle
    private var automaticSuggestionWarmupFailureCodeInternal: String? = null
    private var automaticSuggestionIndicatorInternal: OnDeviceAutomaticSuggestionIndicator =
        OnDeviceAutomaticSuggestionIndicator.Hidden
    private var latestAutomaticSuggestionSnapshot: OnDeviceAutomaticEditorSnapshot? = null
    private var automaticSuggestionTtlCandidate: OnDeviceSuggestionCoordinator.Candidate? = null
    private var automaticSuggestionClosedGateInvalidated = true
    private var automaticSuggestionGenerationStartedAtMs: Long? = null
    private val automaticSuggestionRecoveryBudget = OnDeviceRecoveryBudget()
    private val aiRuntimeStatusStore by lazy { host.createStatusStore() }

    private val automaticSuggestionMainHandler = Handler(Looper.getMainLooper())
    private var automaticSuggestionTtlRunnable: Runnable? = null
    private var automaticSuggestionHideRunnable: Runnable? = null

    @Keep
    private val automaticSuggestionOptInListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        automaticSuggestionOptInRestored = false
        setAutomaticSuggestionsEnabled(enabled)
    }

    fun setAutomaticSuggestionInvalidationListener(listener: (() -> Unit)?) {
        check(listener == null || automaticSuggestionInvalidationListener == null ||
            automaticSuggestionInvalidationListener === listener) {
            "Automatic on-device suggestion already has an active listener"
        }
        automaticSuggestionInvalidationListener = listener
        if (listener == null) {
            clearAutomaticSuggestionPreeditReferences()
            notifyAutomaticSuggestionSnapshotInvalidated()
        }
    }

    fun notifyAutomaticSuggestionSnapshotInvalidated() {
        clearAutomaticSuggestionExtractedTextMonitor()
        automaticSuggestionRevision += 1
        automaticSuggestionInvalidationListener?.invoke()
    }

    private fun beginAutomaticSuggestionExtractedTextMonitor(epoch: Long): ExtractedTextRequest {
        val token = tokens.next()
        activeAutomaticSuggestionExtractedTextToken = token
        activeAutomaticSuggestionExtractedTextEpoch = epoch
        return ExtractedTextRequest().apply {
            this.token = token
            hintMaxChars = ON_DEVICE_CONTEXT_MAX_CHARS + 1
            hintMaxLines = 0
        }
    }

    private fun clearAutomaticSuggestionExtractedTextMonitor() {
        activeAutomaticSuggestionExtractedTextToken = null
        activeAutomaticSuggestionExtractedTextEpoch = null
    }

    private fun clearAutomaticSuggestionPreeditReferences() {
        automaticSuggestionClientPreedit = null
        automaticSuggestionInputPanelPreedit = null
    }

    fun notifyAutomaticSuggestionPreeditChanged(
        clientPreedit: String? = null,
        inputPanelPreedit: String? = null
    ) {
        if (automaticSuggestionInvalidationListener == null) return
        if (!canCaptureAutomaticSuggestionSnapshot()) {
            clearAutomaticSuggestionPreeditReferences()
            notifyAutomaticSuggestionSnapshotInvalidated()
            return
        }
        val clientChanged = clientPreedit != null && clientPreedit != automaticSuggestionClientPreedit
        val panelChanged = inputPanelPreedit != null && inputPanelPreedit != automaticSuggestionInputPanelPreedit
        if (clientPreedit != null) automaticSuggestionClientPreedit = clientPreedit
        if (inputPanelPreedit != null) automaticSuggestionInputPanelPreedit = inputPanelPreedit
        if (clientChanged || panelChanged) notifyAutomaticSuggestionSnapshotInvalidated()
    }

    /** The indicator a freshly created input view starts from. */
    val indicator: OnDeviceAutomaticSuggestionIndicator
        get() = automaticSuggestionIndicatorInternal

    /** The token of the monitor watching the current snapshot, or null while none is active. */
    val activeExtractedTextToken: Int?
        get() = activeAutomaticSuggestionExtractedTextToken

    /** On-device context completion invalidated its snapshot, which also stales this one. */
    fun onContextSnapshotInvalidated() {
        clearAutomaticSuggestionPreeditReferences()
        notifyAutomaticSuggestionSnapshotInvalidated()
    }

    fun registerListeners() {
        prefs.internal.automaticOnDeviceSuggestionsOptIn.registerOnChangeListener(automaticSuggestionOptInListener)
        OnDeviceGenerationControl.configureAutoContextPreemption(
            ::isAutomaticSuggestionBusyForPreemption,
            ::preemptAutomaticSuggestionForExplicitContext,
            ::resumeAutomaticSuggestionAfterExplicitContext
        )
    }

    fun unregisterListeners() {
        prefs.internal.automaticOnDeviceSuggestionsOptIn.unregisterOnChangeListener(automaticSuggestionOptInListener)
        OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
    }

    /** Restores the persisted opt-in once, then warms up for the editor that is now showing. */
    fun onStartInputView(info: EditorInfo, flags: CapabilityFlags) {
        if (automaticSuggestionsSupported && !automaticSuggestionOptInRestored) {
            automaticSuggestionOptInRestored = true
            if (AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.getValue()) {
                setAutomaticSuggestionsEnabled(true)
            }
        }
        startAutomaticSuggestionWarmupIfAllowed(info, flags)
    }

    fun onFinishInputView() {
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
        scheduleAutomaticSuggestionHideClose()
    }

    fun onFinishInput() {
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
        scheduleAutomaticSuggestionHideClose()
    }

    fun onTrimMemory(level: Int) {
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
        OnDeviceSharedEngine.requestClose("TRIM_MEMORY_$level")
    }

    fun onDestroy() {
        cancelAutomaticSuggestionHideClose()
        setAutomaticSuggestionsEnabled(false)
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
        OnDeviceSharedEngine.requestClose("SERVICE_DESTROYED")
        latestAutomaticSuggestionSnapshot = null
        clearAutomaticSuggestionTtl()
    }

    val automaticSuggestionsSupported: Boolean
        get() = OnDeviceAiSupport.isSupported

    val automaticSuggestionsEnabled: Boolean
        get() = automaticSuggestionsEnabledInternal

    val automaticSuggestionsUseGpu: Boolean
        get() = automaticSuggestionsUseGpuInternal

    val automaticSuggestionBackendFallbackOccurred: Boolean
        get() = automaticSuggestionBackendFallbackUsed

    val automaticSuggestionStatus: OnDeviceSuggestionCoordinator.Status
        get() = automaticSuggestionCoordinator?.status
            ?: OnDeviceSuggestionCoordinator.Status(OnDeviceSuggestionCoordinator.State.OFF)

    val automaticSuggestionWarmupState: OnDeviceAutomaticSuggestionWarmupState
        get() = automaticSuggestionWarmupStateInternal

    val automaticSuggestionWarmupFailureCode: String?
        get() = automaticSuggestionWarmupFailureCodeInternal

    val automaticSuggestionRuntimeWarm: Boolean
        get() = automaticSuggestionRuntime?.isWarm == true

    fun setAutomaticSuggestionsEnabled(enabled: Boolean) {
        if (!enabled) {
            // Turning off must release the warm engine even when only the warm-up ran (opt-in was
            // never on), so no native lease survives an opt-out or service teardown.
            val wasEnabled = automaticSuggestionsEnabledInternal
            automaticSuggestionsEnabledInternal = false
            automaticSuggestionWarmupJob?.cancel()
            automaticSuggestionWarmupJob = null
            updateAutomaticSuggestionWarmupState(OnDeviceAutomaticSuggestionWarmupState.Idle)
            if (wasEnabled) setAutomaticSuggestionInvalidationListener(null)
            latestAutomaticSuggestionSnapshot = null
            clearAutomaticSuggestionTtl()
            automaticSuggestionCoordinator?.setEnabled(false)
            automaticSuggestionCoordinator?.invalidate(closeBackend = true)
            automaticSuggestionClosedGateInvalidated = true
            automaticSuggestionWarmupFailureCodeInternal = null
            automaticSuggestionRecoveryBudget.reset()
            refreshAutomaticSuggestionIndicator()
            // 자동 추천을 끄면 공유 엔진이 차지하던 메모리·GPU도 돌려준다(다른 목적은 필요할 때 다시 연다).
            OnDeviceSharedEngine.requestClose("AUTO_SUGGESTIONS_OFF")
            return
        }
        // Opting in is the documented release valve for an exhausted recovery budget: give it a
        // full fresh window so a terminal coordinator/runtime from before this toggle is not
        // permanently blocked by ENGINE_UNRECOVERABLE just because the budget was already spent.
        if (!automaticSuggestionsEnabledInternal) automaticSuggestionRecoveryBudget.reset()
        if (!automaticSuggestionsSupported || !ensureAutomaticSuggestionCoordinator()) return
        if (automaticSuggestionsEnabledInternal) return
        automaticSuggestionsEnabledInternal = true
        automaticSuggestionClosedGateInvalidated = false
        automaticSuggestionCoordinator?.setEnabled(true)
        setAutomaticSuggestionInvalidationListener(::onAutomaticSuggestionInvalidated)
        refreshAutomaticSuggestionIndicator()
        // Opting in while the keyboard is already showing (e.g. a mid-session toggle used as the
        // recovery release valve) must warm up immediately rather than waiting for the next
        // onStartInputView, or a just-recovered coordinator would sit idle until the editor
        // restarts. retryAutomaticSuggestionWarmup() is a no-op when there is no current editor.
        retryAutomaticSuggestionWarmup()
    }

    fun setAutomaticSuggestionsUseGpu(useGpu: Boolean): Boolean {
        if (automaticSuggestionsEnabledInternal) return false
        if (automaticSuggestionsUseGpuInternal == useGpu) return true
        val runtime = automaticSuggestionRuntime
        if (runtime != null && (runtime.isPreparing || runtime.isRunning || runtime.isWarm)) return false
        automaticSuggestionCoordinator?.setEnabled(false)
        automaticSuggestionCoordinator = null
        automaticSuggestionRuntime = null
        latestAutomaticSuggestionSnapshot = null
        clearAutomaticSuggestionTtl()
        automaticSuggestionClosedGateInvalidated = true
        automaticSuggestionsUseGpuInternal = useGpu
        prefs.internal.automaticOnDeviceSuggestionsUseGpu.setValue(useGpu)
        return true
    }

    fun getAutomaticSuggestionCandidates(): List<OnDeviceSuggestionCoordinator.Candidate> {
        if (!automaticSuggestionsSupported || !automaticSuggestionsEnabledInternal) return emptyList()
        if (!canCaptureAutomaticSuggestionSnapshot()) {
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
            return emptyList()
        }
        val coordinator = automaticSuggestionCoordinator ?: return emptyList()
        val snapshot = latestAutomaticSuggestionSnapshot?.takeIf(::isAutomaticSuggestionSnapshotCurrent)
            ?: captureAutomaticSuggestionSnapshot()
            ?: run {
                invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
                return emptyList()
            }
        automaticSuggestionClosedGateInvalidated = false
        latestAutomaticSuggestionSnapshot = snapshot
        coordinator.observe(
            snapshot = snapshot.session,
            input = OnDeviceSuggestionPolicy.Input(
                textBeforeCursor = snapshot.session.textBeforeCursor,
                packageName = snapshot.session.scope.packageName,
                inputType = snapshot.inputType,
                imeAction = snapshot.imeAction,
                mode = OnDeviceSuggestionPolicy.Mode.SENTENCE,
                appCategory = PersonaRegistry.classify(
                    snapshot.session.scope.packageName
                ),
                fieldHint = automaticSuggestionFieldHint(),
                recentSentences = host.recentSentSentences().recent(snapshot.session.scope.packageName, 3)
            )
        )
        return coordinator.candidates
    }

    /**
     * The current editor's hint text, trimmed and capped at 40 characters, or null when empty or
     * when it looks like it contains PII. This is sent to the on-device model as a hint about what
     * the field is asking for (e.g. "받는 사람"), never as free-form user-authored text.
     */
    private fun automaticSuggestionFieldHint(): String? {
        val trimmed = host.editorInfo()?.hintText?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val capped = if (trimmed.length > 40) trimmed.take(40) else trimmed
        return capped.takeUnless { KoreanPiiScrubber.containsPii(it) }
    }

    fun commitAutomaticSuggestionCandidate(
        candidate: OnDeviceSuggestionCoordinator.Candidate
    ): AiSuggestionApplyResult {
        if (!isAutomaticSuggestionEligible()) return AiSuggestionApplyResult.NotApplied
        val coordinator = automaticSuggestionCoordinator ?: return AiSuggestionApplyResult.NotApplied
        val snapshot = latestAutomaticSuggestionSnapshot ?: return AiSuggestionApplyResult.EditorChanged
        if (!isAutomaticSuggestionSnapshotCurrent(snapshot)) return AiSuggestionApplyResult.EditorChanged
        val suffix = coordinator.takeForApply(candidate, snapshot.session)
            ?: return AiSuggestionApplyResult.EditorChanged
        val result = applyAutomaticSuggestion(snapshot, suffix)
        host.inputView()?.postRefreshContextualCandidates(16L)
        return result
    }

    private fun ensureAutomaticSuggestionCoordinator(): Boolean {
        if (!recoverAutomaticSuggestionStateIfTerminal()) return false
        automaticSuggestionCoordinator?.let { return true }
        val runtime = host.createRuntime(automaticSuggestionsUseGpuInternal)
        if (!runtime.supported) return false
        val session = OnDeviceSuggestionSession()
        automaticSuggestionRuntime = runtime
        automaticSuggestionCoordinator = OnDeviceSuggestionCoordinator(
            scope = host.lifecycleScope(),
            session = session,
            backend = runtime,
            clockMs = SystemClock::elapsedRealtime,
            isCurrent = { sessionSnapshot ->
                latestAutomaticSuggestionSnapshot?.let { snapshot ->
                    snapshot.session == sessionSnapshot && isAutomaticSuggestionSnapshotCurrent(snapshot)
                } == true
            },
            onChanged = ::onAutomaticSuggestionCoordinatorChanged,
            promptContextEnricher = ::enrichAutomaticSuggestionInputWithPersonalStyle
        )
        // A coordinator created here can be a mid-session replacement for a terminal instance
        // (see recoverAutomaticSuggestionStateIfTerminal), not only a first-time creation from
        // setAutomaticSuggestionsEnabled(true). Sync it to the current opt-in state immediately so
        // a recovery that happens while already opted in does not leave the fresh coordinator
        // silently disabled (its own default is enabled=false).
        automaticSuggestionCoordinator?.setEnabled(automaticSuggestionsEnabledInternal)
        return true
    }

    /**
     * Adds up to 3 similar past sentences from the personal sentence vault to [input] as style
     * examples. Runs off the main thread (the coordinator dispatches this call on
     * [kotlinx.coroutines.Dispatchers.Default]); this function itself does no dispatching.
     */
    private fun enrichAutomaticSuggestionInputWithPersonalStyle(
        input: OnDeviceSuggestionPolicy.Input
    ): OnDeviceSuggestionPolicy.Input {
        val currentText = input.textBeforeCursor.trim()
        val styleExamples = host.personalSentenceVault().retrieve(input.textBeforeCursor, input.packageName, limit = 5)
            .map { it.sentence.trim() }
            .filter {
                it.isNotEmpty() && it != currentText && it.length <= 80 &&
                    !KoreanPiiScrubber.containsPii(it)
            }
            .take(3)
        if (styleExamples.isEmpty()) return input
        return OnDeviceSuggestionPolicy.Input(
            textBeforeCursor = input.textBeforeCursor,
            packageName = input.packageName,
            inputType = input.inputType,
            imeAction = input.imeAction,
            mode = input.mode,
            appCategory = input.appCategory,
            fieldHint = input.fieldHint,
            recentSentences = input.recentSentences,
            styleExamples = styleExamples
        )
    }

    /**
     * A [terminalFailureCode]/[OnDeviceSuggestionCoordinator.isTerminal] latch is scoped to that
     * runtime/coordinator instance, not the process: it means the underlying engine object is
     * unusable, not that automatic suggestions must stay off forever. When terminal, this discards
     * the stale instance (the same disposal steps the GPU-to-CPU fallback uses) so
     * [ensureAutomaticSuggestionCoordinator] creates a fresh one, gated by
     * [automaticSuggestionRecoveryBudget] so a repeatedly failing engine cannot recover in a tight
     * loop. Returns false only when the budget is exhausted, in which case the caller must not
     * create a new coordinator until the opt-in is toggled or the service is destroyed.
     */
    private fun recoverAutomaticSuggestionStateIfTerminal(): Boolean {
        val runtimeFailureCode = automaticSuggestionRuntime?.terminalFailureCode
        val coordinatorTerminal = automaticSuggestionCoordinator?.isTerminal == true
        if (runtimeFailureCode == null && !coordinatorTerminal) return true
        val now = SystemClock.elapsedRealtime()
        if (!automaticSuggestionRecoveryBudget.tryConsume(now)) {
            automaticSuggestionWarmupFailureCodeInternal = "ENGINE_UNRECOVERABLE"
            aiRuntimeStatusStore.recordFailure("ENGINE_UNRECOVERABLE", System.currentTimeMillis())
            refreshAutomaticSuggestionIndicator()
            return false
        }
        val fromCode = runtimeFailureCode ?: automaticSuggestionCoordinator?.status?.errorCode ?: "UNKNOWN"
        Timber.w(
            "Automatic suggestion engine recovered from %s (recovery %d)",
            fromCode,
            automaticSuggestionRecoveryBudget.consumedInWindow(now)
        )
        aiRuntimeStatusStore.recordRecovery(fromCode, System.currentTimeMillis())
        automaticSuggestionCoordinator?.setEnabled(false)
        // Start the dead backend's late cleanup so its native lease is released; otherwise the
        // replacement engine would only ever see BUSY.
        automaticSuggestionCoordinator?.invalidate(closeBackend = true)
        automaticSuggestionCoordinator = null
        automaticSuggestionRuntime = null
        latestAutomaticSuggestionSnapshot = null
        clearAutomaticSuggestionTtl()
        automaticSuggestionClosedGateInvalidated = true
        automaticSuggestionWarmupJob?.cancel()
        automaticSuggestionWarmupJob = null
        return true
    }

    private fun isAutomaticSuggestionEligible(): Boolean =
        automaticSuggestionsSupported && automaticSuggestionsEnabledInternal &&
            canCaptureAutomaticSuggestionSnapshot()

    /**
     * Whether the AUTO_CONTEXT lease holder is actually generating right now. A warm-up in
     * progress ([OnDeviceAutomaticSuggestionRuntime.isPreparing]) is deliberately excluded: the
     * user's explicit, tap-triggered completion outranks a background warm-up, so that case is
     * still preemptible.
     */
    private fun isAutomaticSuggestionBusyForPreemption(): Boolean =
        automaticSuggestionRuntime?.isRunning == true

    /**
     * Hard-stops automatic suggestions' runtime/coordinator/warm-up job (the same body
     * [scheduleAutomaticSuggestionHideClose] runs after its grace period, called here without the
     * delay) so an explicit, tap-triggered context completion can take the native lease instead of
     * waiting behind a warm or warming-up automatic engine. The opt-in enabled state
     * ([automaticSuggestionsEnabledInternal]) is left untouched, so this is a lease-release only,
     * not an opt-out.
     */
    private fun preemptAutomaticSuggestionForExplicitContext() {
        // Invoked from whichever thread called OnDeviceGenerationControl.tryBegin (the material
        // accumulation worker runs on a WorkManager thread). The teardown touches main-thread-only
        // state, so hop over instead of asserting the caller's thread.
        runOnMainThread {
            Timber.i("Automatic suggestion preempted by explicit context")
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else automaticSuggestionMainHandler.post(block)
    }

    /**
     * Called right after an EXPLICIT_CONTEXT lease is released. If automatic suggestions are still
     * opted in and the keyboard is active, this restarts warm-up immediately so the feature resumes
     * within the same input session instead of waiting for the next onStartInputView.
     */
    private fun resumeAutomaticSuggestionAfterExplicitContext() {
        // Called from OnDeviceGenerationControl.end(), possibly on the releasing worker's thread.
        runOnMainThread {
            if (!OnDeviceGenerationControl.isKeyboardActive) return@runOnMainThread
            val info = host.editorInfo() ?: return@runOnMainThread
            refreshAutomaticSuggestionIndicator()
            startAutomaticSuggestionWarmupIfAllowed(info, host.capabilityFlags())
        }
    }

    /** Manually restarts automatic-suggestion warm-up, e.g. after a user taps the blocked indicator. */
    fun retryAutomaticSuggestionWarmup() {
        val info = host.editorInfo() ?: return
        startAutomaticSuggestionWarmupIfAllowed(info, host.capabilityFlags())
    }

    /**
     * 워밍업을 시작해도 되는 때까지 기다린다. 공유 엔진이 이미 따뜻하면 GPU 초기화가 없으므로 입력이 잠시 멈추기만
     * 기다린다. 차가우면 키보드가 숨겨질 때까지 기다린다: 키보드가 떠 있는 동안 GPU로 초기화하면 가중치 변환이
     * 화면 그리기와 GPU를 다퉈 키보드가 1초 넘게 멈춘다.
     */
    private suspend fun awaitWarmupWindow(since: Long) {
        while (!OnDeviceSharedEngine.isWarm && OnDeviceGenerationControl.isInputViewVisible) {
            delay(AUTOMATIC_SUGGESTION_WARMUP_HIDDEN_POLL_MS)
        }
        if (OnDeviceGenerationControl.isInputViewVisible) awaitEditorIdle(since)
    }

    /** [since] 이후로 에디터 입력이 [AUTOMATIC_SUGGESTION_WARMUP_IDLE_MS] 동안 없을 때까지 기다린다. */
    private suspend fun awaitEditorIdle(since: Long) {
        while (true) {
            val quietSince = maxOf(since, host.lastEditorActivityAtMs())
            val remaining = quietSince + AUTOMATIC_SUGGESTION_WARMUP_IDLE_MS - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            delay(remaining)
        }
    }

    private fun startAutomaticSuggestionWarmupIfAllowed(
        info: EditorInfo,
        flags: CapabilityFlags
    ) {
        val warmupAllowed = allowsAutomaticSuggestionWarmup(info, flags)
        val keyboardActive = OnDeviceGenerationControl.isKeyboardActive
        Timber.i(
            "Automatic suggestion warm-up gate: supported=%s allowed=%s keyboardActive=%s " +
                "directBoot=%s forbidsInspection=%s conversational=%s aiPolicy=%s inputType=0x%x imeOptions=0x%x",
            automaticSuggestionsSupported,
            warmupAllowed,
            keyboardActive,
            host.isDirectBootMode(),
            EditorPrivacyPolicy.forbidsTextInspection(info, flags),
            EditorPrivacyPolicy.isConversationalTextField(info, flags),
            host.appAiPolicy(),
            info.inputType,
            info.imeOptions
        )
        if (!automaticSuggestionsSupported || !warmupAllowed || !keyboardActive ||
            !ensureAutomaticSuggestionCoordinator()
        ) {
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
            return
        }
        val runtime = automaticSuggestionRuntime ?: return
        if (runtime.isWarm) {
            updateAutomaticSuggestionWarmupState(OnDeviceAutomaticSuggestionWarmupState.Idle)
            return
        }
        if (automaticSuggestionWarmupJob?.isActive == true) return
        automaticSuggestionClosedGateInvalidated = false
        automaticSuggestionWarmupFailureCodeInternal = null
        refreshAutomaticSuggestionIndicator()
        val warmupScheduledAt = SystemClock.elapsedRealtime()
        automaticSuggestionWarmupJob = host.lifecycleScope().launch {
            var warmupStartedAt = warmupScheduledAt
            try {
                awaitWarmupWindow(since = warmupScheduledAt)
                warmupStartedAt = SystemClock.elapsedRealtime()
                updateAutomaticSuggestionWarmupState(OnDeviceAutomaticSuggestionWarmupState.Preparing)
                var busyRetries = 0
                while (true) {
                    try {
                        runtime.warmUp()
                        break
                    } catch (error: OnDeviceSuggestionCoordinator.BackendException) {
                        // Another generation purpose can still hold the native lease for a moment
                        // after the keyboard became active; wait for it instead of giving up.
                        if (error.code != "BUSY" || busyRetries >= AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRIES ||
                            !OnDeviceGenerationControl.isKeyboardActive
                        ) {
                            throw error
                        }
                        busyRetries += 1
                        Timber.i("Automatic suggestion warm-up busy, retry %d", busyRetries)
                        delay(AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRY_MS)
                    }
                }
                automaticSuggestionWarmupFailureCodeInternal = null
                refreshAutomaticSuggestionIndicator()
                val warmupElapsedMs = SystemClock.elapsedRealtime() - warmupStartedAt
                aiRuntimeStatusStore.recordWarmup(
                    result = "OK",
                    durationMs = warmupElapsedMs,
                    backend = if (automaticSuggestionsUseGpuInternal) "gpu" else "cpu",
                    nowMs = System.currentTimeMillis()
                )
                Timber.i(
                    "Automatic suggestion warm-up finished: warm=%s elapsedMs=%d",
                    runtime.isWarm,
                    warmupElapsedMs
                )
            } catch (error: CancellationException) {
                Timber.i("Automatic suggestion warm-up cancelled after %dms", SystemClock.elapsedRealtime() - warmupStartedAt)
                throw error
            } catch (error: Throwable) {
                val backendErrorCode = (error as? OnDeviceSuggestionCoordinator.BackendException)?.code
                if (OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
                        errorCode = backendErrorCode,
                        useGpu = automaticSuggestionsUseGpuInternal,
                        fallbackAlreadyUsed = automaticSuggestionBackendFallbackUsed
                    )
                ) {
                    Timber.i("Automatic suggestion backend fallback gpu->cpu")
                    automaticSuggestionBackendFallbackUsed = true
                    automaticSuggestionsUseGpuInternal = false
                    prefs.internal.automaticOnDeviceSuggestionsUseGpu.setValue(false)
                    automaticSuggestionCoordinator?.setEnabled(false)
                    automaticSuggestionCoordinator = null
                    automaticSuggestionRuntime = null
                    latestAutomaticSuggestionSnapshot = null
                    clearAutomaticSuggestionTtl()
                    automaticSuggestionClosedGateInvalidated = true
                    automaticSuggestionWarmupJob = null
                    startAutomaticSuggestionWarmupIfAllowed(info, flags)
                } else {
                    val code = backendErrorCode ?: "UNKNOWN"
                    automaticSuggestionWarmupFailureCodeInternal = code
                    refreshAutomaticSuggestionIndicator()
                    aiRuntimeStatusStore.recordWarmup(
                        result = code,
                        durationMs = SystemClock.elapsedRealtime() - warmupStartedAt,
                        backend = if (automaticSuggestionsUseGpuInternal) "gpu" else "cpu",
                        nowMs = System.currentTimeMillis()
                    )
                    aiRuntimeStatusStore.recordFailure(code, System.currentTimeMillis())
                    Timber.w("Automatic suggestion warm-up failed: %s", code)
                    Timber.d(error, "Automatic suggestion warm-up stopped")
                }
            } finally {
                if (automaticSuggestionRuntime === runtime) {
                    automaticSuggestionWarmupJob = null
                    updateAutomaticSuggestionWarmupState(OnDeviceAutomaticSuggestionWarmupState.Idle)
                }
            }
        }
    }

    private fun allowsAutomaticSuggestionWarmup(
        info: EditorInfo,
        flags: CapabilityFlags
    ): Boolean =
        DirectBootInputPolicy.allowsCredentialProtectedFeatures(host.isDirectBootMode()) &&
            !EditorPrivacyPolicy.forbidsTextInspection(info, flags) &&
            EditorPrivacyPolicy.isConversationalTextField(info, flags) &&
            host.appAiPolicy() != AppFeaturePolicy.Block

    private fun updateAutomaticSuggestionWarmupState(
        state: OnDeviceAutomaticSuggestionWarmupState
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (automaticSuggestionWarmupStateInternal == state) return
        automaticSuggestionWarmupStateInternal = state
        host.inputView()?.postRefreshContextualCandidates(16L)
        refreshAutomaticSuggestionIndicator()
    }

    private fun refreshAutomaticSuggestionIndicator() {
        val next = when {
            !automaticSuggestionsSupported -> OnDeviceAutomaticSuggestionIndicator.Hidden
            // The warm-up runs before opt-in so that opting in is instant; its spinner is shown
            // regardless. Blocked states are only meaningful once the feature is on.
            automaticSuggestionWarmupStateInternal == OnDeviceAutomaticSuggestionWarmupState.Preparing -> OnDeviceAutomaticSuggestionIndicator.Preparing
            !automaticSuggestionsEnabledInternal -> OnDeviceAutomaticSuggestionIndicator.Hidden
            automaticSuggestionWarmupFailureCodeInternal != null -> OnDeviceAutomaticSuggestionIndicator.Blocked(automaticSuggestionWarmupFailureCodeInternal!!)
            automaticSuggestionCoordinator?.status?.let { it.state == OnDeviceSuggestionCoordinator.State.ERROR && it.errorCode != "INVALID_INPUT" } == true ->
                OnDeviceAutomaticSuggestionIndicator.Blocked(automaticSuggestionCoordinator?.status?.errorCode ?: "BACKEND_FAILURE")
            else -> OnDeviceAutomaticSuggestionIndicator.Hidden
        }
        if (next == automaticSuggestionIndicatorInternal) return
        automaticSuggestionIndicatorInternal = next
        host.inputView()?.updateAutomaticSuggestionIndicator(next)
    }

    private fun onAutomaticSuggestionInvalidated() {
        latestAutomaticSuggestionSnapshot = null
        // A stale in-flight generation is discarded by the coordinator's epoch check. Cancelling
        // it natively would tear down the warm engine and cost a full re-preparation.
        if (!isAutomaticSuggestionEligible()) {
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
            return
        }
        host.inputView()?.postRefreshContextualCandidates(16L)
    }

    private fun invalidateAutomaticSuggestionsForClosedGate(closeBackend: Boolean) {
        val runtimeNeedsClose = closeBackend && automaticSuggestionRuntime?.let {
            it.isPreparing || it.isRunning || it.isWarm
        } == true
        val warmupNeedsCancel = closeBackend && automaticSuggestionWarmupJob != null
        val coordinatorNeedsClear = automaticSuggestionCoordinator?.let {
            it.candidates.isNotEmpty() ||
                it.status.state !in setOf(
                    OnDeviceSuggestionCoordinator.State.OFF,
                    OnDeviceSuggestionCoordinator.State.NO_CANDIDATE
                )
        } == true
        if (automaticSuggestionClosedGateInvalidated &&
            !warmupNeedsCancel && !runtimeNeedsClose && !coordinatorNeedsClear
        ) return
        automaticSuggestionClosedGateInvalidated = true
        if (closeBackend) {
            automaticSuggestionWarmupJob?.cancel()
            automaticSuggestionWarmupJob = null
            updateAutomaticSuggestionWarmupState(OnDeviceAutomaticSuggestionWarmupState.Idle)
        }
        latestAutomaticSuggestionSnapshot = null
        clearAutomaticSuggestionTtl()
        automaticSuggestionCoordinator?.invalidate(closeBackend)
        host.inputView()?.postRefreshContextualCandidates(16L)
    }

    private fun onAutomaticSuggestionCoordinatorChanged() {
        trackAutomaticSuggestionGenerationLatency()
        scheduleAutomaticSuggestionTtlIfNeeded()
        host.inputView()?.postRefreshContextualCandidates(16L)
        refreshAutomaticSuggestionIndicator()
    }

    /**
     * Records generation latency in [aiRuntimeStatusStore] whenever the coordinator reaches
     * READY. The clock starts at GENERATING (backend.generate() is in flight) and is discarded on
     * any other transition (NO_CANDIDATE, ERROR, OFF) since no successful generation completed.
     */
    private fun trackAutomaticSuggestionGenerationLatency() {
        when (automaticSuggestionCoordinator?.status?.state) {
            OnDeviceSuggestionCoordinator.State.GENERATING -> {
                if (automaticSuggestionGenerationStartedAtMs == null) {
                    automaticSuggestionGenerationStartedAtMs = SystemClock.elapsedRealtime()
                }
            }
            OnDeviceSuggestionCoordinator.State.READY -> {
                automaticSuggestionGenerationStartedAtMs?.let { startedAt ->
                    aiRuntimeStatusStore.recordGeneration(
                        SystemClock.elapsedRealtime() - startedAt,
                        System.currentTimeMillis()
                    )
                }
                automaticSuggestionGenerationStartedAtMs = null
            }
            else -> automaticSuggestionGenerationStartedAtMs = null
        }
    }

    private fun scheduleAutomaticSuggestionTtlIfNeeded() {
        val coordinator = automaticSuggestionCoordinator ?: return
        if (coordinator.status.state != OnDeviceSuggestionCoordinator.State.READY) {
            clearAutomaticSuggestionTtl()
            return
        }
        val generated = coordinator.candidates.firstOrNull {
            it.origin == OnDeviceSuggestionSession.Origin.GENERATED
        } ?: return
        if (automaticSuggestionTtlCandidate === generated) return
        clearAutomaticSuggestionTtl()
        automaticSuggestionTtlCandidate = generated
        val runnable = Runnable {
            automaticSuggestionTtlRunnable = null
            automaticSuggestionTtlCandidate = null
            host.inputView()?.postRefreshContextualCandidates(16L)
        }
        automaticSuggestionTtlRunnable = runnable
        automaticSuggestionMainHandler.postDelayed(runnable, AUTOMATIC_SUGGESTION_TTL_MS)
    }

    private fun clearAutomaticSuggestionTtl() {
        automaticSuggestionTtlRunnable?.let { automaticSuggestionMainHandler.removeCallbacks(it) }
        automaticSuggestionTtlRunnable = null
        automaticSuggestionTtlCandidate = null
    }

    private fun scheduleAutomaticSuggestionHideClose() {
        if (automaticSuggestionHideRunnable != null) return
        val runnable = Runnable {
            automaticSuggestionHideRunnable = null
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
        automaticSuggestionHideRunnable = runnable
        automaticSuggestionMainHandler.postDelayed(runnable, AUTOMATIC_SUGGESTION_HIDE_GRACE_MS)
    }

    fun cancelAutomaticSuggestionHideClose() {
        automaticSuggestionHideRunnable?.let { automaticSuggestionMainHandler.removeCallbacks(it) }
        automaticSuggestionHideRunnable = null
    }

    fun captureAutomaticSuggestionSnapshot(): OnDeviceAutomaticEditorSnapshot? {
        if (host.isContextCompletionMonitorActive()) return null
        clearAutomaticSuggestionExtractedTextMonitor()
        if (!canCaptureAutomaticSuggestionSnapshot()) return null
        val capturedSessionEpoch = host.inputSessionEpoch()
        val connection = host.inputConnection() ?: return null
        val request = beginAutomaticSuggestionExtractedTextMonitor(capturedSessionEpoch)
        val extracted = connection.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
            ?: run {
                clearAutomaticSuggestionExtractedTextMonitor()
                return null
            }
        return automaticSuggestionSnapshotFrom(
            extracted = extracted,
            expectedSessionEpoch = capturedSessionEpoch,
            expectedMonitorToken = request.token,
            recordPreedit = true
        ).also {
            if (it == null) clearAutomaticSuggestionExtractedTextMonitor()
        }
    }

    fun isAutomaticSuggestionSnapshotCurrent(snapshot: OnDeviceAutomaticEditorSnapshot): Boolean {
        if (!canCaptureAutomaticSuggestionSnapshot() ||
            snapshot.session.revision != automaticSuggestionRevision
        ) return false
        val connection = host.inputConnection() ?: return false
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val current = automaticSuggestionSnapshotFrom(
            extracted = extracted,
            expectedSessionEpoch = snapshot.session.scope.editorSessionId
        ) ?: return false
        return sameAutomaticSuggestionSnapshot(current, snapshot)
    }

    fun applyAutomaticSuggestion(
        snapshot: OnDeviceAutomaticEditorSnapshot,
        suffix: String
    ): AiSuggestionApplyResult {
        if (!isAutomaticSuggestionSuffixSafe(snapshot, suffix)) {
            return AiSuggestionApplyResult.NotApplied
        }
        if (!isAutomaticSuggestionSnapshotCurrent(snapshot)) {
            return AiSuggestionApplyResult.EditorChanged
        }
        if (!host.finishCompositionForDirectAction()) return AiSuggestionApplyResult.NotApplied
        if (!matchesAutomaticSuggestionAfterComposition(snapshot)) {
            return AiSuggestionApplyResult.EditorChanged
        }
        val connection = host.inputConnection() ?: return AiSuggestionApplyResult.NotApplied
        val cursor = host.selection().start
        if (!host.commitAiTextAtCursor(connection, cursor, suffix, EditorSelection.collapsed(cursor))) {
            return AiSuggestionApplyResult.NotApplied
        }
        val end = cursor + suffix.length
        host.predictSelection(end)
        notifyAutomaticSuggestionSnapshotInvalidated()
        return AiSuggestionApplyResult.Applied(
            AiAppliedEdit(
                editor = AiEditorTarget(
                    packageName = snapshot.session.scope.packageName,
                    fieldId = snapshot.session.scope.fieldId,
                    inputType = snapshot.inputType,
                    selectionStart = end,
                    selectionEnd = end,
                    inputSessionEpoch = snapshot.session.scope.editorSessionId
                ),
                inserted = suffix,
                restore = ""
            )
        )
    }

    private fun canCaptureAutomaticSuggestionSnapshot(): Boolean =
        host.allowsCompletion() &&
            OnDeviceGenerationControl.isKeyboardActive &&
            !host.isPromptInputOwned() &&
            !host.isPromptCaptureActive() &&
            !host.hasContextCompletionListener()

    private fun automaticSuggestionSnapshotFrom(
        extracted: ExtractedText,
        expectedSessionEpoch: Long,
        expectedMonitorToken: Int? = null,
        recordPreedit: Boolean = false
    ): OnDeviceAutomaticEditorSnapshot? {
        if (!canCaptureAutomaticSuggestionSnapshot() || host.inputSessionEpoch() != expectedSessionEpoch ||
            (expectedMonitorToken != null &&
                (activeAutomaticSuggestionExtractedTextToken != expectedMonitorToken ||
                    activeAutomaticSuggestionExtractedTextEpoch != expectedSessionEpoch))
        ) return null
        val physical = extracted.text?.toString() ?: return null
        val physicalSelection = host.selection()
        if (physical.length > ON_DEVICE_CONTEXT_MAX_CHARS || extracted.startOffset != 0 ||
            extracted.partialStartOffset != -1 || extracted.partialEndOffset != -1 ||
            extracted.selectionStart != physicalSelection.start ||
            extracted.selectionEnd != physicalSelection.end ||
            physicalSelection.start != physicalSelection.end ||
            physicalSelection.end != physical.length
        ) return null
        val info = host.editorInfo()
        val composing = host.composing()
        val isBuffered = host.isBufferedHangulSession()
        val currentComposingText = host.composingText().toString()
        val rawBufferedPrefix = host.bufferedHangulPrefix()
        val rawEnginePreedit: String
        val logical: String
        if (isBuffered) {
            if (!composing.isEmpty() || currentComposingText.isNotEmpty() ||
                host.isBufferedEngineResetPending()
            ) return null
            val enginePreedit = host.enginePreedit()
            rawEnginePreedit = enginePreedit.toString()
            if (rawEnginePreedit.isEmpty()) {
                if (enginePreedit.cursor != -1 && enginePreedit.cursor != 0) return null
            } else if (enginePreedit.cursor != rawEnginePreedit.length) return null
            if (recordPreedit && automaticSuggestionInvalidationListener != null) {
                automaticSuggestionInputPanelPreedit = rawEnginePreedit
            }
            logical = physical + rawBufferedPrefix + rawEnginePreedit
        } else {
            if (composing.isEmpty()) {
                if (currentComposingText.isNotEmpty()) return null
            } else if (
                composing.start < 0 || composing.end > physical.length ||
                composing.end - composing.start != currentComposingText.length ||
                physical.substring(composing.start, composing.end) != currentComposingText ||
                (host.composingText().cursor != -1 && host.composingText().cursor != currentComposingText.length) ||
                composing.end != physical.length
            ) return null
            if (recordPreedit && automaticSuggestionInvalidationListener != null) {
                automaticSuggestionClientPreedit = currentComposingText
            }
            rawEnginePreedit = ""
            logical = physical
        }
        if (logical.length !in 1..ON_DEVICE_CONTEXT_MAX_CHARS || logical.isBlank()) return null
        return OnDeviceAutomaticEditorSnapshot(
            session = OnDeviceSuggestionSession.Snapshot(
                scope = OnDeviceSuggestionSession.Scope(
                    packageName = info!!.packageName,
                    fieldId = info.fieldId,
                    editorSessionId = host.inputSessionEpoch()
                ),
                revision = automaticSuggestionRevision,
                textBeforeCursor = logical,
                selectionStart = logical.length,
                selectionEnd = logical.length
            ),
            inputType = info.inputType,
            imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION,
            physicalExtractedText = physical,
            physicalSelectionStart = physicalSelection.start,
            physicalSelectionEnd = physicalSelection.end,
            composingStart = composing.start,
            composingEnd = composing.end,
            composingText = currentComposingText,
            bufferedHangul = isBuffered,
            rawBufferedPrefix = rawBufferedPrefix,
            rawEnginePreedit = rawEnginePreedit
        )
    }

    private fun sameAutomaticSuggestionSnapshot(
        current: OnDeviceAutomaticEditorSnapshot,
        expected: OnDeviceAutomaticEditorSnapshot
    ): Boolean =
        current.session == expected.session &&
            current.inputType == expected.inputType &&
            current.imeAction == expected.imeAction &&
            current.physicalExtractedText == expected.physicalExtractedText &&
            current.physicalSelectionStart == expected.physicalSelectionStart &&
            current.physicalSelectionEnd == expected.physicalSelectionEnd &&
            current.composingStart == expected.composingStart &&
            current.composingEnd == expected.composingEnd &&
            current.composingText == expected.composingText &&
            current.bufferedHangul == expected.bufferedHangul &&
            current.rawBufferedPrefix == expected.rawBufferedPrefix &&
            current.rawEnginePreedit == expected.rawEnginePreedit

    private fun matchesAutomaticSuggestionAfterComposition(
        snapshot: OnDeviceAutomaticEditorSnapshot
    ): Boolean {
        if (!canCaptureAutomaticSuggestionSnapshot()) return false
        val info = host.editorInfo()
        if (!EditorIdentity.of(info!!).sameField(snapshot.identity) ||
            (info.imeOptions and EditorInfo.IME_MASK_ACTION) != snapshot.imeAction ||
            host.inputSessionEpoch() != snapshot.session.scope.editorSessionId
        ) return false
        val connection = host.inputConnection() ?: return false
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val physical = extracted.text?.toString() ?: return false
        val currentSelection = host.selection()
        return extracted.startOffset == 0 && extracted.partialStartOffset == -1 &&
            extracted.partialEndOffset == -1 && physical == snapshot.session.textBeforeCursor &&
            extracted.selectionStart == physical.length && extracted.selectionEnd == physical.length &&
            currentSelection.rangeEquals(physical.length)
    }

    private fun isAutomaticSuggestionSuffixSafe(
        snapshot: OnDeviceAutomaticEditorSnapshot,
        suffix: String
    ): Boolean {
        val base = OnDeviceSuggestionPolicy.Input(
            textBeforeCursor = snapshot.session.textBeforeCursor,
            packageName = snapshot.session.scope.packageName,
            inputType = snapshot.inputType,
            imeAction = snapshot.imeAction,
            mode = OnDeviceSuggestionPolicy.Mode.WORD
        )
        return OnDeviceSuggestionPolicy.parseSuffix(base, suffix) == suffix ||
            OnDeviceSuggestionPolicy.parseSuffix(
                OnDeviceSuggestionPolicy.Input(
                    textBeforeCursor = base.textBeforeCursor,
                    packageName = base.packageName,
                    inputType = base.inputType,
                    imeAction = base.imeAction,
                    mode = OnDeviceSuggestionPolicy.Mode.SENTENCE
                ),
                suffix
            ) == suffix
    }
}
