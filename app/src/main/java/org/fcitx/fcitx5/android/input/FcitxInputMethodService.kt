/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.BuildConfig
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.TRANSIENT_BUFFERED_PASTE_LABEL
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseTemplate
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseValues
import org.fcitx.fcitx5.android.data.quickphrase.snippet.SnippetCatalog
import org.fcitx.fcitx5.android.data.quickphrase.snippet.SnippetRepository
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.dynamicphrase.DynamicPhraseEditorTarget
import org.fcitx.fcitx5.android.input.dynamicphrase.SensitivePhraseSession
import org.fcitx.fcitx5.android.input.ai.AiAppliedEdit
import org.fcitx.fcitx5.android.input.ai.AiApplyMode
import org.fcitx.fcitx5.android.input.ai.AiEditorTransaction
import org.fcitx.fcitx5.android.input.ai.AiEditorTarget
import org.fcitx.fcitx5.android.input.ai.AiInputCaptureResult
import org.fcitx.fcitx5.android.input.ai.AiInputSnapshot
import org.fcitx.fcitx5.android.input.ai.AiSourceKind
import org.fcitx.fcitx5.android.input.ai.AiSourceScope
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.AiTextSource
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.fcitx.fcitx5.android.input.ai.ContextualReplacement
import org.fcitx.fcitx5.android.input.ai.KoreanSemanticSentencePredictor
import org.fcitx.fcitx5.android.input.ai.TypingDnaCommitSink
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.learning.CollectionFeedbackEvent
import org.fcitx.fcitx5.android.input.ai.learning.PersonalLearningController
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsSession
import org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceContextCompletionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticEditorSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionIndicator
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionRuntime
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceRecoveryBudget
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSharedEngine
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionSession
import org.fcitx.fcitx5.android.input.ai.ondevice.RecentSentSentences
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionSessionTracker
import org.fcitx.fcitx5.android.input.context.KoreanParticleCommitContract
import org.fcitx.fcitx5.android.input.context.KoreanParticleEditorTarget
import org.fcitx.fcitx5.android.input.context.KoreanParticleSnapshot
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggester
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.policy.InputFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.AppKeyboardGlobalDefaults
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileResolver
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileStore
import org.fcitx.fcitx5.android.input.profile.AppToolbarVisibility
import org.fcitx.fcitx5.android.input.profile.EffectiveAppKeyboardProfile
import org.fcitx.fcitx5.android.input.prompt.InternalPromptController
import org.fcitx.fcitx5.android.input.search.KoreanDictionaryQuery
import org.fcitx.fcitx5.android.input.typo.KoreanTypoRecovery
import org.fcitx.fcitx5.android.input.typo.TypoRecoveryEditorTarget
import org.fcitx.fcitx5.android.input.typo.TypoRecoverySnapshot
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private const val ON_DEVICE_CONTEXT_MAX_CHARS = 2048
private const val AUTOMATIC_SUGGESTION_TTL_MS = 30_000L
private const val AUTOMATIC_SUGGESTION_HIDE_GRACE_MS = 600_000L
private const val AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRIES = 20
private const val AUTOMATIC_SUGGESTION_WARMUP_BUSY_RETRY_MS = 500L

// 이미 따뜻한 공유 엔진에 다시 붙는 워밍업도 타이핑이 잠시 멈춘 뒤에 한다.
private const val AUTOMATIC_SUGGESTION_WARMUP_IDLE_MS = 1_500L
// 차가운 엔진의 워밍업이 키보드가 숨겨지기를 기다리며 확인하는 간격.
private const val AUTOMATIC_SUGGESTION_WARMUP_HIDDEN_POLL_MS = 500L

class FcitxInputMethodService : LifecycleInputMethodService() {

    suspend fun triggerInstantTypingDnaSyncAsync() = personalLearning.triggerInstantTypingDnaSyncAsync()

    val isDirectBootInputMode: Boolean
        get() = FcitxApplication.getInstance().isDirectBootMode

    private lateinit var fcitx: FcitxConnection
    private var fcitxEventCollectorJob: Job? = null
    private var fcitxEventCollectorRestartJob: Job? = null
    private var fcitxEventCollectorGeneration = 0L
    private var fcitxEventCollectorRestartRequest = 0L
    private var fcitxEventCollectorEngineGeneration = Long.MIN_VALUE
    private var readyFcitxEventCollectorGeneration = Long.MIN_VALUE
    private var observedFcitxEngineGeneration = Long.MIN_VALUE
    private val engineRestartEditorRehydrationGate = EngineRestartEditorRehydrationGate()

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0

    /**
     * Saves MetaState produced by hardware keyboard with "sticky" modifier keys, to clear them in order.
     * See also [InputConnection#clearMetaKeyStates(int)](https://developer.android.com/reference/android/view/inputmethod/InputConnection#clearMetaKeyStates(int))
     */
    private var lastMetaState: Int = 0

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
        if (isVirtualKeyboard) {
            hideStatusIcon()
        } else {
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        window.window?.let {
            navbarMgr.evaluate(it, isVirtualKeyboard)
        }
    }

    internal var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = 0x66008577 // material_deep_teal_500 with alpha 0.4

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor
    private val offlineMode by prefs.advanced.offlineMode
    private val autoSnippetExpansion by prefs.advanced.autoSnippetExpansion
    private val bufferedHangulInputPref = prefs.advanced.bufferedHangulInput
    private val bufferedHangulTransport by prefs.advanced.bufferedHangulTransport
    private val appProfileStore by lazy { AppKeyboardProfileStore(this) }
    @Volatile
    private var effectiveAppProfile: EffectiveAppKeyboardProfile? = null
    private val featurePolicy = InputFeaturePolicy(
        isDirectBootMode = { isDirectBootInputMode },
        editorInfo = { currentInputEditorInfo },
        capabilityFlags = { capabilityFlags },
        offlineMode = { offlineMode },
        appProfile = { effectiveAppProfile }
    )
    private var onDeviceContextSnapshotInvalidationListener: (() -> Unit)? = null
    private var nextOnDeviceContextExtractedTextToken = -1
    private var activeOnDeviceContextExtractedTextToken: Int? = null
    private var activeOnDeviceContextExtractedTextEpoch: Long? = null
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

    /** 마지막으로 에디터 선택이 바뀐 시각(elapsedRealtime). 워밍업을 입력이 멈춘 뒤로 미루는 데 쓴다. */
    @Volatile
    private var lastEditorActivityAtMs = 0L
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
    private val aiRuntimeStatusStore by lazy { AiRuntimeStatusStore(this) }
    private var appliedInputThemeName: String? = null

    private val bufferedHangul = BufferedInputController()
    private var bufferedHangulSessionActive = false
    @Volatile
    private var bufferedHangulEngineResetPending = false
    private val consumedPhysicalKeysDown = mutableSetOf<Int>()

    @Volatile
    private var snippetCatalog = SnippetCatalog.builtIns()
    private var snippetRefreshJob: Job? = null

    private val internalPrompt = InternalPromptController(
        InternalPromptController.Host(
            engineGeneration = { fcitx.engineGeneration.value },
            isEngineReady = { fcitx.runImmediately { isReady } },
            isEventCollectorReady = { isFcitxEventCollectorReady },
            eventCollectorEngineGeneration = { fcitxEventCollectorEngineGeneration },
            discardEventGeneration = ::discardFcitxEventGenerationForPromptSafety,
            postFcitxJob = ::postFcitxJob,
            lifecycleScope = { lifecycleScope },
            inputView = { inputView },
            editorInfo = { currentInputEditorInfo },
            selection = { currentInputSelection },
            inputSessionEpoch = { inputSessionEpoch },
            allowsFeature = { feature -> featurePolicy.allowsInternalPromptFeature(feature) },
            finishCompositionForDirectAction = ::finishCompositionForDirectAction,
            finishComposing = ::finishComposing,
            clearBufferedHangul = ::clearBufferedHangul,
            resetComposingState = ::resetComposingState,
            removeCachedKeyEvent = { timestamp -> cachedKeyEvents.remove(timestamp) }
        )
    )
    private var inputSessionEpoch = 0L

    private val personalLearning = PersonalLearningController(
        PersonalLearningController.Host(
            filesDir = { filesDir },
            collocationModel = { contextualPredictor.collocationModel },
            advancePredictionEpoch = { predictionEpoch++ },
            allowsTextInspection = ::allowsTextInspectionFeatures,
            editorInfo = { currentInputEditorInfo },
            inputConnection = { currentInputConnection },
            activePreedit = ::activePreeditForContextualInput,
            appPersona = { effectiveAppProfile?.source?.persona },
            isDestroyed = { lifecycle.currentState == Lifecycle.State.DESTROYED },
            lifecycleScope = { lifecycleScope },
            inputView = { inputView }
        )
    )

    /** A transient collection-progress hint for the keyboard's status row. */
    val collectionFeedback: StateFlow<CollectionFeedbackEvent?>
        get() = personalLearning.collectionFeedback

    val userTypingContextCollector: UserTypingContextCollector
        get() = personalLearning.userTypingContextCollector

    val recentSentSentences: RecentSentSentences
        get() = personalLearning.recentSentSentences

    private val typingDnaCommitSink: TypingDnaCommitSink
        get() = personalLearning.typingDnaCommitSink

    private val correctionSessionTracker: CorrectionSessionTracker
        get() = personalLearning.correctionSessionTracker

    private fun currentWordBeforeCursor(): String = personalLearning.currentWordBeforeCursor()

    private fun observeCommittedEditorText(text: String) = personalLearning.observeCommittedEditorText(text)

    val isInternalPromptCaptureActive: Boolean
        get() = internalPrompt.isInternalPromptCaptureActive

    fun isInternalPromptCaptureActive(token: Long): Boolean =
        internalPrompt.isInternalPromptCaptureActive(token)

    val isInternalPromptCaptureDraining: Boolean
        get() = internalPrompt.isInternalPromptCaptureDraining

    val isInternalPromptInputOwned: Boolean
        get() = internalPrompt.isInternalPromptInputOwned

    val isInternalPromptCaptureStarting: Boolean
        get() = internalPrompt.isInternalPromptCaptureStarting

    /** Monotonically changes at every Android editor-session boundary. */
    val currentInputSessionEpoch: Long
        get() = inputSessionEpoch

    val isInternalPromptSubmissionPending: Boolean
        get() = internalPrompt.isInternalPromptSubmissionPending

    internal fun insertInternalPromptDirectText(text: String): InternalPromptDirectCommitResult =
        internalPrompt.insertInternalPromptDirectText(text)

    /** Serializes candidate selection with virtual-key input and prompt submit fences. */
    fun selectCandidate(index: Int) {
        if (internalPrompt.invalidateStaleEngine()) return
        if (isInternalPromptCaptureStarting || isInternalPromptCaptureDraining ||
            isInternalPromptSubmissionPending
        ) return
        postFcitxJob { select(index) }
    }

    fun shouldRetainInternalPromptCapture(info: EditorInfo): Boolean =
        internalPrompt.shouldRetainInternalPromptCapture(info)

    /** Prepares a deterministic keyboard return path before an IME-owned settings activity. */
    fun prepareForSettingsActivity() {
        inputDeviceMgr.requestVirtualKeyboardOnNextStartInputView(currentInputEditorInfo)
        inputView?.prepareForSettingsActivity()
    }

    val bufferedHangulPrefix: String
        get() = if (bufferedHangulSessionActive) bufferedHangul.prefix else ""

    val isBufferedHangulSessionActive: Boolean
        get() = bufferedHangulSessionActive

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        // Key layouts are built once per keyboard instance, so the pinned number row can only be
        // added or removed by rebuilding the whole input view.
        prefs.keyboard.showNumberRow,
        // KeyView text sizes are also set once per key, at keyboard-build time.
        prefs.keyboard.keyTextScale,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private fun effectiveInputTheme(globalTheme: Theme = ThemeManager.activeTheme): Theme =
        effectiveAppProfile?.source?.themeName?.let { name ->
            ThemeManager.getAllThemes().firstOrNull { it.name == name }
        } ?: globalTheme

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        inputView = newInputView
        newInputView.updateAutomaticSuggestionIndicator(automaticSuggestionIndicatorInternal)
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        appliedInputThemeName = theme.name
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(effectiveInputTheme())
    }

    @Keep
    private val automaticSuggestionOptInListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        automaticSuggestionOptInRestored = false
        setAutomaticSuggestionsEnabled(enabled)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(effectiveInputTheme())
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        replaceInputViews(effectiveInputTheme(it))
    }

    private fun bufferedHangulModeActive(ime: InputMethodEntry): Boolean =
        BufferedHangulMode.isActive(bufferedHangulInputPref.getValue(), ime)

    private fun effectiveCapabilityFlags(
        flags: CapabilityFlags,
        ime: InputMethodEntry
    ): CapabilityFlags = BufferedHangulMode.effectiveCapabilities(
        flags,
        bufferedHangulInputPref.getValue(),
        ime
    )

    @Keep
    private val bufferedHangulInputListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (isInternalPromptInputOwned) {
            // A settings change must not flush a stale buffered segment into the editor while an
            // internal prompt owns the keyboard. The prompt drain will reset Fcitx separately.
            discardBufferedHangulForInternalPrompt()
        } else {
            // Finish any editor-owned composing span before changing where preedit is rendered.
            currentInputConnection?.finishComposingText()
            resetComposingState()
            if (!enabled && bufferedHangulSessionActive) {
                submitBufferedHangul()
            }
        }
        bufferedHangulSessionActive = bufferedHangulModeActive(
            fcitx.runImmediately { inputMethodEntryCached }
        )
        if (!bufferedHangulSessionActive) clearBufferedHangul()
        postFcitxJob {
            setCapFlags(effectiveCapabilityFlags(capabilityFlags, inputMethodEntryCached))
        }
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        if (jobs.trySend(job).isFailure) job.cancel()
        return job
    }

    /** Starts a fresh, replay-free event subscription for the current Fcitx engine generation. */
    private fun startFcitxEventCollector() {
        if (fcitxEventCollectorJob?.isActive == true) return
        val generation = ++fcitxEventCollectorGeneration
        val engineGeneration = fcitx.engineGeneration.value
        fcitxEventCollectorEngineGeneration = engineGeneration
        readyFcitxEventCollectorGeneration = Long.MIN_VALUE
        fcitxEventCollectorJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.runImmediately { eventFlow }.onSubscription {
                if (generation == fcitxEventCollectorGeneration &&
                    engineGeneration == fcitx.engineGeneration.value
                ) {
                    readyFcitxEventCollectorGeneration = generation
                }
            }.collect {
                if (generation != fcitxEventCollectorGeneration) return@collect
                if (engineGeneration != fcitx.engineGeneration.value) {
                    // A restart can move STOPPING -> STOPPED -> READY before StateFlow collectors
                    // run. Never route a new-engine event through an old prompt/collector epoch.
                    discardFcitxEventGenerationForPromptSafety(engineRestart = true)
                    return@collect
                }
                handleFcitxEvent(it)
            }
        }
    }

    private val isFcitxEventCollectorReady: Boolean
        get() = fcitxEventCollectorJob?.isActive == true &&
            readyFcitxEventCollectorGeneration == fcitxEventCollectorGeneration &&
            fcitxEventCollectorEngineGeneration == fcitx.engineGeneration.value

    /**
     * Subscribes only once the current engine has fully reached READY.
     *
     * The engine generation advances only after Fcitx.stop() returned from its native dispatcher.
     * Re-subscribing earlier could give an old native callback a new collector epoch.
     */
    private fun restartFcitxEventCollectorWhenReady() {
        val request = ++fcitxEventCollectorRestartRequest
        val engineGeneration = fcitx.engineGeneration.value
        fcitxEventCollectorRestartJob?.cancel()
        fcitxEventCollectorRestartJob = lifecycleScope.launch {
            fcitx.runOnReady { }
            if (request == fcitxEventCollectorRestartRequest &&
                engineGeneration == fcitx.engineGeneration.value
            ) {
                val restored = restoreEditorAfterFcitxEngineRestart(
                    engineRestartEditorRehydrationGate.claimReady(engineGeneration)
                )
                if (restored && request == fcitxEventCollectorRestartRequest &&
                    engineGeneration == fcitx.engineGeneration.value
                ) {
                    startFcitxEventCollector()
                }
            }
        }
    }

    /**
     * A daemon-only engine restart creates a fresh native AndroidFrontend without another Android
     * bind/start callback. Recreate its InputContext before events or keys are allowed through.
     */
    private suspend fun restoreEditorAfterFcitxEngineRestart(
        plan: EngineRestartEditorRehydrationGate.Plan?
    ): Boolean {
        val engineStillReady = {
            fcitx.runImmediately { isReady }
        }
        if (plan == null) return engineStillReady()
        if (plan.engineGeneration != fcitx.engineGeneration.value || !engineStillReady()) {
            return false
        }
        // A start-input-view callback may refine EditorInfo without issuing a second bind/start.
        // Resolve the claim again so its native restore targets that latest same-generation field.
        val currentPlan = engineRestartEditorRehydrationGate.refreshClaimedPlan(plan) ?: return true
        val failure = AtomicReference<Throwable?>()
        val appliedPlan = AtomicReference<EngineRestartEditorRehydrationGate.Plan?>()
        val restoreJob = postFcitxJob {
            // The Android editor may have changed while this job waited behind another operation.
            val latestPlan = engineRestartEditorRehydrationGate.refreshClaimedPlan(currentPlan)
                ?: return@postFcitxJob
            if (!isReady || latestPlan.engineGeneration != fcitx.engineGeneration.value
            ) return@postFcitxJob
            appliedPlan.set(latestPlan)
            activate(latestPlan.uid, latestPlan.packageName)
            latestPlan.inputMethodUniqueName?.takeIf { it.isNotBlank() }?.let { activateIme(it) }
            setCandidatePagingMode(if (latestPlan.isVirtualKeyboard) 0 else 1)
            setCapFlags(effectiveCapabilityFlags(latestPlan.capabilityFlags, inputMethodEntryCached))
            if (latestPlan.shouldFocus) focus(true)
        }
        restoreJob.invokeOnCompletion { cause -> failure.set(cause) }
        restoreJob.join()
        if (plan.engineGeneration != fcitx.engineGeneration.value || !engineStillReady()) {
            return false
        }
        val cause = failure.get()
        if (cause == null) return true
        Timber.w(cause, "Fcitx editor rehydration did not complete")
        if (engineRestartEditorRehydrationGate.retryAfterFailedRestore(appliedPlan.get() ?: currentPlan)) {
            restartFcitxEventCollectorWhenReady()
        }
        return false
    }

    /**
     * Drops the old event subscription before releasing a prompt gate.
     *
     * Once the collector is cancelled, any callback that predates a failed marker or a restart is
     * deliberately never routed through a later editor. Replacement collection waits for the
     * current engine's READY boundary, where its replay-free SharedFlow is safe to subscribe.
     */
    private fun discardFcitxEventGenerationForPromptSafety(engineRestart: Boolean = false) {
        // Invalidate before cancellation: an in-flight collector callback must not write after
        // the prompt gate becomes idle.
        val engineGeneration = fcitx.engineGeneration.value
        observedFcitxEngineGeneration = engineGeneration
        if (engineRestart) {
            engineRestartEditorRehydrationGate.requestForEngineGeneration(engineGeneration)
            discardLocalInputStateForFcitxEngineRestart()
        }
        fcitxEventCollectorGeneration += 1
        fcitxEventCollectorRestartJob?.cancel()
        fcitxEventCollectorRestartJob = null
        fcitxEventCollectorEngineGeneration = Long.MIN_VALUE
        readyFcitxEventCollectorGeneration = Long.MIN_VALUE
        fcitxEventCollectorJob?.cancel()
        fcitxEventCollectorJob = null
        internalPrompt.resetForEngineRestart()
        restartFcitxEventCollectorWhenReady()
    }

    /** Never submit a composition that belonged to an engine instance that no longer exists. */
    private fun discardLocalInputStateForFcitxEngineRestart() {
        bufferedHangul.clear()
        bufferedHangulEngineResetPending = false
        resetComposingState()
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        consumedPhysicalKeysDown.clear()
        cursorUpdateIndex = 0
    }

    override fun onCreate() {
        activeInstance = this
        OnDeviceGenerationControl.onServiceStarted()
        fcitx = FcitxDaemon.connect(javaClass.name)
        observedFcitxEngineGeneration = fcitx.engineGeneration.value
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        restartFcitxEventCollectorWhenReady()
        lifecycleScope.launch {
            fcitx.engineGeneration.collect { generation ->
                if (generation != observedFcitxEngineGeneration) {
                    observedFcitxEngineGeneration = generation
                    // The monotonically increasing boundary survives StateFlow state conflation,
                    // so active, starting, and draining prompts all die with their old engine.
                    discardFcitxEventGenerationForPromptSafety(engineRestart = true)
                }
            }
        }
        pkgNameCache = PackageNameCache(this)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        bufferedHangulInputPref.registerOnChangeListener(bufferedHangulInputListener)
        prefs.internal.automaticOnDeviceSuggestionsOptIn.registerOnChangeListener(automaticSuggestionOptInListener)
        OnDeviceGenerationControl.configureAutoContextPreemption(
            ::isAutomaticSuggestionBusyForPreemption,
            ::preemptAutomaticSuggestionForExplicitContext,
            ::resumeAutomaticSuggestionAfterExplicitContext
        )
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        observeSentencePackRevision()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = resources.configuration
        refreshSnippetCatalog()
        personalLearning.attachTypingDnaBatchCompiler()
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.InternalPromptStartBarrier -> {
                internalPrompt.deliverInternalPromptStartFence(event.data)
            }
            is FcitxEvent.InternalPromptDrainBarrier -> {
                internalPrompt.deliverInternalPromptDrainFence(event.data)
            }
            is FcitxEvent.InternalPromptSubmitBarrier -> {
                internalPrompt.deliverInternalPromptSubmitFence(event.data)
            }
            is FcitxEvent.InternalPromptDirectCommitBarrier -> {
                internalPrompt.deliverInternalPromptDirectCommit(
                    event.data.token,
                    event.data.sequence,
                    event.data.text
                )
            }
            is FcitxEvent.CommitStringEvent -> {
                val snippetBoundary = boundaryForText(event.data.text)
                if (internalPrompt.captureInternalPromptCommit(event.data.text)) {
                    // Internal prompt capture owns this commit; never forward it to the target editor.
                } else if (isInternalPromptCaptureStarting) {
                    // This callback was already ahead of the start marker. It belongs to the
                    // original editor, but must bypass normal direct-UI gates that freeze new
                    // input while the prompt is waiting for that marker.
                    commitFcitxEventToEditor(event.data.text, event.data.cursor)
                } else if (snippetBoundary != null && tryExpandSnippet(snippetBoundary)) {
                    // The boundary is part of the atomic snippet replacement.
                } else if (beginDynamicPhrasePreview(event.data.text)) {
                    // Dynamic templates are committed only from their explicit preview action.
                } else if (bufferedHangulSessionActive) {
                    bufferedHangul.capture(event.data.text)
                } else {
                    commitToEditor(event.data.text, event.data.cursor)
                }
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (internalPrompt.handleInternalPromptForwardedKey(it)) return@event
                if (handleBufferedHangulForwardedKey(it)) return@event
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> {
                            if (!tryExpandSnippet(SnippetBoundary.Enter)) handleReturnKey()
                        }
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        else -> if (it.unicode > 0) {
                            val text = Character.toString(it.unicode)
                            if (isInternalPromptCaptureStarting) {
                                commitFcitxEventToEditor(text)
                            } else {
                                val boundary = boundaryForText(text)
                                if (boundary == null || !tryExpandSnippet(boundary)) {
                                    commitToEditor(text)
                                }
                            }
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        /**
                         * intercept the KeyEvent which would cause the default [android.text.method.QwertyKeyListener]
                         * to show a Gingerbread-style CharacterPickerDialog
                         */
                        if (keyEvent.unicodeChar == KeyCharacterMap.PICKER_DIALOG_INPUT.code) {
                            currentInputConnection?.sendKeyEvent(
                                KeyEvent(
                                    keyEvent.downTime, keyEvent.eventTime,
                                    keyEvent.action, keyEvent.keyCode,
                                    keyEvent.repeatCount, keyEvent.metaState, -1,
                                    keyEvent.scanCode, keyEvent.flags, keyEvent.source
                                )
                            )
                            return@event
                        }
                        val physicalBoundary = if (keyEvent.action == KeyEvent.ACTION_DOWN &&
                            !keyEvent.isCtrlPressed && !keyEvent.isAltPressed &&
                            !keyEvent.isMetaPressed
                        ) {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_SPACE -> SnippetBoundary.Space
                                KeyEvent.KEYCODE_ENTER -> SnippetBoundary.Enter
                                else -> null
                            }
                        } else {
                            null
                        }
                        if (physicalBoundary != null && tryExpandSnippet(physicalBoundary)) {
                            consumedPhysicalKeysDown.add(it.sym.sym)
                            return@event
                        }
                        if (keyEvent.keyCode == KeyEvent.KEYCODE_DEL &&
                            keyEvent.action == KeyEvent.ACTION_DOWN &&
                            allowsTextInspectionFeatures()
                        ) {
                            correctionSessionTracker.onBackspace(currentWordBeforeCursor())
                        }
                        if (keyEvent.action == KeyEvent.ACTION_DOWN &&
                            (keyEvent.keyCode == KeyEvent.KEYCODE_DEL ||
                                keyEvent.keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
                        ) {
                            val dnaInspectionAllowed = allowsTextInspectionFeatures()
                            val dnaRemovedText = if (
                                keyEvent.keyCode == KeyEvent.KEYCODE_DEL && dnaInspectionAllowed
                            ) {
                                currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
                            } else {
                                null
                            }
                            typingDnaCommitSink.onEditorContinuityLost(
                                currentInputEditorInfo?.packageName,
                                dnaRemovedText,
                                dnaInspectionAllowed
                            )
                        }
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                            personalLearning.observeForwardedKeyIfPrintable(keyEvent.keyCode, keyEvent.unicodeChar, keyEvent.metaState)
                        }
                        if (KeyEvent.isModifierKey(keyEvent.keyCode)) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    // save current metaState when modifier key down
                                    lastMetaState = keyEvent.metaState
                                }
                                KeyEvent.ACTION_UP -> {
                                    // only clear metaState that would be missing when this modifier key up
                                    currentInputConnection?.clearMetaKeyStates(lastMetaState xor keyEvent.metaState)
                                    lastMetaState = keyEvent.metaState
                                }
                            }
                        }
                        return@event
                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        val simulatedBoundary = if (!it.up) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_SPACE -> SnippetBoundary.Space
                                KeyEvent.KEYCODE_ENTER -> SnippetBoundary.Enter
                                else -> null
                            }
                        } else {
                            null
                        }
                        if (simulatedBoundary != null && tryExpandSnippet(simulatedBoundary)) {
                            if (!it.states.virtual) consumedPhysicalKeysDown.add(it.sym.sym)
                            return@event
                        }
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            val text = Character.toString(it.unicode)
                            if (isInternalPromptCaptureStarting) {
                                commitFcitxEventToEditor(text)
                            } else {
                                commitToEditor(text)
                            }
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                notifyAutomaticSuggestionPreeditChanged(clientPreedit = event.data.toString())
                if (!internalPrompt.updateInternalPromptPreedit(event.data.toString())) {
                    updateComposingText(event.data)
                }
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                if (!internalPrompt.deleteInternalPromptBeforeCursor(before)) {
                    handleDeleteSurrounding(before, after)
                }
            }
            is FcitxEvent.InputPanelEvent -> {
                notifyAutomaticSuggestionPreeditChanged(inputPanelPreedit = event.data.preedit.toString())
                if (isInternalPromptCaptureActive && bufferedHangulSessionActive) {
                    internalPrompt.updateInternalPromptPreedit(event.data.preedit.toString())
                }
            }
            is FcitxEvent.IMChangeEvent -> {
                engineRestartEditorRehydrationGate.onInputMethodChanged(event.data.uniqueName)
                val wasBufferedHangul = bufferedHangulSessionActive
                val isBufferedHangul = bufferedHangulModeActive(event.data)
                if (wasBufferedHangul && !isBufferedHangul) {
                    submitBufferedHangul()
                }
                bufferedHangulSessionActive = isBufferedHangul
                if (!wasBufferedHangul || !isBufferedHangul) clearBufferedHangul()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    SubtypeManager.subtypeOf(im)?.let { subtype ->
                        skipNextSubtypeChange = im
                        // [^1]: notify system that input method subtype has changed
                        switchInputMethod(InputMethodUtil.componentName, subtype)
                    }
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.data))
                }
                postFcitxJob {
                    setCapFlags(effectiveCapabilityFlags(capabilityFlags, event.data))
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
                ) {
                    if (inputDeviceMgr.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        if (before > 0 || after > 0) {
            val dnaInspectionAllowed = allowsTextInspectionFeatures()
            val dnaRemovedText = if (before > 0 && dnaInspectionAllowed) {
                ic.getTextBeforeCursor(before, 0)?.toString()
            } else {
                null
            }
            typingDnaCommitSink.onEditorContinuityLost(
                currentInputEditorInfo?.packageName,
                dnaRemovedText,
                dnaInspectionAllowed
            )
        }
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
        if (before > 0 || after > 0) {
            notifyOnDeviceContextSnapshotInvalidated()
        }
        inputView?.postRefreshContextualCandidates(16L)
    }

    private fun handleBackspaceKey() {
        if (internalPrompt.deleteInternalPromptBeforeCursor(1)) return
        notifyOnDeviceContextSnapshotInvalidated()
        val dnaInspectionAllowed = allowsTextInspectionFeatures()
        val dnaRemovedText = if (dnaInspectionAllowed) {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
        } else {
            null
        }
        typingDnaCommitSink.onEditorContinuityLost(
            currentInputEditorInfo?.packageName,
            dnaRemovedText,
            dnaInspectionAllowed
        )
        if (dnaInspectionAllowed) {
            correctionSessionTracker.onBackspace(currentWordBeforeCursor())
        }
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        // In practice nobody (apart from ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                currentInputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            currentInputConnection.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        if (isInternalPromptCaptureActive) {
            inputView?.submitInternalPromptInput()
            return
        }
        personalLearning.flushTypingDnaForCurrentEditor()
        personalLearning.finalizeCorrectionSessionAtBoundary()
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                personalLearning.observeForwardedKeyIfPrintable(KeyEvent.KEYCODE_ENTER, 0, 0)
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                    personalLearning.observeForwardedKeyIfPrintable(KeyEvent.KEYCODE_ENTER, 0, 0)
                }
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    private fun handleArrowKey(keyCode: Int) {
        val type = currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = currentInputEditorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (type == InputType.TYPE_NULL ||
            // confirm URL suggestion in browser location bar, see also https://bugzilla.mozilla.org/show_bug.cgi?id=1999915
            type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            sendDownUpKeyEvents(keyCode)
            return
        }
        val (start, end) = currentInputSelection
        val offset = if (start == end) 1 else 0
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> start - offset
            KeyEvent.KEYCODE_DPAD_RIGHT -> end + offset
            else -> return
        }
        notifyOnDeviceContextSnapshotInvalidated()
        currentInputConnection.setSelection(target, target)
    }

    private enum class SnippetBoundary(val suffix: String, val consumeOnFailure: Boolean) {
        Space(" ", false),
        // A recognized snippet must never be sent to a chat accidentally because its personal
        // profile value is missing. The first Enter expands (or reports the issue); a second one
        // performs the editor action.
        Enter("", true)
    }

    private fun boundaryForText(text: String): SnippetBoundary? = when (text) {
        " " -> SnippetBoundary.Space
        "\n", "\r" -> SnippetBoundary.Enter
        else -> null
    }

    private fun refreshSnippetCatalog() {
        snippetRefreshJob?.cancel()
        if (!DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootInputMode)) {
            snippetRefreshJob = null
            snippetCatalog = SnippetCatalog.builtIns()
            return
        }
        snippetRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            snippetCatalog = runCatching { SnippetRepository.load() }
                .onFailure { Timber.w(it, "Unable to refresh snippet catalog") }
                .getOrElse { SnippetCatalog.builtIns() }
        }
    }

    private fun resolveSnippetTemplate(template: String): String? {
        val clipboard = ClipboardManager.lastEntry
        val result = DynamicPhraseTemplate.expand(
            template,
            DynamicPhraseValues(
                now = ZonedDateTime.now(),
                profile = DynamicPhraseProfileStore(this).load(),
                clipboardText = clipboard?.takeUnless { it.sensitive }?.text,
                clipboardSensitive = clipboard?.sensitive == true,
                privateEditor = false
            )
        )
        if (!result.canInsert) {
            Toast.makeText(this, R.string.snippet_missing_value, Toast.LENGTH_SHORT).show()
            return null
        }
        return result.text
    }

    private fun tryExpandSnippet(boundary: SnippetBoundary): Boolean {
        // A boundary ahead of an internal-prompt start marker is historical editor input. Do not
        // let it become a deferred direct action while starting freezes new editor writes.
        if (isInternalPromptCaptureStarting || !autoSnippetExpansion || !allowsTextInspectionFeatures() ||
            currentInputSelection.isNotEmpty()
        ) return false
        return if (bufferedHangulSessionActive) {
            tryExpandBufferedSnippet(boundary)
        } else {
            tryExpandEditorSnippet(boundary)
        }
    }

    private fun tryExpandEditorSnippet(boundary: SnippetBoundary): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString() ?: return false
        val initialPlan = snippetCatalog.plan(before) ?: return false
        val expanded = resolveSnippetTemplate(initialPlan.template)
            ?: return boundary.consumeOnFailure
        if (!finishCompositionForDirectAction()) return boundary.consumeOnFailure

        // Finishing a composing span can change what the editor exposes. Match again and only
        // mutate when the same trigger/template still ends exactly at the cursor.
        val verifiedBefore = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString()
            ?: return boundary.consumeOnFailure
        val plan = snippetCatalog.plan(verifiedBefore)
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
        Toast.makeText(this, R.string.snippet_insert_failed, Toast.LENGTH_SHORT).show()
        return boundary.consumeOnFailure
    }

    private fun tryExpandBufferedSnippet(boundary: SnippetBoundary): Boolean {
        val ic = currentInputConnection ?: return false
        val originalStart = selection.latest.start
        val originalEnd = selection.latest.end
        if (originalStart != originalEnd) return false
        val editorBefore = ic.getTextBeforeCursor(SNIPPET_CONTEXT_CHARS, 0)?.toString()
            ?: return false
        val currentPreedit = if (bufferedHangulEngineResetPending) {
            ""
        } else {
            fcitx.runImmediately { inputPanelCached.preedit.toString() }
        }
        val pending = bufferedHangul.snapshot(currentPreedit)
        val plan = snippetCatalog.plan(editorBefore, pending) ?: return false
        val expanded = resolveSnippetTemplate(plan.template)
            ?: return boundary.consumeOnFailure
        val selectionStart = originalStart - plan.deleteBeforeCursor
        if (selectionStart < 0 || !ic.setSelection(selectionStart, originalEnd)) {
            return boundary.consumeOnFailure
        }
        selection.resetTo(selectionStart, originalEnd)
        val replacement = plan.replacement(expanded, boundary.suffix)
        val dispatched = dispatchBufferedText(replacement)
        if (dispatched) {
            bufferedHangul.clear()
            queueBufferedHangulEngineReset()
            inputView?.refreshBufferedHangulPreedit()
            selection.resetTo(selectionStart + replacement.length)
            return true
        }

        ic.setSelection(originalStart, originalEnd)
        selection.resetTo(originalStart, originalEnd)
        Toast.makeText(this, R.string.snippet_insert_failed, Toast.LENGTH_SHORT).show()
        return boundary.consumeOnFailure
    }

    /**
     * Commits reviewed output to the app editor that opened this IME.
     *
     * Native Fcitx events are captured separately by
     * [InternalPromptController.captureInternalPromptCommit]. Returning
     * false while a prompt owns input is intentional: an editor-targeted action must never look
     * successful when its target is being isolated or drained.
     */
    fun commitToEditor(text: String, cursor: Int = -1): Boolean {
        if (isInternalPromptInputOwned) return false
        // Clipboard entries, emoji, and toolbar actions bypass Fcitx's CommitString event. Flush
        // the internal Hangul segment first so those direct inserts cannot overtake it.
        if (bufferedHangulSessionActive && !submitBufferedHangul()) return false
        return commitTextToEditor(text, cursor)
    }

    /**
     * Delivers an already ordered Fcitx callback that predates a prompt's start marker.
     *
     * Starting intentionally freezes *new* UI and hardware input. It must not retroactively
     * drop an engine callback that was queued before the marker, so this is the sole path that
     * may write while starting. Active and draining prompts remain fail-closed.
     */
    private fun commitFcitxEventToEditor(text: String, cursor: Int = -1): Boolean {
        if (internalPrompt.ownsInput) return false
        if (bufferedHangulSessionActive) {
            bufferedHangul.capture(text)
            return submitBufferedHangul(allowPromptStart = isInternalPromptCaptureStarting)
        }
        return commitTextToEditor(
            text = text,
            cursor = cursor,
            allowPromptStart = isInternalPromptCaptureStarting
        )
    }

    /** Inserts IME-window text into the app editor; it never crosses an active prompt boundary. */
    fun insertImeText(text: String, cursor: Int = -1): Boolean = commitToEditor(text, cursor)

    fun beginInternalPromptCapture(
        spec: InternalPromptSpec,
        initialText: String,
        onStarted: (token: Long) -> Unit,
        onChanged: (token: Long, committed: String, preedit: String) -> Unit
    ): Long? = internalPrompt.beginInternalPromptCapture(spec, initialText, onStarted, onChanged)

    internal fun finishInternalPromptCapture(): InternalPromptFinishResult =
        internalPrompt.finishInternalPromptCapture()

    fun cancelInternalPromptCapture(discardPreStartCallbacks: Boolean = false) =
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks)

    /**
     * Consume a dynamic quick-phrase commit and replace it with a frozen preview. Failure is
     * final: never insert the unresolved template as an implicit fallback.
     */
    private fun beginDynamicPhrasePreview(template: String): Boolean {
        if (!DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootInputMode)) {
            return false
        }
        if (!DynamicPhraseTemplate.containsSupportedToken(template)) return false
        if (!prepareDynamicPhrasePreview()) return true
        val view = inputView
        if (view == null) {
            Timber.w("Unable to preview a dynamic phrase without an input view")
            return true
        }
        val info = currentInputEditorInfo
        val currentSelection = currentInputSelection
        view.showDynamicPhrasePreview(
            template,
            DynamicPhraseEditorTarget(
                packageName = info.packageName,
                fieldId = info.fieldId,
                inputType = info.inputType,
                selectionStart = currentSelection.start,
                selectionEnd = currentSelection.end
            )
        )
        return true
    }

    /**
     * A quick-phrase commit replaces its trigger preedit. Remove that preedit instead of finishing
     * it as literal text, while preserving any Hangul segment owned by buffered compatibility mode.
     */
    private fun prepareDynamicPhrasePreview(): Boolean {
        val ic = currentInputConnection ?: return false
        if (bufferedHangulSessionActive) {
            if (!submitBufferedHangul()) return false
        } else if (composing.isNotEmpty()) {
            val start = composing.start
            resetComposingState()
            selection.predict(start)
            var dispatched = true
            ic.withBatchEdit {
                dispatched = commitText("", 1) && dispatched
                dispatched = finishComposingText() && dispatched
            }
            if (!dispatched) return false
        }
        postFcitxJob { reset() }
        return true
    }

    fun allowsTextInspectionFeatures(): Boolean = featurePolicy.allowsTextInspectionFeatures()

    fun allowsNetworkInputFeatures(): Boolean = featurePolicy.allowsNetworkInputFeatures()

    fun allowsOnDeviceContextCompletionFeatures(): Boolean =
        featurePolicy.allowsOnDeviceContextCompletionFeatures()

    /** Only the active local completion window may receive invalidation events. */
    fun setOnDeviceContextSnapshotInvalidationListener(listener: (() -> Unit)?) {
        check(listener == null || onDeviceContextSnapshotInvalidationListener == null ||
            onDeviceContextSnapshotInvalidationListener === listener) {
            "On-device context completion already has an active listener"
        }
        if (listener == null) clearOnDeviceContextExtractedTextMonitor()
        onDeviceContextSnapshotInvalidationListener = listener
        clearAutomaticSuggestionPreeditReferences()
        notifyAutomaticSuggestionSnapshotInvalidated()
    }

    private fun notifyOnDeviceContextSnapshotInvalidated() {
        clearOnDeviceContextExtractedTextMonitor()
        onDeviceContextSnapshotInvalidationListener?.invoke()
        clearAutomaticSuggestionPreeditReferences()
        notifyAutomaticSuggestionSnapshotInvalidated()
    }

    private fun beginOnDeviceContextExtractedTextMonitor(epoch: Long): ExtractedTextRequest {
        val token = nextOnDeviceContextExtractedTextToken
        nextOnDeviceContextExtractedTextToken = if (token == Int.MIN_VALUE) -1 else token - 1
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

    private fun notifyAutomaticSuggestionSnapshotInvalidated() {
        clearAutomaticSuggestionExtractedTextMonitor()
        automaticSuggestionRevision += 1
        automaticSuggestionInvalidationListener?.invoke()
    }

    private fun beginAutomaticSuggestionExtractedTextMonitor(epoch: Long): ExtractedTextRequest {
        val token = nextOnDeviceContextExtractedTextToken
        nextOnDeviceContextExtractedTextToken = if (token == Int.MIN_VALUE) -1 else token - 1
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

    private fun notifyAutomaticSuggestionPreeditChanged(
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

    fun networkInputBlock(): InputFeatureBlock? = featurePolicy.networkInputBlock()


    fun effectiveMobileHangulLayout(global: MobileHangulLayout): MobileHangulLayout =
        effectiveAppProfile?.source?.mobileHangulLayout ?: global

    fun effectiveToolbarExpanded(global: Boolean): Boolean = when (
        effectiveAppProfile?.source?.toolbarVisibility
    ) {
        AppToolbarVisibility.Expanded -> true
        AppToolbarVisibility.Collapsed -> false
        else -> global
    }

    fun updateCurrentAppMobileHangulLayout(layout: MobileHangulLayout): Boolean {
        val effective = effectiveAppProfile ?: return false
        val source = effective.source ?: return false
        return runCatching {
            val updated = source.copy(mobileHangulLayout = layout)
            appProfileStore.upsert(updated)
            effectiveAppProfile = effective.copy(source = updated, mobileHangulLayout = layout)
        }.isSuccess
    }

    private fun effectiveBufferedInputTransport(): BufferedInputTransport =
        effectiveAppProfile?.source?.bufferedInputTransport ?: bufferedHangulTransport

    private fun resolveAndApplyAppKeyboardProfile(info: EditorInfo, flags: CapabilityFlags) {
        if (!DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootInputMode)) {
            effectiveAppProfile = null
            return
        }
        val globalTheme = ThemeManager.activeTheme
        val resolved = AppKeyboardProfileResolver.resolve(
            packageName = info.packageName,
            profiles = appProfileStore.profiles(),
            defaults = AppKeyboardGlobalDefaults(
                mobileHangulLayout = prefs.keyboard.mobileHangulLayout.getValue(),
                themeName = globalTheme.name,
                toolbarExpanded = prefs.keyboard.expandToolbarByDefault.getValue(),
                bufferedInputTransport = prefs.advanced.bufferedHangulTransport.getValue(),
                offlineMode = prefs.advanced.offlineMode.getValue()
            ),
            privateEditor = EditorPrivacyPolicy.forbidsTextInspection(info, flags)
        )
        val targetTheme = ThemeManager.getAllThemes().firstOrNull { it.name == resolved.themeName }
            ?: globalTheme
        effectiveAppProfile = resolved.copy(themeName = targetTheme.name)
        if (::contentView.isInitialized && inputView != null && appliedInputThemeName != targetTheme.name) {
            replaceInputViews(targetTheme)
        }
    }

    /** Uses native selection replacement so a failed commit cannot first delete the source. */
    private fun replaceAiRange(
        connection: android.view.inputmethod.InputConnection,
        start: Int,
        end: Int,
        replacement: String,
        restoreStart: Int,
        restoreEnd: Int
    ): Boolean = AiEditorTransaction.replaceRange(
        start = start,
        end = end,
        replacement = replacement,
        restoreStart = restoreStart,
        restoreEnd = restoreEnd,
        setSelection = connection::setSelection,
        commitText = { text -> connection.commitText(text, 1) },
        confirmCommit = { text ->
            confirmsAiTextCommit(
                connection = connection,
                expectedText = text,
                expectedCursor = start + text.length
            )
        }
    )

    private fun commitAiTextAtCursor(
        connection: android.view.inputmethod.InputConnection,
        cursor: Int,
        text: String,
        restoreStart: Int,
        restoreEnd: Int
    ): Boolean = AiEditorTransaction.commitAtCursor(
        cursor = cursor,
        text = text,
        restoreStart = restoreStart,
        restoreEnd = restoreEnd,
        setSelection = connection::setSelection,
        commitText = { committed -> connection.commitText(committed, 1) },
        confirmCommit = { committed ->
            confirmsAiTextCommit(
                connection = connection,
                expectedText = committed,
                expectedCursor = cursor + committed.length
            )
        }
    )

    /**
     * Confirms that the asynchronous editor command changed the currently visible input before
     * exposing Undo. Android's remote InputConnection returns true when Binder accepted a command
     * even when the target editor later rejects it, so transport acknowledgement alone is unsafe.
     *
     * The check reads at most the same 4k character bound allowed for AI capture. A non-empty
     * insertion must appear as the exact visible tail. A deletion has no tail to compare, so it
     * instead requires its selected range to collapse at the exact cursor. Editors with complete
     * extraction must report that cursor; other editors must expose no remaining selected text.
     * Any unconfirmable state is deliberately treated as a failed apply rather than falling back
     * to clipboard.
     */
    private fun confirmsAiTextCommit(
        connection: android.view.inputmethod.InputConnection,
        expectedText: String,
        expectedCursor: Int
    ): Boolean {
        if (expectedText.length > AiTextSource.MAX_CHARACTERS) {
            return false
        }
        if (expectedText.isNotEmpty()) {
            val visibleTail = runCatching {
                connection.getTextBeforeCursor(expectedText.length, 0)?.toString()
            }.getOrNull()
            if (visibleTail != expectedText) return false
        }

        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        if (extracted != null && extracted.startOffset == 0 && extracted.partialStartOffset == -1) {
            return extracted.selectionStart == expectedCursor && extracted.selectionEnd == expectedCursor
        }

        val selected = runCatching { connection.getSelectedText(0)?.toString() }
        return selected.isSuccess && selected.getOrNull().isNullOrEmpty()
    }

    private val AiEditorTarget.identity: EditorIdentity
        get() = EditorIdentity(packageName, fieldId, inputType)

    private val AiEditorTarget.selection: EditorSelection
        get() = EditorSelection(selectionStart, selectionEnd)

    /** Canonical "is this still the same editor field" check: identity, then selection, then an optional session epoch. */
    fun matchesCurrentEditor(
        identity: EditorIdentity,
        selection: EditorSelection,
        expectedInputSessionEpoch: Long? = null
    ): Boolean =
        EditorIdentity.of(currentInputEditorInfo).sameField(identity) &&
            currentInputSelection.rangeEquals(selection.start, selection.end) &&
            (expectedInputSessionEpoch == null || inputSessionEpoch == expectedInputSessionEpoch)

    /**
     * Commit the current Hangul/composing segment before a rich-content transaction. Returning
     * false is final: callers must not attach content or silently fall back to inserting a URL.
     */
    fun prepareRichContentCommit(): Boolean {
        if (!allowsNetworkInputFeatures()) return false
        return finishCompositionForDirectAction()
    }

    /** Finishes composition for user-selected, entirely on-device OCR without requiring network. */
    fun prepareOcrCommit(): Boolean {
        if (!allowsTextInspectionFeatures()) return false
        return finishCompositionForDirectAction()
    }

    /** Captures only the explicitly selected or cursor-adjacent Korean headword for local lookup. */
    fun captureKoreanDictionaryQuery(): String? {
        if (!allowsTextInspectionFeatures()) return null
        if (!finishCompositionForDirectAction()) return null
        val connection = currentInputConnection ?: return null
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
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) return null
        if (!finishCompositionForDirectAction()) return null
        val info = currentInputEditorInfo
        val cursor = currentInputSelection.start
        if (cursor < 0) return null
        val tail = currentInputConnection?.getTextBeforeCursor(
            KOREAN_PARTICLE_CONTEXT_CHARACTERS,
            0
        )?.toString() ?: return null
        val suggestions = KoreanParticleSuggester.suggest(tail)
        if (suggestions.isEmpty()) return null
        return KoreanParticleSnapshot(
            editor = KoreanParticleEditorTarget(
                packageName = info.packageName,
                fieldId = info.fieldId,
                inputType = info.inputType,
                cursor = cursor
            ),
            contextTail = tail,
            suggestions = suggestions
        )
    }

    /** Inserts one explicitly selected particle. A stale editor/context fails without fallback. */
    fun commitKoreanParticle(snapshot: KoreanParticleSnapshot, text: String): Boolean {
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) return false
        val info = currentInputEditorInfo
        val currentEditor = KoreanParticleEditorTarget(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            cursor = currentInputSelection.start
        )
        if (currentEditor != snapshot.editor || snapshot.suggestions.none { it.text == text }) {
            return false
        }
        if (!finishCompositionForDirectAction()) return false
        val connection = currentInputConnection ?: return false
        val currentTail = connection.getTextBeforeCursor(
            KOREAN_PARTICLE_CONTEXT_CHARACTERS,
            0
        )?.toString() ?: return false
        if (!KoreanParticleCommitContract.canCommit(snapshot, currentEditor, currentTail, text)) {
            return false
        }
        return commitTextToEditor(text, 1)
    }

    /** Captures only a complete, bounded editor prefix with a collapsed cursor at its end. */
    fun captureOnDeviceContextSnapshot(): AiInputCaptureResult {
        clearOnDeviceContextExtractedTextMonitor()
        if (!allowsOnDeviceContextCompletionFeatures()) return AiInputCaptureResult.NoText
        if (currentInputSelection.isNotEmpty()) return AiInputCaptureResult.EditorStateChanged
        val capturedSessionEpoch = inputSessionEpoch
        if (!finishCompositionForDirectAction()) return AiInputCaptureResult.NoText
        val info = currentInputEditorInfo
        val capturedSelectionStart = currentInputSelection.start
        val capturedSelectionEnd = currentInputSelection.end
        if (capturedSelectionStart != capturedSelectionEnd || inputSessionEpoch != capturedSessionEpoch) {
            return AiInputCaptureResult.EditorStateChanged
        }
        val connection = currentInputConnection ?: return AiInputCaptureResult.NoText
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
        if (!matchesCurrentEditor(
                EditorIdentity.of(info),
                EditorSelection(capturedSelectionStart, capturedSelectionEnd),
                expectedInputSessionEpoch = capturedSessionEpoch
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
                    inputSessionEpoch = inputSessionEpoch
                ),
                source = source,
                sourceKind = AiSourceKind.BeforeCursor,
                scope = AiSourceScope.CursorContext
            )
        )
    }

    /** Revalidates the complete captured prefix, its end cursor, and the current editor session. */
    fun isOnDeviceContextSnapshotCurrent(snapshot: AiInputSnapshot): Boolean {
        if (!allowsOnDeviceContextCompletionFeatures() ||
            snapshot.sourceKind != AiSourceKind.BeforeCursor ||
            snapshot.editor.selectionStart != snapshot.editor.selectionEnd ||
            snapshot.source.length !in 1..ON_DEVICE_CONTEXT_MAX_CHARS ||
            activeOnDeviceContextExtractedTextEpoch != snapshot.editor.inputSessionEpoch ||
            !matchesCurrentEditor(snapshot.editor.identity, snapshot.editor.selection, snapshot.editor.inputSessionEpoch)
        ) return false
        val connection = currentInputConnection ?: return false
        val beforeCursor = connection.getTextBeforeCursor(ON_DEVICE_CONTEXT_MAX_CHARS + 1, 0)
            ?.toString() ?: return false
        val afterCursor = connection.getTextAfterCursor(1, 0)?.toString() ?: return false
        return beforeCursor == snapshot.source &&
            beforeCursor.length == snapshot.editor.selectionStart &&
            afterCursor == "" &&
            activeOnDeviceContextExtractedTextEpoch == snapshot.editor.inputSessionEpoch &&
            matchesCurrentEditor(snapshot.editor.identity, snapshot.editor.selection, snapshot.editor.inputSessionEpoch)
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
        if (!finishCompositionForDirectAction() || !isOnDeviceContextSnapshotCurrent(snapshot)) {
            return AiSuggestionApplyResult.EditorChanged
        }
        val connection = currentInputConnection ?: return AiSuggestionApplyResult.NotApplied
        val cursor = currentInputSelection.start
        if (!commitAiTextAtCursor(
                connection = connection,
                cursor = cursor,
                text = suffix,
                restoreStart = cursor,
                restoreEnd = cursor
            )
        ) return AiSuggestionApplyResult.NotApplied
        val end = cursor + suffix.length
        selection.predict(end)
        notifyOnDeviceContextSnapshotInvalidated()
        return AiSuggestionApplyResult.Applied(
            AiAppliedEdit(
                editor = snapshot.editor.copy(selectionStart = end, selectionEnd = end),
                inserted = suffix,
                restore = ""
            )
        )
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
                appCategory = org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry.classify(
                    snapshot.session.scope.packageName
                ),
                fieldHint = automaticSuggestionFieldHint(),
                recentSentences = recentSentSentences.recent(snapshot.session.scope.packageName, 3)
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
        val trimmed = currentInputEditorInfo?.hintText?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val capped = if (trimmed.length > 40) trimmed.take(40) else trimmed
        return capped.takeUnless { org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber.containsPii(it) }
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
        inputView?.postRefreshContextualCandidates(16L)
        return result
    }

    private fun ensureAutomaticSuggestionCoordinator(): Boolean {
        if (!recoverAutomaticSuggestionStateIfTerminal()) return false
        automaticSuggestionCoordinator?.let { return true }
        val runtime = OnDeviceAutomaticSuggestionRuntime(this, automaticSuggestionsUseGpuInternal)
        if (!runtime.supported) return false
        val session = OnDeviceSuggestionSession()
        automaticSuggestionRuntime = runtime
        automaticSuggestionCoordinator = OnDeviceSuggestionCoordinator(
            scope = lifecycleScope,
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
     * Adds up to 3 similar past sentences from [PersonalLearningController.personalSentenceVault] to [input] as style
     * examples. Runs off the main thread (the coordinator dispatches this call on
     * [kotlinx.coroutines.Dispatchers.Default]); this function itself does no dispatching.
     */
    private fun enrichAutomaticSuggestionInputWithPersonalStyle(
        input: OnDeviceSuggestionPolicy.Input
    ): OnDeviceSuggestionPolicy.Input {
        val currentText = input.textBeforeCursor.trim()
        val styleExamples = personalLearning.personalSentenceVault.retrieve(input.textBeforeCursor, input.packageName, limit = 5)
            .map { it.sentence.trim() }
            .filter {
                it.isNotEmpty() && it != currentText && it.length <= 80 &&
                    !org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber.containsPii(it)
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

    private val automaticSuggestionMainHandler = Handler(Looper.getMainLooper())

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
            val info = currentInputEditorInfo ?: return@runOnMainThread
            refreshAutomaticSuggestionIndicator()
            startAutomaticSuggestionWarmupIfAllowed(info, capabilityFlags)
        }
    }

    /** Manually restarts automatic-suggestion warm-up, e.g. after a user taps the blocked indicator. */
    fun retryAutomaticSuggestionWarmup() {
        val info = currentInputEditorInfo ?: return
        startAutomaticSuggestionWarmupIfAllowed(info, capabilityFlags)
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
            val quietSince = maxOf(since, lastEditorActivityAtMs)
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
            isDirectBootInputMode,
            EditorPrivacyPolicy.forbidsTextInspection(info, flags),
            EditorPrivacyPolicy.isConversationalTextField(info, flags),
            effectiveAppProfile?.source?.aiPolicy,
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
        automaticSuggestionWarmupJob = lifecycleScope.launch {
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
                if (org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceBackendFallbackPolicy.shouldFallbackToCpu(
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
        DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootInputMode) &&
            !EditorPrivacyPolicy.forbidsTextInspection(info, flags) &&
            EditorPrivacyPolicy.isConversationalTextField(info, flags) &&
            effectiveAppProfile?.source?.aiPolicy != AppFeaturePolicy.Block

    private fun updateAutomaticSuggestionWarmupState(
        state: OnDeviceAutomaticSuggestionWarmupState
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (automaticSuggestionWarmupStateInternal == state) return
        automaticSuggestionWarmupStateInternal = state
        inputView?.postRefreshContextualCandidates(16L)
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
        inputView?.updateAutomaticSuggestionIndicator(next)
    }

    private fun onAutomaticSuggestionInvalidated() {
        latestAutomaticSuggestionSnapshot = null
        // A stale in-flight generation is discarded by the coordinator's epoch check. Cancelling
        // it natively would tear down the warm engine and cost a full re-preparation.
        if (!isAutomaticSuggestionEligible()) {
            invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
            return
        }
        inputView?.postRefreshContextualCandidates(16L)
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
        inputView?.postRefreshContextualCandidates(16L)
    }

    private fun onAutomaticSuggestionCoordinatorChanged() {
        trackAutomaticSuggestionGenerationLatency()
        scheduleAutomaticSuggestionTtlIfNeeded()
        inputView?.postRefreshContextualCandidates(16L)
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
            inputView?.postRefreshContextualCandidates(16L)
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

    private fun cancelAutomaticSuggestionHideClose() {
        automaticSuggestionHideRunnable?.let { automaticSuggestionMainHandler.removeCallbacks(it) }
        automaticSuggestionHideRunnable = null
    }

    fun captureAutomaticSuggestionSnapshot(): OnDeviceAutomaticEditorSnapshot? {
        if (activeOnDeviceContextExtractedTextToken != null) return null
        clearAutomaticSuggestionExtractedTextMonitor()
        if (!canCaptureAutomaticSuggestionSnapshot()) return null
        val capturedSessionEpoch = inputSessionEpoch
        val connection = currentInputConnection ?: return null
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
        val connection = currentInputConnection ?: return false
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
        if (!finishCompositionForDirectAction()) return AiSuggestionApplyResult.NotApplied
        if (!matchesAutomaticSuggestionAfterComposition(snapshot)) {
            return AiSuggestionApplyResult.EditorChanged
        }
        val connection = currentInputConnection ?: return AiSuggestionApplyResult.NotApplied
        val cursor = currentInputSelection.start
        if (!commitAiTextAtCursor(
                connection = connection,
                cursor = cursor,
                text = suffix,
                restoreStart = cursor,
                restoreEnd = cursor
            )
        ) return AiSuggestionApplyResult.NotApplied
        val end = cursor + suffix.length
        selection.predict(end)
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
        allowsOnDeviceContextCompletionFeatures() &&
            OnDeviceGenerationControl.isKeyboardActive &&
            !isInternalPromptInputOwned &&
            !isInternalPromptCaptureActive &&
            onDeviceContextSnapshotInvalidationListener == null

    private fun automaticSuggestionSnapshotFrom(
        extracted: ExtractedText,
        expectedSessionEpoch: Long,
        expectedMonitorToken: Int? = null,
        recordPreedit: Boolean = false
    ): OnDeviceAutomaticEditorSnapshot? {
        if (!canCaptureAutomaticSuggestionSnapshot() || inputSessionEpoch != expectedSessionEpoch ||
            (expectedMonitorToken != null &&
                (activeAutomaticSuggestionExtractedTextToken != expectedMonitorToken ||
                    activeAutomaticSuggestionExtractedTextEpoch != expectedSessionEpoch))
        ) return null
        val physical = extracted.text?.toString() ?: return null
        val physicalSelection = currentInputSelection
        if (physical.length > ON_DEVICE_CONTEXT_MAX_CHARS || extracted.startOffset != 0 ||
            extracted.partialStartOffset != -1 || extracted.partialEndOffset != -1 ||
            extracted.selectionStart != physicalSelection.start ||
            extracted.selectionEnd != physicalSelection.end ||
            physicalSelection.start != physicalSelection.end ||
            physicalSelection.end != physical.length
        ) return null
        val info = currentInputEditorInfo
        val isBuffered = bufferedHangulSessionActive
        val currentComposingText = composingText.toString()
        val rawBufferedPrefix = bufferedHangulPrefix
        val rawEnginePreedit: String
        val logical: String
        if (isBuffered) {
            if (!composing.isEmpty() || currentComposingText.isNotEmpty() ||
                bufferedHangulEngineResetPending
            ) return null
            val enginePreedit = fcitx.runImmediately { inputPanelCached.preedit }
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
                (composingText.cursor != -1 && composingText.cursor != currentComposingText.length) ||
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
                    packageName = info.packageName,
                    fieldId = info.fieldId,
                    editorSessionId = inputSessionEpoch
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
        val info = currentInputEditorInfo
        if (!EditorIdentity.of(info).sameField(snapshot.identity) ||
            (info.imeOptions and EditorInfo.IME_MASK_ACTION) != snapshot.imeAction ||
            inputSessionEpoch != snapshot.session.scope.editorSessionId
        ) return false
        val connection = currentInputConnection ?: return false
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val physical = extracted.text?.toString() ?: return false
        val currentSelection = currentInputSelection
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

    @Volatile
    private var predictionEpoch = 0L

    private data class ContextualPredictionMemoKey(
        val stroke: String,
        val context: String,
        val packageName: String,
        val epoch: Long,
        val sentencePackRevision: Long,
        val generatedSentenceRevision: Long
    )

    data class ContextualAppendSnapshot(
        val append: ContextualAppend,
        val inputSessionEpoch: Long
    )

    data class ContextualReplacementSnapshot(
        val replacement: ContextualReplacement,
        val inputSessionEpoch: Long,
        val cursor: Int
    )

    data class ContextualCandidate(
        val word: CandidateWord,
        val metricsCandidate: PredictionMetricsSession.Candidate?,
        val appendSnapshot: ContextualAppendSnapshot? = null,
        val replacementSnapshot: ContextualReplacementSnapshot? = null
    )

    data class ContextualCandidateSnapshot(
        val words: List<ContextualCandidate>,
        val sentences: List<ContextualCandidate>
    )

    private data class CachedContextualPredictions(
        val key: ContextualPredictionMemoKey,
        val generation: Long,
        val predictions: List<org.fcitx.fcitx5.android.input.ai.AiPrediction>
    )

    private data class ResolvedContextualPredictions(
        val predictions: List<org.fcitx.fcitx5.android.input.ai.AiPrediction>,
        val generation: Long?
    )

    private fun mergeGeneratedSentencePredictions(
        predictions: List<org.fcitx.fcitx5.android.input.ai.AiPrediction>,
        generatedPredictions: List<org.fcitx.fcitx5.android.input.ai.AiPrediction>
    ): List<org.fcitx.fcitx5.android.input.ai.AiPrediction> {
        val seen = mutableSetOf<String>()
        return (predictions + generatedPredictions)
            .asSequence()
            .sortedByDescending { it.confidenceScore }
            .filter { prediction -> seen.add("${prediction.isSentenceCompletion}:${prediction.text}") }
            .toList()
    }

    @Volatile
    private var contextualResultCache: CachedContextualPredictions? = null

    private var contextualPredictKey: ContextualPredictionMemoKey? = null
    private var contextualPredictJob: Job? = null
    private var sentencePackRevisionJob: Job? = null
    private var observedSentencePackRevision = Long.MIN_VALUE
    private var nextContextualPredictionGeneration = 0L
    private val predictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val predictionMetricsSession = PredictionMetricsSession()

    private fun observeSentencePackRevision() {
        val sentencePacks = FcitxApplication.getInstance().sentencePacks
        sentencePacks.prepare()
        observedSentencePackRevision = sentencePacks.revision
        sentencePackRevisionJob?.cancel()
        sentencePackRevisionJob = lifecycleScope.launch {
            sentencePacks.status
                .map { it.revision }
                .distinctUntilChanged()
                .collect { revision ->
                    if (revision == observedSentencePackRevision) return@collect
                    observedSentencePackRevision = revision
                    contextualPredictJob?.cancel()
                    contextualPredictJob = null
                    contextualPredictKey = null
                    contextualResultCache = null
                    predictionEpoch++
                    inputView?.refreshContextualCandidates()
                }
        }
    }

    private var automaticSuggestionTtlRunnable: Runnable? = null
    private var automaticSuggestionHideRunnable: Runnable? = null

    val contextualPredictor: org.fcitx.fcitx5.android.input.ai.AiContextualPredictor by lazy {
        org.fcitx.fcitx5.android.input.ai.AiContextualPredictor(
            morphology = personalLearning.morphologyEngine,
            semanticPredictor = org.fcitx.fcitx5.android.input.ai.KoreanSemanticSentencePredictor(),
            personalizedStore = personalLearning.personalizedStore,
            ngram = personalLearning.personalNgramModel,
            typoCorrector = personalLearning.typoCorrector,
            baseVocabulary = personalLearning.baseKoreanVocabulary,
            correctionStore = personalLearning.correctionPatternStore,
            personalSentenceVault = personalLearning.personalSentenceVault,
            personalGraphStore = personalLearning.personalGraphStore,
            sentencePackLookup = FcitxApplication.getInstance().sentencePacks::complete,
            bundledNgram = { FcitxApplication.getInstance().bundledKoreanNgram }
        )
    }

    private fun getEmailDomainPredictions(
        beforeCursor: String,
        activePreedit: String,
        limit: Int
    ): List<org.fcitx.fcitx5.android.input.ai.AiPrediction> {
        val emailDomains = listOf(
            "gmail.com",
            "naver.com",
            "kakao.com",
            "daum.net",
            "icloud.com",
            "outlook.com"
        )
        val trimmedBefore = beforeCursor.trim()
        val atIndex = trimmedBefore.lastIndexOf('@')

        val results = mutableListOf<org.fcitx.fcitx5.android.input.ai.AiPrediction>()

        if (atIndex >= 0) {
            val queryDomain = (trimmedBefore.substring(atIndex + 1) + activePreedit).trim().lowercase()
            val matched = if (queryDomain.isEmpty()) {
                emailDomains
            } else {
                emailDomains.filter { it.startsWith(queryDomain) }
            }
            matched.take(limit).forEachIndexed { idx, domain ->
                results.add(
                    org.fcitx.fcitx5.android.input.ai.AiPrediction(
                        text = domain,
                        confidenceScore = 0.99f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "email_domain",
                        badge = "📧"
                    )
                )
            }
        } else {
            emailDomains.take(limit).forEachIndexed { idx, domain ->
                results.add(
                    org.fcitx.fcitx5.android.input.ai.AiPrediction(
                        text = "@$domain",
                        confidenceScore = 0.98f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "email_domain",
                        badge = "📧"
                    )
                )
            }
        }
        return results
    }

    private fun getUrlDomainPredictions(
        beforeCursor: String,
        activePreedit: String,
        limit: Int
    ): List<org.fcitx.fcitx5.android.input.ai.AiPrediction> {
        val tlds = listOf(".com", ".co.kr", ".net", ".kr", ".org")
        val results = mutableListOf<org.fcitx.fcitx5.android.input.ai.AiPrediction>()
        val trimmed = (beforeCursor.trim() + activePreedit.trim()).lowercase()
        val lastDotIndex = trimmed.lastIndexOf('.')
        val queryExt = if (lastDotIndex >= 0 && lastDotIndex >= trimmed.length - 6) {
            trimmed.substring(lastDotIndex)
        } else {
            ""
        }
        val matched = if (queryExt.isNotEmpty() && queryExt != ".") {
            tlds.filter { it.startsWith(queryExt) }
        } else {
            tlds
        }
        if (!tlds.any { trimmed.endsWith(it) }) {
            matched.take(limit).forEachIndexed { idx, tld ->
                results.add(
                    org.fcitx.fcitx5.android.input.ai.AiPrediction(
                        text = tld,
                        confidenceScore = 0.95f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "url_tld",
                        badge = ""
                    )
                )
            }
        }
        return results
    }

    private fun getRawContextualPredictions(limit: Int): ResolvedContextualPredictions {
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val isEmail = EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, capabilityFlags)
        val isPhone = EditorPrivacyPolicy.isPhoneField(currentInputEditorInfo, capabilityFlags)
        val isNumeric = EditorPrivacyPolicy.isNumericField(currentInputEditorInfo, capabilityFlags)
        val isUrl = EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, capabilityFlags)
        val isConversational = EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, capabilityFlags)

        // 1. Phone or pure numeric inputs -> strictly no conversational predictions
        if (isPhone || isNumeric) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val ic = currentInputConnection ?: run {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }
        val beforeCursor = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()

        val activePreedit = activePreeditForContextualInput()

        // 2. Email field -> smart email domain suggestions, zero sentence completions
        if (isEmail) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(getEmailDomainPredictions(beforeCursor, activePreedit, limit), null)
        }

        // 3. URL field -> web domain suggestions, zero sentence completions
        if (isUrl) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(getUrlDomainPredictions(beforeCursor, activePreedit, limit), null)
        }

        // 4. Non-conversational text field (e.g. search filter with NO_SUGGESTIONS) -> emptyList()
        if (!isConversational) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val pkgName = currentInputEditorInfo.packageName
        val resolved = org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput.resolve(beforeCursor, activePreedit)
        // 진짜 유휴(스트로크도 없고 커서 앞 문맥도 비어 있음)일 때만 예측을 건너뛴다. 그래야
        // 후보 영역이 접혀 도구 줄만 남는다. 단어를 치고 스페이스를 눌러 스트로크가 비었어도
        // 커서 앞에 문맥이 있으면(예: "회의 참석 ") 다음 단어·입력 이어쓰기(회의 참석하겠습니다)를
        // 계속 제시한다.
        if (resolved.stroke.isBlank() && resolved.context.isBlank()) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val application = FcitxApplication.getInstance()
        val memoKey = ContextualPredictionMemoKey(
            resolved.stroke,
            resolved.context,
            pkgName,
            predictionEpoch,
            application.sentencePacks.revision,
            if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank.revision else 0L
        )
        val inputSessionEpoch = currentInputSessionEpoch
        contextualResultCache?.let { cached ->
            if (cached.key == memoKey) {
                return ResolvedContextualPredictions(cached.predictions, cached.generation)
            }
        }
        predictionMetricsSession.reset()

        val rawFullContext = org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput.rawFullContext(
            resolved.stroke,
            resolved.context
        )
        val immediateResults = org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions.collect(
            input = org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions.Input(
                rawContext = rawFullContext,
                packageName = pkgName,
                inputSessionEpoch = inputSessionEpoch,
                limit = limit
            ),
            sentencePackLookup = application.sentencePacks::complete,
            generatedSentenceLookup = if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank::complete else null,
            generatedSpacingLookup = if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank::suggestSpacing else null
        )
        val immediateGeneration = ++nextContextualPredictionGeneration
        contextualResultCache = CachedContextualPredictions(
            key = memoKey,
            generation = immediateGeneration,
            predictions = immediateResults
        )
        if (contextualPredictKey != memoKey) {
            contextualPredictJob?.cancel()
            contextualPredictKey = memoKey
            contextualPredictJob = predictionScope.launch {
                val results = contextualPredictor.predict(
                    currentStroke = resolved.stroke,
                    contextBeforeCursor = resolved.context,
                    packageName = pkgName,
                    limit = limit,
                    inputSessionEpoch = inputSessionEpoch
                )
                withContext(Dispatchers.Main) {
                    val currentFieldIsConversational =
                        !EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, capabilityFlags) &&
                            !EditorPrivacyPolicy.isPhoneField(currentInputEditorInfo, capabilityFlags) &&
                            !EditorPrivacyPolicy.isNumericField(currentInputEditorInfo, capabilityFlags) &&
                            !EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, capabilityFlags) &&
                            EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, capabilityFlags)
                    if (
                        contextualPredictKey == memoKey &&
                        currentInputConnection != null &&
                        currentInputSessionEpoch == inputSessionEpoch &&
                        allowsTextInspectionFeatures() &&
                        currentInputSelection.isEmpty() &&
                        currentFieldIsConversational
                    ) {
                        val generatedImmediateResults = immediateResults.filter {
                            it.source == "ondevice_generated"
                        }
                        val publishedResults = when {
                            results.isEmpty() && immediateResults.isNotEmpty() -> immediateResults
                            generatedImmediateResults.isEmpty() -> results
                            else -> mergeGeneratedSentencePredictions(results, generatedImmediateResults)
                        }
                        contextualResultCache = CachedContextualPredictions(
                            key = memoKey,
                            generation = ++nextContextualPredictionGeneration,
                            predictions = publishedResults
                        )
                        inputView?.refreshContextualCandidates()
                    }
                }
            }
        }
        return ResolvedContextualPredictions(immediateResults, immediateGeneration)
    }

    private fun activePreeditForContextualInput(): String {
        val clientPreedit = composingText.toString()
        if (clientPreedit.trim().isNotEmpty()) {
            return org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput.activePreedit(
                bufferedHangulPrefix = bufferedHangulPrefix,
                clientPreedit = clientPreedit,
                enginePreedit = ""
            )
        }
        val enginePreedit = if (bufferedHangulEngineResetPending) {
            ""
        } else {
            fcitx.runImmediately { inputPanelCached.preedit.toString() }
        }
        return org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput.activePreedit(
            bufferedHangulPrefix = bufferedHangulPrefix,
            clientPreedit = clientPreedit,
            enginePreedit = enginePreedit
        )
    }

    fun getContextualSentencePredictions(limit: Int = 2): List<CandidateWord> {
        val predictions = getRawContextualPredictions(limit = 10).predictions
        return predictions.filter { it.isSentenceCompletion }.take(limit).mapIndexed { index, pred ->
            CandidateWord(
                label = (index + 1).toString(),
                text = pred.text,
                comment = pred.badge
            )
        }
    }

    fun getContextualWordPredictions(limit: Int = 4): List<CandidateWord> {
        val predictions = getRawContextualPredictions(limit = 10).predictions
        return predictions.filter { !it.isSentenceCompletion }.take(limit).mapIndexed { index, pred ->
            CandidateWord(
                label = (index + 1).toString(),
                text = pred.text,
                comment = pred.badge
            )
        }
    }

    /**
     * Provides one immutable cache generation to the candidate UI so metrics preserve the source
     * that produced each rendered candidate instead of resolving a source from current text later.
     */
    fun getContextualCandidateSnapshot(wordLimit: Int = 4, sentenceLimit: Int = 2): ContextualCandidateSnapshot {
        val resolved = getRawContextualPredictions(limit = 10)
        val generation = resolved.generation
        if (generation != null) {
            predictionMetricsSession.activate(generation)
        }
        fun toCandidate(prediction: org.fcitx.fcitx5.android.input.ai.AiPrediction, index: Int): ContextualCandidate =
            ContextualCandidate(
                word = CandidateWord(
                    label = index.toString(),
                    text = prediction.text,
                    comment = prediction.badge
                ),
                metricsCandidate = generation?.let {
                    PredictionMetricsSession.Candidate(it, prediction.text, prediction.source)
                },
                appendSnapshot = prediction.append?.let { append ->
                    ContextualAppendSnapshot(append, inputSessionEpoch)
                },
                replacementSnapshot = prediction.replacement?.let { replacement ->
                    ContextualReplacementSnapshot(
                        replacement = replacement,
                        inputSessionEpoch = inputSessionEpoch,
                        cursor = currentInputSelection.start
                    )
                }
            )

        val words = resolved.predictions.filter { !it.isSentenceCompletion }
            .take(wordLimit)
            .mapIndexed { index, prediction -> toCandidate(prediction, index + 1) }
        val sentences = resolved.predictions.filter { it.isSentenceCompletion }
            .take(sentenceLimit)
            .mapIndexed { index, prediction -> toCandidate(prediction, index + 1) }
        return ContextualCandidateSnapshot(words = words, sentences = sentences)
    }

    fun recordContextualCandidateShown(candidate: PredictionMetricsSession.Candidate?) {
        if (candidate == null) return
        if (!allowsTextInspectionFeatures()) {
            predictionMetricsSession.reset()
            return
        }
        if (predictionMetricsSession.recordShown(candidate)) {
            FcitxApplication.getInstance().applicationScope.launch {
                FcitxApplication.getInstance().predictionMetricsStore.recordShown(1)
                withContext(Dispatchers.Main) {
                    personalLearning.scheduleNgramSave()
                }
            }
        }
    }

    private fun recordContextualCandidateAccepted(candidate: PredictionMetricsSession.Candidate?, committed: Boolean) {
        if (candidate == null) return
        if (!allowsTextInspectionFeatures()) {
            predictionMetricsSession.reset()
            return
        }
        if (predictionMetricsSession.recordAccepted(candidate, committed)) {
            FcitxApplication.getInstance().applicationScope.launch {
                if (OnDeviceAiSupport.isSupported && candidate.source == "ondevice_generated") {
                    try {
                        FcitxApplication.getInstance().generatedSentenceBank.recordAcceptedSuffix(candidate.text)
                    } catch (error: Exception) {
                        Timber.w("Generated material acceptance save failed: ${error.javaClass.simpleName}")
                    }
                }
                FcitxApplication.getInstance().predictionMetricsStore.recordAccepted(candidate.source, savedKeystrokes = 0)
                withContext(Dispatchers.Main) {
                    personalLearning.scheduleNgramSave()
                }
            }
        }
    }

    fun recordContextualCandidatesIgnored(offeredSentences: List<String>) {
        if (!allowsTextInspectionFeatures() || offeredSentences.isEmpty()) return
        val capturedSentences = offeredSentences.toList()
        personalLearning.enqueuePersonalLearning {
            personalLearning.reinforcementTracker.onCandidatesIgnored(capturedSentences)
        }
    }

    fun recordContextualCandidateRejected(sentence: String, heavyPenalty: Boolean = true) {
        if (!allowsTextInspectionFeatures()) return
        val capturedSentence = sentence
        personalLearning.enqueuePersonalLearning {
            personalLearning.reinforcementTracker.onCandidateRejected(capturedSentence, heavyPenalty)
        }
    }

    /** 마지막으로 만든 예측 결과 메모에서 텍스트가 같은 후보의 replaceLength를 찾는다. */
    private fun replaceLengthForCandidate(sentence: String): Int =
        contextualResultCache?.predictions?.firstOrNull { it.text == sentence }?.replaceLength ?: 0

    private fun commitContextualCandidateText(
        connection: InputConnection,
        replaceLength: Int,
        textToCommit: String
    ): Boolean {
        if (replaceLength <= 0) {
            return commitTextToEditor(textToCommit, textToCommit.length)
        }
        val start = currentInputSelection.start
        val end = currentInputSelection.end
        if (start != end || start < replaceLength) return false
        val removedText = connection.getTextBeforeCursor(replaceLength, 0)?.toString() ?: return false
        if (removedText.length != replaceLength || !currentInputSelection.rangeEquals(start, end)) {
            return false
        }
        personalLearning.captureCorrectionBoundarySnapshot()
        val replacementStart = start - replaceLength
        if (!replaceAiRange(
                connection = connection,
                start = replacementStart,
                end = start,
                replacement = textToCommit,
                restoreStart = start,
                restoreEnd = end
            )
        ) {
            return false
        }
        typingDnaCommitSink.onEditorSuffixDeleted(
            currentInputEditorInfo?.packageName,
            removedText,
            allowsTextInspectionFeatures()
        )
        observeCommittedEditorText(textToCommit)
        selection.predict(replacementStart + textToCommit.length)
        inputView?.postRefreshContextualCandidates(16L)
        return true
    }

    private fun commitConfirmedContextualAppend(
        sentence: String,
        appendSnapshot: ContextualAppendSnapshot,
        metricsCandidate: PredictionMetricsSession.Candidate?
    ): Boolean {
        if (!allowsTextInspectionFeatures()) return false
        if (sentence != appendSnapshot.append.suffix || appendSnapshot.inputSessionEpoch != inputSessionEpoch) {
            return false
        }
        if (!finishCompositionForDirectAction()) return false
        if (appendSnapshot.inputSessionEpoch != inputSessionEpoch) return false
        val cursor = currentInputSelection.start
        if (cursor != currentInputSelection.end) return false
        val connection = currentInputConnection ?: return false
        val beforeCursor = connection.getTextBeforeCursor(1024, 0)?.toString() ?: return false
        if (appendSnapshot.inputSessionEpoch != inputSessionEpoch ||
            !currentInputSelection.rangeEquals(cursor, cursor)
        ) {
            return false
        }
        val textToCommit = appendSnapshot.append.insertionFor(beforeCursor) ?: return false
        personalLearning.captureCorrectionBoundarySnapshot()
        if (!commitAiTextAtCursor(connection, cursor, textToCommit, cursor, cursor)) return false

        observeCommittedEditorText(textToCommit)
        selection.predict(cursor + textToCommit.length)
        inputView?.postRefreshContextualCandidates(16L)
        personalLearning.enqueueContextualSelectionFeedback(
            contextBeforeReinforce = beforeCursor.takeLast(64),
            selectedSentence = sentence,
            reinforcedSentence = appendSnapshot.append.suffix,
            packageName = currentInputEditorInfo.packageName
        )
        predictionEpoch++
        recordContextualCandidateAccepted(metricsCandidate?.takeIf { it.text == sentence }, committed = true)
        return true
    }

    private fun commitConfirmedContextualReplacement(
        sentence: String,
        replacementSnapshot: ContextualReplacementSnapshot,
        metricsCandidate: PredictionMetricsSession.Candidate?
    ): Boolean {
        val replacement = replacementSnapshot.replacement
        if (!allowsTextInspectionFeatures() ||
            !EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, capabilityFlags) ||
            sentence != replacement.replacement ||
            replacementSnapshot.inputSessionEpoch != inputSessionEpoch
        ) {
            return false
        }
        val capturedCursor = currentInputSelection.start
        if (capturedCursor != currentInputSelection.end ||
            capturedCursor != replacementSnapshot.cursor ||
            capturedCursor != replacement.expectedContext.length
        ) {
            return false
        }
        if (!finishCompositionForDirectAction() || replacementSnapshot.inputSessionEpoch != inputSessionEpoch) {
            return false
        }
        val cursor = currentInputSelection.start
        if (cursor != currentInputSelection.end ||
            cursor != replacementSnapshot.cursor ||
            cursor != replacement.expectedContext.length
        ) {
            return false
        }
        val connection = currentInputConnection ?: return false
        val beforeCursor = connection.getTextBeforeCursor(replacement.expectedContext.length + 1, 0)
            ?.toString() ?: return false
        if (beforeCursor != replacement.expectedContext ||
            replacementSnapshot.inputSessionEpoch != inputSessionEpoch ||
            !currentInputSelection.rangeEquals(cursor, cursor)
        ) {
            return false
        }
        if (!replaceAiRange(
                connection = connection,
                start = 0,
                end = replacement.expectedContext.length,
                replacement = replacement.replacement,
                restoreStart = cursor,
                restoreEnd = cursor
            )
        ) {
            return false
        }
        selection.predict(replacement.replacement.length)
        inputView?.postRefreshContextualCandidates(16L)
        predictionEpoch++
        recordContextualCandidateAccepted(metricsCandidate?.takeIf { it.text == sentence }, committed = true)
        return true
    }

    fun commitContextualSentence(
        sentence: String,
        metricsCandidate: PredictionMetricsSession.Candidate? = null,
        appendSnapshot: ContextualAppendSnapshot? = null,
        replacementSnapshot: ContextualReplacementSnapshot? = null
    ): Boolean {
        if (!allowsTextInspectionFeatures()) return false
        if (replacementSnapshot != null) {
            return commitConfirmedContextualReplacement(sentence, replacementSnapshot, metricsCandidate)
        }
        if (appendSnapshot != null) {
            return commitConfirmedContextualAppend(sentence, appendSnapshot, metricsCandidate)
        }
        if (!finishCompositionForDirectAction()) return false
        val ic = currentInputConnection ?: return false
        val beforeCursor = ic.getTextBeforeCursor(512, 0)?.toString().orEmpty()
        val capturedMetricsCandidate = metricsCandidate?.takeIf { it.text == sentence }

        val replaceLength = replaceLengthForCandidate(sentence)
        if (replaceLength > 0) {
            val contextBeforeReinforce = beforeCursor.dropLast(replaceLength).takeLast(64)
            val shouldAppendSpace = !sentence.endsWith(" ") && !sentence.endsWith("\n")
            val textToCommit = if (shouldAppendSpace) "$sentence " else sentence
            val committed = commitContextualCandidateText(ic, replaceLength, textToCommit)
            if (committed) {
                personalLearning.enqueueContextualSelectionFeedback(
                    contextBeforeReinforce = contextBeforeReinforce,
                    selectedSentence = sentence,
                    reinforcedSentence = sentence,
                    packageName = currentInputEditorInfo.packageName
                )
                predictionEpoch++
                recordContextualCandidateAccepted(capturedMetricsCandidate, committed = true)
            }
            return committed
        }

        if (!sentence.contains(" ")) {
            val lastSentence = beforeCursor
                .substringAfterLast('.')
                .substringAfterLast('?')
                .substringAfterLast('!')
                .substringAfterLast('\n')
                .trim()
            if (lastSentence.isNotEmpty()) {
                val typoPairs = contextualPredictor.typoEngine.findTypoCorrectionsInSentence(lastSentence)
                val matchingPair = typoPairs.firstOrNull { it.second == sentence }
                if (matchingPair != null && !lastSentence.endsWith(matchingPair.first)) {
                    val correctedSentence = contextualPredictor.typoEngine.correctSentence(lastSentence)
                    if (correctedSentence != null) {
                        val trailingSpaces = beforeCursor.length - beforeCursor.trimEnd().length
                        val replacementLength = lastSentence.length + trailingSpaces
                        val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
                        val textToCommit = if (correctedSentence.endsWith(" ") || correctedSentence.endsWith("\n")) correctedSentence else "$correctedSentence "
                        val committed = commitContextualCandidateText(ic, replacementLength, textToCommit)
                        if (committed) {
                            personalLearning.enqueueContextualSelectionFeedback(
                                contextBeforeReinforce = contextBeforeReinforce,
                                selectedSentence = sentence,
                                reinforcedSentence = correctedSentence,
                                packageName = currentInputEditorInfo.packageName
                            )
                            predictionEpoch++
                            recordContextualCandidateAccepted(capturedMetricsCandidate, committed = true)
                        }
                        return committed
                    }
                }
            }
        }

        val isEmailField = EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, capabilityFlags)
        val isEmailDomain = sentence.startsWith("@") || sentence.endsWith(".com") || sentence.endsWith(".net") || sentence.endsWith(".co.kr") || sentence.endsWith(".io") || sentence.endsWith(".org")
        val isUrlField = EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, capabilityFlags)
        val isUrlTld = sentence.startsWith(".") && (sentence.endsWith(".com") || sentence.endsWith(".net") || sentence.endsWith(".org") || sentence.endsWith(".kr") || sentence.endsWith(".co.kr") || sentence.endsWith(".io"))
        val urlReplaceLength = if (isUrlField && isUrlTld && beforeCursor.endsWith(".")) 1 else 0

        if (isEmailField && beforeCursor.contains("@")) {
            val afterAt = beforeCursor.substringAfterLast('@')
            val replacementLength = if (sentence.startsWith("@")) {
                afterAt.length + 1
            } else if (afterAt.isNotEmpty() && sentence.startsWith(afterAt)) {
                afterAt.length
            } else {
                0
            }
            val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
            val committed = commitContextualCandidateText(ic, replacementLength, sentence)
            if (committed) {
                personalLearning.enqueueContextualSelectionFeedback(
                    contextBeforeReinforce = contextBeforeReinforce,
                    selectedSentence = sentence,
                    reinforcedSentence = sentence,
                    packageName = currentInputEditorInfo.packageName
                )
                predictionEpoch++
                recordContextualCandidateAccepted(capturedMetricsCandidate, committed = true)
            }
            return committed
        }

        val overlapLengthInBeforeCursor = if (isEmailField || isUrlField) {
            0
        } else {
            contextualPredictor.typoEngine.calculateReplacementOverlap(beforeCursor, sentence)
        }
        val replacementLength = if (overlapLengthInBeforeCursor > 0) {
            overlapLengthInBeforeCursor
        } else {
            urlReplaceLength
        }
        val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
        val shouldAppendSpace = !isEmailField && !isUrlField && !isEmailDomain && !isUrlTld && !sentence.endsWith(" ") && !sentence.endsWith("\n")
        val textToCommit = if (shouldAppendSpace) "$sentence " else sentence
        val committed = commitContextualCandidateText(ic, replacementLength, textToCommit)
        if (committed) {
            personalLearning.enqueueContextualSelectionFeedback(
                contextBeforeReinforce = contextBeforeReinforce,
                selectedSentence = sentence,
                reinforcedSentence = sentence,
                packageName = currentInputEditorInfo.packageName
            )
            predictionEpoch++
            recordContextualCandidateAccepted(capturedMetricsCandidate, committed = true)
        }
        return committed
    }

    fun captureTypoRecoverySnapshot(): TypoRecoverySnapshot? {
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) return null
        if (!finishCompositionForDirectAction()) return null
        val info = currentInputEditorInfo
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return null
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
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) return false
        val info = currentInputEditorInfo
        if (info.packageName != editor.packageName || info.fieldId != editor.fieldId ||
            info.inputType != editor.inputType
        ) return false
        if (!finishCompositionForDirectAction()) return false
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        val previousCursor = currentInputSelection.start
        var dispatched = true
        ic.withBatchEdit {
            dispatched = deleteSurroundingText(expected.length, 0) && dispatched
            if (dispatched) dispatched = commitText(replacement, 1) && dispatched
        }
        if (dispatched) selection.predict(previousCursor - expected.length + replacement.length)
        return dispatched
    }

    private fun finishCompositionForDirectAction(): Boolean {
        // Reviewed editor actions must wait until an internal prompt has either completed its
        // drain or been cancelled. Otherwise a visible success state could hide a dropped write.
        if (isInternalPromptInputOwned) return false
        val ic = currentInputConnection ?: return false
        if (bufferedHangulSessionActive) {
            if (!submitBufferedHangul()) return false
        } else if (composing.isNotEmpty()) {
            composing.clear()
            composingText = FormattedText.Empty
            if (!ic.finishComposingText()) return false
        }
        postFcitxJob { reset() }
        return true
    }

    private fun commitTextToEditor(
        text: String,
        cursor: Int = -1,
        allowPromptStart: Boolean = false
    ): Boolean {
        if (isInternalPromptInputOwned && !allowPromptStart) return false
        val ic = currentInputConnection ?: return false
        personalLearning.captureCorrectionBoundarySnapshot()
        // when composing text equals commit content, finish composing text as-is
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            var dispatched = true
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    dispatched = ic.setSelection(target, target) && dispatched
                }
                dispatched = ic.finishComposingText() && dispatched
            }
            if (dispatched) {
                observeCommittedEditorText(text)
                inputView?.postRefreshContextualCandidates(16L)
            }
            return dispatched
        }
        // committed text should replace composing (if any), replace selected range (if any),
        // or simply prepend before cursor
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        val dispatchedResult = if (cursor == -1) {
            selection.predict(start + text.length)
            ic.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            var dispatched = true
            ic.withBatchEdit {
                dispatched = commitText(text, 1) && dispatched
                dispatched = setSelection(target, target) && dispatched
            }
            dispatched
        }
        if (dispatchedResult) {
            notifyOnDeviceContextSnapshotInvalidated()
            observeCommittedEditorText(text)
            inputView?.postRefreshContextualCandidates(16L)
        }
        return dispatchedResult
    }

    private fun handleBufferedHangulForwardedKey(data: FcitxEvent.KeyEvent.Data): Boolean {
        // A callback before the start marker must take the normal original-editor route below.
        // The buffered path would otherwise see the temporary UI freeze and discard it.
        if (isInternalPromptCaptureStarting) return false
        if (isInternalPromptCaptureActive) return false
        if (!data.states.virtual && data.up && consumedPhysicalKeysDown.remove(data.sym.sym)) {
            cachedKeyEvents.remove(data.timestamp)
            return true
        }
        if (!bufferedHangulSessionActive) return false
        val hasShortcutModifier = data.states.ctrl || data.states.alt || data.states.meta ||
            data.states.has(KeyState.Super) || data.states.has(KeyState.Super2) ||
            data.states.has(KeyState.Hyper)
        if (hasShortcutModifier) {
            // Flush without touching the clipboard before forwarding shortcuts such as Ctrl+V.
            // Otherwise their Unicode value could become literal text or a paste shortcut could
            // overtake the pending Hangul segment.
            if (!data.up) submitBufferedHangul(BufferedInputTransport.DirectCommit)
            if (!data.states.virtual) return false
            val keyCode = data.sym.keyCode
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                Timber.w("Unable to forward buffered shortcut KeyEvent: $data")
                return true
            }
            val eventTime = SystemClock.uptimeMillis()
            if (data.up) sendUpKeyEvent(eventTime, keyCode, data.states.metaState)
            else sendDownKeyEvent(eventTime, keyCode, data.states.metaState)
            return true
        }
        if (!data.up) {
            val boundary = when (data.sym.sym) {
                FcitxKeyMapping.FcitxKey_space -> SnippetBoundary.Space
                FcitxKeyMapping.FcitxKey_Return -> SnippetBoundary.Enter
                else -> null
            }
            if (boundary != null && tryExpandBufferedSnippet(boundary)) {
                if (!data.states.virtual) {
                    cachedKeyEvents.remove(data.timestamp)
                    consumedPhysicalKeysDown.add(data.sym.sym)
                }
                return true
            }
        }
        val bufferedKey = when (data.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace,
            FcitxKeyMapping.FcitxKey_Return,
            FcitxKeyMapping.FcitxKey_Left,
            FcitxKeyMapping.FcitxKey_Right -> true
            else -> data.unicode > 0
        }
        if (!bufferedKey) return false
        if (data.up) return data.states.virtual
        val handled = when (data.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> {
                val preeditEmpty = fcitx.runImmediately { inputPanelCached.preedit.isEmpty() }
                if (preeditEmpty) {
                    if (allowsTextInspectionFeatures()) {
                        correctionSessionTracker.onBackspace(currentWordBeforeCursor())
                    }
                    if (bufferedHangul.deleteLastCodePoint()) {
                        inputView?.refreshBufferedHangulPreedit()
                    } else {
                        handleBackspaceKey()
                    }
                    true
                } else {
                    false
                }
            }
            FcitxKeyMapping.FcitxKey_Return -> {
                // Two-stage Return. The first press only finalizes the pending segment, so a
                // segment can be ended without a delimiter such as Space inserting an unwanted
                // character. The next press runs the editor's own Return action. A failed
                // dispatch keeps the buffer, and the next press retries it instead of sending
                // Return after text the editor never received.
                if (hasPendingBufferedHangul()) submitBufferedHangul() else handleReturnKey()
                true
            }
            FcitxKeyMapping.FcitxKey_Left -> {
                if (submitBufferedHangul()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                true
            }
            FcitxKeyMapping.FcitxKey_Right -> {
                if (submitBufferedHangul()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                true
            }
            else -> if (data.unicode > 0) {
                bufferedHangul.capture(Character.toString(data.unicode))
                submitBufferedHangul()
                true
            } else {
                false
            }
        }
        if (handled && !data.states.virtual) {
            cachedKeyEvents.remove(data.timestamp)
            consumedPhysicalKeysDown.add(data.sym.sym)
        }
        return handled
    }

    /**
     * Returns a copy for Fcitx's own preedit UI. The target InputConnection never sees it.
     */
    fun decorateBufferedHangulPreedit(data: FcitxEvent.InputPanelEvent.Data):
        FcitxEvent.InputPanelEvent.Data {
        val prefix = bufferedHangulPrefix
        if (prefix.isEmpty() && !bufferedHangulEngineResetPending) return data
        val source = if (bufferedHangulEngineResetPending) FormattedText.Empty else data.preedit
        if (prefix.isEmpty()) return data.copy(preedit = source)
        val cursor = source.cursor.let { if (it < 0) prefix.length else prefix.length + it }
        val combined = FormattedText(
            arrayOf(prefix, *source.strings),
            intArrayOf(TextFormatFlag.NoFlag.flag, *source.flags),
            cursor
        )
        return data.copy(preedit = combined)
    }

    private fun clearBufferedHangul() {
        bufferedHangul.clear()
        inputView?.refreshBufferedHangulPreedit()
    }

    /** Whether an unsent segment exists in the captured prefix or in the engine's live preedit. */
    private fun hasPendingBufferedHangul(): Boolean {
        if (!bufferedHangul.isEmpty) return true
        return !bufferedHangulEngineResetPending &&
            fcitx.runImmediately { inputPanelCached.preedit.isNotEmpty() }
    }

    /**
     * Submit one complete buffered segment. Transport choice is explicit: paste acknowledgements
     * do not report whether the target editor actually handled the action, so automatic fallback
     * would risk duplicate input.
     */
    private fun queueBufferedHangulEngineReset() {
        if (bufferedHangulEngineResetPending) return
        bufferedHangulEngineResetPending = true
        postFcitxJob { reset() }.invokeOnCompletion {
            bufferedHangulEngineResetPending = false
        }
    }

    private fun submitBufferedHangul(
        forcedTransport: BufferedInputTransport? = null,
        allowPromptStart: Boolean = false
    ): Boolean {
        if (isInternalPromptInputOwned && !allowPromptStart) {
            discardBufferedHangulForInternalPrompt()
            return false
        }
        val currentPreedit = if (bufferedHangulEngineResetPending) {
            ""
        } else {
            fcitx.runImmediately { inputPanelCached.preedit.toString() }
        }
        val text = bufferedHangul.snapshot(currentPreedit)
        if (text.isEmpty()) return true
        val dispatched = dispatchBufferedText(text, forcedTransport, allowPromptStart)
        if (dispatched) {
            bufferedHangul.clear()
        } else if (currentPreedit.isNotEmpty()) {
            // Preserve the tail before resetting the engine so a retry cannot duplicate it.
            bufferedHangul.capture(currentPreedit)
        }
        if (currentPreedit.isNotEmpty()) queueBufferedHangulEngineReset()
        inputView?.refreshBufferedHangulPreedit()
        return dispatched
    }

    private fun dispatchBufferedText(
        text: String,
        forcedTransport: BufferedInputTransport? = null,
        allowPromptStart: Boolean = false
    ): Boolean {
        if (isInternalPromptInputOwned && !allowPromptStart) {
            discardBufferedHangulForInternalPrompt()
            return false
        }
        if (currentInputConnection == null) return false
        val transport = if (BufferedHangulMode.mustAvoidClipboard(capabilityFlags)) {
            BufferedInputTransport.DirectCommit
        } else {
            forcedTransport ?: effectiveBufferedInputTransport()
        }
        return when (transport) {
            BufferedInputTransport.DirectCommit -> {
                commitTextToEditor(text, allowPromptStart = allowPromptStart)
            }
            BufferedInputTransport.SystemPaste -> {
                if (!setBufferedClipboard(text)) return false
                try {
                    // The Boolean only acknowledges dispatch across RemoteInputConnection; it is
                    // not the target editor's paste result and must not drive an auto fallback.
                    val dispatched =
                        currentInputConnection?.performContextMenuAction(android.R.id.paste) == true
                    if (dispatched) {
                        predictBufferedInsertion(text)
                        observeCommittedEditorText(text)
                    }
                    dispatched
                } catch (exception: RuntimeException) {
                    Timber.w(exception, "Unable to dispatch buffered system paste")
                    false
                }
            }
            BufferedInputTransport.CtrlV -> {
                if (!setBufferedClipboard(text)) return false
                val dispatched = sendCombinationKeyEventsInternal(
                    keyEventCode = KeyEvent.KEYCODE_V,
                    ctrl = true,
                    allowPromptStart = allowPromptStart
                )
                if (dispatched) {
                    predictBufferedInsertion(text)
                    observeCommittedEditorText(text)
                }
                dispatched
            }
        }
    }

    private fun predictBufferedInsertion(text: String) {
        selection.predict(selection.latest.start + text.length)
    }

    /** Drops a stale buffered segment instead of dispatching it into an internal prompt's editor. */
    private fun discardBufferedHangulForInternalPrompt() {
        bufferedHangul.clear()
        inputView?.refreshBufferedHangulPreedit()
    }

    private fun setBufferedClipboard(text: String): Boolean {
        val clip = ClipData.newPlainText(TRANSIENT_BUFFERED_PASTE_LABEL, text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                description.extras = PersistableBundle().apply {
                    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ClipDescription.EXTRA_IS_SENSITIVE
                    } else {
                        "android.content.extra.IS_SENSITIVE"
                    }
                    putBoolean(key, true)
                }
            }
        }
        return try {
            // Leave the submitted text in the clipboard. Remote InputConnection dispatch is
            // asynchronous; restoring the previous clip here can paste the wrong content.
            clipboardManager.setPrimaryClip(clip)
            true
        } catch (exception: RuntimeException) {
            Timber.w(exception, "Unable to prepare buffered clipboard transport")
            false
        }
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0
    ): Boolean = currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        ) == true

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0
    ): Boolean = currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        ) == true

    fun deleteSelection() {
        if (isInternalPromptInputOwned) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ): Boolean = sendCombinationKeyEventsInternal(keyEventCode, alt, ctrl, shift)

    private fun sendCombinationKeyEventsInternal(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        allowPromptStart: Boolean = false
    ): Boolean {
        if (isInternalPromptInputOwned && !allowPromptStart) return false
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        val mainKeyDispatched = sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        // The modified key-down carries the full meta state and triggers the shortcut. Release
        // failures are ambiguous and must not cause an automatic duplicate submission.
        return mainKeyDispatched
    }

    /** Sends an editor-directed key pair only while no internal prompt owns the input target. */
    fun sendEditorKeyEvents(keyEventCode: Int): Boolean {
        if (isInternalPromptInputOwned) return false
        sendDownUpKeyEvents(keyEventCode)
        return true
    }

    /** Performs an editor context-menu action without bypassing the prompt isolation gate. */
    fun performEditorContextMenuAction(action: Int): Boolean {
        if (isInternalPromptInputOwned) return false
        return currentInputConnection?.performContextMenuAction(action) == true
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        if (isInternalPromptInputOwned) return
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        if (isInternalPromptInputOwned) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
    }

    private lateinit var lastKnownConfig: Configuration

    override fun onConfigurationChanged(newConfig: Configuration) {
        postFcitxJob { reset() }
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceMgr]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        try {
            highlightColor = styledColor(android.R.attr.colorAccent).alpha(0.4f)
        } catch (_: Exception) {
            Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
        }
        InputFeedbacks.syncSystemPrefs()
    }

    override fun onCreateInputView(): View? {
        replaceInputViews(effectiveInputTheme())
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard) {
            inputView?.getTouchableTopLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // Search/Run already placed its FIFO fence. Physical keys arriving after that point must
        // not slip behind the fence and mutate a prompt that is about to be finalized.
        if (isInternalPromptCaptureStarting || isInternalPromptCaptureDraining ||
            isInternalPromptSubmissionPending
        ) return true
        val sym = KeySym.fromKeyEvent(event)
        if (sym == null) {
            Timber.d("Skipped KeyEvent: $event")
            // An unsupported hardware/dead/shortcut key must not fall through to the app while
            // the internal prompt owns the keyboard target.
            return isInternalPromptInputOwned
        }
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val states = KeyStates.fromKeyEvent(event)
        val up = event.action == KeyEvent.ACTION_UP
        postFcitxJob {
            sendKey(sym, states, event.scanCode, up, timestamp)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isInternalPromptInputOwned) return forwardKeyEvent(event)
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(event, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isInternalPromptInputOwned) return forwardKeyEvent(event)
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceMgr.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        engineRestartEditorRehydrationGate.onBindInput(uid, pkgName)
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // Android can reuse identical EditorInfo metadata for a different text field. An internal
        // prompt therefore never survives a new input-session boundary, even for a same-app
        // restart: dropping a draft is safer than sending a later GIF/AI action to the wrong field.
        notifyOnDeviceContextSnapshotInvalidated()
        inputSessionEpoch += 1
        personalLearning.onStartInput()
        predictionEpoch++
        predictionMetricsSession.reset()
        correctionSessionTracker.onEditorChanged()
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        SensitivePhraseSession.onEditorChanged(
            DynamicPhraseEditorTarget(
                packageName = attribute.packageName,
                fieldId = attribute.fieldId,
                inputType = attribute.inputType,
                selectionStart = attribute.initialSelStart,
                selectionEnd = attribute.initialSelEnd
            )
        )
        refreshSnippetCatalog()
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        val restartTransport = if (
            BufferedHangulMode.mustAvoidClipboard(capabilityFlags) ||
            BufferedHangulMode.mustAvoidClipboard(flags)
        ) {
            BufferedInputTransport.DirectCommit
        } else {
            null
        }
        val preserveFailedRestart = bufferedHangulSessionActive && restarting &&
            !submitBufferedHangul(restartTransport)
        consumedPhysicalKeysDown.clear()
        val nextBufferedHangulSessionActive = bufferedHangulModeActive(
            fcitx.runImmediately { inputMethodEntryCached }
        )
        if (!preserveFailedRestart || !nextBufferedHangulSessionActive) {
            bufferedHangul.clear()
        }
        bufferedHangulSessionActive = nextBufferedHangulSessionActive
        inputView?.refreshBufferedHangulPreedit()
        typingDnaCommitSink.onEditorSessionStarted(attribute.packageName, attribute.fieldId, restarting)
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        capabilityFlags = flags
        resolveAndApplyAppKeyboardProfile(attribute, flags)
        // EditorInfo may change between onStartInput and onStartInputView
        inputDeviceMgr.notifyOnStartInput(attribute)
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        val isNullType = attribute.isTypeNull()
        val inputMethodUniqueName = fcitx.runImmediately { inputMethodEntryCached.uniqueName }
            .takeUnless { it.isBlank() || it == getString(R.string._not_available_) }
        engineRestartEditorRehydrationGate.onStartInput(
            inputSessionEpoch = inputSessionEpoch,
            identity = EditorIdentity.of(attribute),
            capabilityFlags = flags,
            shouldFocus = !isNullType,
            isVirtualKeyboard = inputDeviceMgr.isVirtualKeyboard,
            inputMethodUniqueName = inputMethodUniqueName
        )
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(effectiveCapabilityFlags(flags, inputMethodEntryCached))
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        cancelAutomaticSuggestionHideClose()
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
        val viewCapabilityFlags = CapabilityFlags.fromEditorInfo(info)
        engineRestartEditorRehydrationGate.onStartInputView(
            inputSessionEpoch = inputSessionEpoch,
            identity = EditorIdentity.of(info),
            capabilityFlags = viewCapabilityFlags,
            shouldFocus = !info.isTypeNull()
        )
        SensitivePhraseSession.onEditorChanged(
            DynamicPhraseEditorTarget(
                packageName = info.packageName,
                fieldId = info.fieldId,
                inputType = info.inputType,
                selectionStart = info.initialSelStart,
                selectionEnd = info.initialSelEnd
            )
        )
        // Browsers may replace EditorInfo between onStartInput and onStartInputView.
        resolveAndApplyAppKeyboardProfile(info, viewCapabilityFlags)
        postFcitxJob {
            focus(true)
        }
        if (inputDeviceMgr.evaluateOnStartInputView(info, this)) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
        } else {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        if (automaticSuggestionsSupported && !automaticSuggestionOptInRestored) {
            automaticSuggestionOptInRestored = true
            if (AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn.getValue()) {
                setAutomaticSuggestionsEnabled(true)
            }
        }
        startAutomaticSuggestionWarmupIfAllowed(info, viewCapabilityFlags)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        lastEditorActivityAtMs = SystemClock.elapsedRealtime()
        notifyOnDeviceContextSnapshotInvalidated()
        currentInputEditorInfo.let { info ->
            SensitivePhraseSession.onEditorChanged(
                DynamicPhraseEditorTarget(
                    packageName = info.packageName,
                    fieldId = info.fieldId,
                    inputType = info.inputType,
                    selectionStart = newSelStart,
                    selectionEnd = newSelEnd
                )
            )
        }
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd] cand=[$candidatesStart,$candidatesEnd]")
        handleCursorUpdate(
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            cursorUpdateIndex
        )
        inputView?.updateSelection(newSelStart, newSelEnd)
        personalLearning.flushTypingDnaIfEditorEmptied(newSelStart, newSelEnd)
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText?) {
        if (activeOnDeviceContextExtractedTextToken == token) {
            clearOnDeviceContextExtractedTextMonitor()
            onDeviceContextSnapshotInvalidationListener?.invoke()
            return
        }
        if (activeAutomaticSuggestionExtractedTextToken == token) {
            notifyAutomaticSuggestionSnapshotInvalidated()
            return
        }
        super.onUpdateExtractedText(token, text)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        newComposingStart: Int,
        newComposingEnd: Int,
        updateIndex: Int
    ) {
        // Prompt composition lives exclusively in Fcitx and its internal buffer. A delayed editor
        // selection callback must not restore a composing span or reset/focus the real editor, but
        // it must still update our identity snapshot so a pending tool action fails closed.
        if (internalPrompt.ownsInput) {
            selection.resetTo(newSelStart, newSelEnd)
            return
        }
        if (bufferedHangulSessionActive) {
            if (!selection.consume(newSelStart, newSelEnd)) {
                typingDnaCommitSink.onEditorContinuityLost(
                    currentInputEditorInfo?.packageName,
                    null,
                    allowsTextInspectionFeatures()
                )
                val engineHasPreedit = !bufferedHangulEngineResetPending &&
                    fcitx.runImmediately { inputPanelCached.preedit.isNotEmpty() }
                if (!bufferedHangul.isEmpty || engineHasPreedit) {
                    // The target has already moved its cursor, so the original insertion anchor
                    // cannot be restored reliably without using composing spans. Discard instead
                    // of surprising the user by pasting the segment at a different position.
                    Timber.i("Discarding buffered Hangul after an external selection change")
                    bufferedHangul.clear()
                    if (engineHasPreedit) queueBufferedHangulEngineReset()
                    inputView?.refreshBufferedHangulPreedit()
                }
                selection.resetTo(newSelStart, newSelEnd)
            }
            return
        }
        if (selection.consume(newSelStart, newSelEnd)) {
            // try restore composing range in case it was dropped by InputFilter
            // but only when prediction matches, since InputFilter can also change editor content
            // ref:
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/TextView.java#7351
            if (newComposingStart == -1 && newComposingEnd == -1 && composing.isNotEmpty()) {
                currentInputConnection?.setComposingRegion(composing.start, composing.end)
            }
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            if (composing.isEmpty() || newSelStart != newSelEnd || !composing.contains(newSelStart)) {
                typingDnaCommitSink.onEditorContinuityLost(
                    currentInputEditorInfo?.packageName,
                    null,
                    allowsTextInspectionFeatures()
                )
            }
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focusOutIn()
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        if (internalPrompt.updateInternalPromptPreedit(text.toString())) return
        // A stale empty ClientPreeditEvent can race the capability change. In buffered mode the
        // engine renders preedit in Fcitx's own input panel, never in the target InputConnection.
        if (bufferedHangulSessionActive) return
        val ic = currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        if (composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        ic.endBatchEdit()
        inputView?.postRefreshContextualCandidates(16L)
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        if (internalPrompt.finishComposingIfOwned()) return
        if (bufferedHangulSessionActive) {
            submitBufferedHangul()
            return
        }
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard || isInternalPromptInputOwned) {
            return null
        }
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        if (isInternalPromptInputOwned) {
            inputView?.handleInlineSuggestions(response)
            return true
        }
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        // 재료 생성의 경계는 입력 뷰 가시성이다. 자동 추천의 웜 유예(onKeyboardVisibilityChanged)와
        // 분리되어 숨김 즉시 배경 축적이 가능해진다.
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        notifyOnDeviceContextSnapshotInvalidated()
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
        scheduleAutomaticSuggestionHideClose()
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        val wasBufferedHangul = bufferedHangulSessionActive
        if (wasBufferedHangul) {
            submitBufferedHangul()
        }
        personalLearning.flushTypingDnaForCurrentEditor()
        if (finishingInput) {
            bufferedHangulSessionActive = false
            bufferedHangul.clear()
            consumedPhysicalKeysDown.clear()
        }
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        postFcitxJob {
            if (wasBufferedHangul) reset()
            focusOutIn()
        }
        hideStatusIcon()
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        notifyOnDeviceContextSnapshotInvalidated()
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = false)
        scheduleAutomaticSuggestionHideClose()
        engineRestartEditorRehydrationGate.onFinishInput()
        personalLearning.finalizeCorrectionSessionAtBoundary()
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        SensitivePhraseSession.lock()
        val wasBufferedHangul = bufferedHangulSessionActive
        if (wasBufferedHangul) {
            submitBufferedHangul()
        }
        personalLearning.flushTypingDnaForCurrentEditor()
        predictionMetricsSession.reset()
        personalLearning.onFinishInput()
        bufferedHangulSessionActive = false
        bufferedHangul.clear()
        postFcitxJob {
            if (wasBufferedHangul) reset()
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        notifyOnDeviceContextSnapshotInvalidated()
        engineRestartEditorRehydrationGate.onUnbindInput()
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        SensitivePhraseSession.lock()
        bufferedHangulSessionActive = false
        bufferedHangul.clear()
        consumedPhysicalKeysDown.clear()
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    /**
     * 시스템 메모리가 부족하면 자동 추천의 공유 엔진 사용을 내려놓고 엔진을 닫게 한다. 키보드가 숨겨질 때
     * 오는 UI_HIDDEN·BACKGROUND 단계는 무시한다: 그때마다 닫으면 다음 표시 때 GPU 초기화를 다시 치른다.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (!pressure) return
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
        OnDeviceSharedEngine.requestClose("TRIM_MEMORY_$level")
    }

    override fun onDestroy() {
        cancelAutomaticSuggestionHideClose()
        setAutomaticSuggestionsEnabled(false)
        invalidateAutomaticSuggestionsForClosedGate(closeBackend = true)
        OnDeviceSharedEngine.requestClose("SERVICE_DESTROYED")
        latestAutomaticSuggestionSnapshot = null
        clearAutomaticSuggestionTtl()
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        sentencePackRevisionJob?.cancel()
        sentencePackRevisionJob = null
        predictionScope.cancel()
        personalLearning.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
        SensitivePhraseSession.lock()
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        bufferedHangulInputPref.unregisterOnChangeListener(bufferedHangulInputListener)
        prefs.internal.automaticOnDeviceSuggestionsOptIn.unregisterOnChangeListener(automaticSuggestionOptInListener)
        OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        // IME windows may not resolve the platform dialog dim attribute on every OEM theme.
        // Keep the dialog usable rather than rejecting an otherwise valid user action.
        val dimAmount = runCatching { styledFloat(android.R.attr.backgroundDimAmount) }
            .getOrElse { exception ->
                Timber.w(exception, "Unable to resolve dialog dim amount; using fallback")
                0.32f
            }
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(dimAmount)
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    @Suppress("ConstPropertyName")
    companion object {
        @Volatile
        var activeInstance: FcitxInputMethodService? = null
            private set

        private const val KOREAN_PARTICLE_CONTEXT_CHARACTERS = 64
        val DeleteSurroundingFlag = "${BuildConfig.APPLICATION_ID}.DELETE_SURROUNDING"
        private const val SNIPPET_CONTEXT_CHARS = 128
    }
}
