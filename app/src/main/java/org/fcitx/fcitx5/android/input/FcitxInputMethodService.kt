/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.buffered.BufferedHangulMode
import org.fcitx.fcitx5.android.input.buffered.BufferedInputController
import org.fcitx.fcitx5.android.input.buffered.BufferedInputTransport
import org.fcitx.fcitx5.android.input.policy.DirectBootInputPolicy
import org.fcitx.fcitx5.android.input.policy.EngineRestartEditorRehydrationGate
import org.fcitx.fcitx5.android.input.prompt.InternalPromptDirectCommitResult
import org.fcitx.fcitx5.android.input.prompt.InternalPromptFinishResult
import org.fcitx.fcitx5.android.input.prompt.InternalPromptSpec
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
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
import org.fcitx.fcitx5.android.input.ai.AiApplyMode
import org.fcitx.fcitx5.android.input.ai.AiEditorTransaction
import org.fcitx.fcitx5.android.input.ai.AiInputCaptureResult
import org.fcitx.fcitx5.android.input.ai.AiInputSnapshot
import org.fcitx.fcitx5.android.input.ai.AiSuggestionApplyResult
import org.fcitx.fcitx5.android.input.ai.AiTextSource
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.learning.CollectionFeedbackEvent
import org.fcitx.fcitx5.android.input.ai.learning.PersonalLearningController
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsSession
import org.fcitx.fcitx5.android.input.ai.ondevice.AiRuntimeStatusStore
import org.fcitx.fcitx5.android.input.ai.ondevice.AutomaticSuggestionController
import org.fcitx.fcitx5.android.input.ai.ondevice.ExtractedTextTokens
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceContextCompletionController
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionRuntime
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator
import org.fcitx.fcitx5.android.input.ai.ondevice.RecentSentSentences
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualAppendSnapshot
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualCandidateSnapshot
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualPredictionController
import org.fcitx.fcitx5.android.input.ai.prediction.ContextualReplacementSnapshot
import org.fcitx.fcitx5.android.input.context.KoreanParticleCommitContract
import org.fcitx.fcitx5.android.input.context.KoreanParticleEditorTarget
import org.fcitx.fcitx5.android.input.context.KoreanParticleSnapshot
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggester
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.policy.InputFeaturePolicy
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
import org.fcitx.fcitx5.android.input.voice.DictationErasePolicy
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

class FcitxInputMethodService : LifecycleInputMethodService() {

    suspend fun triggerInstantTypingDnaSyncAsync() = personalLearning.triggerInstantTypingDnaSyncAsync()

    val isDirectBootInputMode: Boolean
        get() = FcitxApplication.getInstance().isDirectBootMode

    private val allowsCredentialProtectedFeatures: Boolean
        get() = DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootInputMode)

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
    private val featurePolicy: InputFeaturePolicy = InputFeaturePolicy(
        isDirectBootMode = { isDirectBootInputMode },
        editorInfo = { currentInputEditorInfo },
        capabilityFlags = { capabilityFlags },
        offlineMode = { offlineMode },
        appProfile = { effectiveAppProfile }
    )

    private val extractedTextTokens = ExtractedTextTokens()
    private val onDeviceContextCompletion: OnDeviceContextCompletionController = OnDeviceContextCompletionController(
        tokens = extractedTextTokens,
        host = OnDeviceContextCompletionController.Host(
            allowsCompletion = ::allowsOnDeviceContextCompletionFeatures,
            editorInfo = { currentInputEditorInfo },
            selection = { currentInputSelection },
            inputSessionEpoch = { inputSessionEpoch },
            inputConnection = { currentInputConnection },
            finishCompositionForDirectAction = ::finishCompositionForDirectAction,
            matchesCurrentEditor = ::matchesCurrentEditor,
            commitAiTextAtCursor = ::commitAiTextAtCursor,
            predictSelection = { position -> selection.predict(position) },
            onSnapshotInvalidated = { automaticSuggestion.onContextSnapshotInvalidated() }
        )
    )

    private val automaticSuggestion: AutomaticSuggestionController = AutomaticSuggestionController(
        tokens = extractedTextTokens,
        host = AutomaticSuggestionController.Host(
            createRuntime = { useGpu -> OnDeviceAutomaticSuggestionRuntime(this, useGpu) },
            createStatusStore = { AiRuntimeStatusStore(this) },
            lifecycleScope = { lifecycleScope },
            inputView = { inputView },
            editorInfo = { currentInputEditorInfo },
            capabilityFlags = { capabilityFlags },
            inputConnection = { currentInputConnection },
            selection = { currentInputSelection },
            inputSessionEpoch = { inputSessionEpoch },
            isDirectBootMode = { isDirectBootInputMode },
            appAiPolicy = { effectiveAppProfile?.source?.aiPolicy },
            allowsCompletion = ::allowsOnDeviceContextCompletionFeatures,
            isPromptInputOwned = { internalPrompt.isInternalPromptInputOwned },
            isPromptCaptureActive = { internalPrompt.isInternalPromptCaptureActive },
            isContextCompletionMonitorActive = { onDeviceContextCompletion.activeExtractedTextToken != null },
            hasContextCompletionListener = { onDeviceContextCompletion.hasSnapshotInvalidationListener },
            personalSentenceVault = { personalLearning.personalSentenceVault },
            recentSentSentences = { personalLearning.recentSentSentences },
            lastEditorActivityAtMs = { lastEditorActivityAtMs },
            isBufferedHangulSession = { bufferedHangulSessionActive },
            bufferedHangulPrefix = { bufferedHangulPrefix },
            isBufferedEngineResetPending = { bufferedHangulEngineResetPending },
            enginePreedit = { fcitx.runImmediately { inputPanelCached.preedit } },
            composing = { composing },
            composingText = { composingText },
            finishCompositionForDirectAction = ::finishCompositionForDirectAction,
            commitAiTextAtCursor = ::commitAiTextAtCursor,
            predictSelection = { position -> selection.predict(position) }
        )
    )

    /** 마지막으로 에디터 선택이 바뀐 시각(elapsedRealtime). 워밍업을 입력이 멈춘 뒤로 미루는 데 쓴다. */
    @Volatile
    private var lastEditorActivityAtMs = 0L
    private var appliedInputThemeName: String? = null

    private val bufferedHangul = BufferedInputController()
    private var bufferedHangulSessionActive = false
    @Volatile
    private var bufferedHangulEngineResetPending = false
    private val consumedPhysicalKeysDown = mutableSetOf<Int>()

    @Volatile
    private var snippetCatalog = SnippetCatalog.builtIns()
    private var snippetRefreshJob: Job? = null

    private val internalPrompt: InternalPromptController = InternalPromptController(
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

    private val personalLearning: PersonalLearningController = PersonalLearningController(
        PersonalLearningController.Host(
            filesDir = { filesDir },
            collocationModel = { contextualPrediction.contextualPredictor.collocationModel },
            advancePredictionEpoch = { contextualPrediction.advancePredictionEpoch() },
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

    private val contextualPrediction: ContextualPredictionController = ContextualPredictionController(
        ContextualPredictionController.Host(
            lifecycleScope = { lifecycleScope },
            inputView = { inputView },
            allowsTextInspection = ::allowsTextInspectionFeatures,
            editorInfo = { currentInputEditorInfo },
            capabilityFlags = { capabilityFlags },
            inputConnection = { currentInputConnection },
            selection = { currentInputSelection },
            inputSessionEpoch = { inputSessionEpoch },
            activePreedit = ::activePreeditForContextualInput,
            finishCompositionForDirectAction = ::finishCompositionForDirectAction,
            commitTextToEditor = { text, cursor -> commitTextToEditor(text, cursor) },
            replaceAiRange = ::replaceAiRange,
            commitAiTextAtCursor = ::commitAiTextAtCursor,
            predictSelection = { position -> selection.predict(position) },
            morphologyEngine = { personalLearning.morphologyEngine },
            personalizedStore = { personalLearning.personalizedStore },
            personalNgramModel = { personalLearning.personalNgramModel },
            typoCorrector = { personalLearning.typoCorrector },
            baseKoreanVocabulary = { personalLearning.baseKoreanVocabulary },
            correctionPatternStore = { personalLearning.correctionPatternStore },
            personalSentenceVault = { personalLearning.personalSentenceVault },
            personalGraphStore = { personalLearning.personalGraphStore },
            reinforcementTracker = { personalLearning.reinforcementTracker },
            enqueuePersonalLearning = { action -> personalLearning.enqueuePersonalLearning(action = action) },
            enqueueContextualSelectionFeedback = { context, selected, reinforced, packageName ->
                personalLearning.enqueueContextualSelectionFeedback(context, selected, reinforced, packageName)
            },
            scheduleNgramSave = { personalLearning.scheduleNgramSave() },
            observeCommittedEditorText = ::observeCommittedEditorText,
            captureCorrectionBoundarySnapshot = { personalLearning.captureCorrectionBoundarySnapshot() },
            onEditorSuffixDeleted = { packageName, removedText, inspectionAllowed ->
                personalLearning.typingDnaCommitSink.onEditorSuffixDeleted(packageName, removedText, inspectionAllowed)
            }
        )
    )

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
        // The bottom rows are built with or without the microphone key (and the period comma key
        // that comes with it), so the setting only takes effect by rebuilding the input view.
        prefs.keyboard.showVoiceInputButton,
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
        newInputView.updateAutomaticSuggestionIndicator(automaticSuggestion.indicator)
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
        ime: InputMethodEntry,
        packageName: String?
    ): CapabilityFlags = BufferedHangulMode.effectiveCapabilities(
        flags,
        bufferedHangulInputPref.getValue(),
        ime,
        packageName
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
            setCapFlags(
                effectiveCapabilityFlags(
                    capabilityFlags,
                    inputMethodEntryCached,
                    currentInputEditorInfo?.packageName
                )
            )
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
            setCapFlags(
                effectiveCapabilityFlags(
                    latestPlan.capabilityFlags,
                    inputMethodEntryCached,
                    latestPlan.packageName
                )
            )
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
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = resources.configuration
        if (allowsCredentialProtectedFeatures) {
            attachCredentialProtectedFeatures()
        }
    }

    /**
     * Direct Boot (before the first unlock after a reboot) has no credential-encrypted storage, and
     * the process is restarted on unlock. Everything that reads or writes it is therefore attached
     * only here, never in Direct Boot, so the keyboard keeps just the basic typing path alive.
     */
    private fun attachCredentialProtectedFeatures() {
        automaticSuggestion.registerListeners()
        contextualPrediction.observeSentencePackRevision()
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
                            personalLearning.correctionSessionTracker.onBackspace(personalLearning.currentWordBeforeCursor())
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
                            notifyTypingDnaContinuityLost(dnaRemovedText, dnaInspectionAllowed)
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
                automaticSuggestion.notifyAutomaticSuggestionPreeditChanged(clientPreedit = event.data.toString())
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
                automaticSuggestion.notifyAutomaticSuggestionPreeditChanged(inputPanelPreedit = event.data.preedit.toString())
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
                    setCapFlags(
                        effectiveCapabilityFlags(
                            capabilityFlags,
                            event.data,
                            currentInputEditorInfo?.packageName
                        )
                    )
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
            notifyTypingDnaContinuityLost(dnaRemovedText, dnaInspectionAllowed)
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
            onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
        }
        inputView?.postRefreshContextualCandidates(16L)
    }

    private fun handleBackspaceKey() {
        if (internalPrompt.deleteInternalPromptBeforeCursor(1)) return
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
        val dnaInspectionAllowed = allowsTextInspectionFeatures()
        val dnaRemovedText = if (dnaInspectionAllowed) {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
        } else {
            null
        }
        notifyTypingDnaContinuityLost(dnaRemovedText, dnaInspectionAllowed)
        if (dnaInspectionAllowed) {
            personalLearning.correctionSessionTracker.onBackspace(personalLearning.currentWordBeforeCursor())
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
        flushTypingDnaForCurrentEditor()
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
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
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

    /** Typing DNA collection lives in credential-protected storage, so Direct Boot never starts it. */
    private fun notifyTypingDnaContinuityLost(removedText: String?, inspectionAllowed: Boolean) {
        if (!allowsCredentialProtectedFeatures) return
        personalLearning.typingDnaCommitSink.onEditorContinuityLost(
            currentInputEditorInfo?.packageName,
            removedText,
            inspectionAllowed
        )
    }

    private fun flushTypingDnaForCurrentEditor() {
        if (!allowsCredentialProtectedFeatures) return
        personalLearning.flushTypingDnaForCurrentEditor()
    }

    /**
     * A messenger clears its input field after sending, so an emptied editor means the pending
     * sentence was sent and is flushed whole; any other external cursor move only breaks continuity.
     */
    private fun notifyTypingDnaExternalCursorMove(newSelStart: Int, newSelEnd: Int) {
        val inspectionAllowed = allowsTextInspectionFeatures()
        if (inspectionAllowed && isEditorEmptied(newSelStart, newSelEnd, currentInputConnection)) {
            flushTypingDnaForCurrentEditor()
        } else {
            notifyTypingDnaContinuityLost(null, inspectionAllowed)
        }
    }

    private fun observeCommittedEditorText(text: String) {
        if (!allowsCredentialProtectedFeatures) return
        personalLearning.observeCommittedEditorText(text)
    }

    fun allowsTextInspectionFeatures(): Boolean = featurePolicy.allowsTextInspectionFeatures()

    fun allowsNetworkInputFeatures(): Boolean = featurePolicy.allowsNetworkInputFeatures()

    fun allowsOnDeviceContextCompletionFeatures(): Boolean =
        featurePolicy.allowsOnDeviceContextCompletionFeatures()

    fun setOnDeviceContextSnapshotInvalidationListener(listener: (() -> Unit)?) =
        onDeviceContextCompletion.setOnDeviceContextSnapshotInvalidationListener(listener)

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
        restore: EditorSelection
    ): Boolean = AiEditorTransaction.replaceRange(
        start = start,
        end = end,
        replacement = replacement,
        restore = restore,
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
        restore: EditorSelection
    ): Boolean = AiEditorTransaction.commitAtCursor(
        cursor = cursor,
        text = text,
        restore = restore,
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

    /** Canonical "is this still the same editor field" check: identity, then selection, then an optional session epoch. */
    fun matchesCurrentEditor(
        identity: EditorIdentity,
        selection: EditorSelection,
        expectedInputSessionEpoch: Long? = null
    ): Boolean =
        currentInputEditorInfo?.let(EditorIdentity::of)?.sameField(identity) == true &&
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

    /** Finishes composition for on-device dictation so its text lands after everything already typed. */
    fun prepareDictationCommit(): Boolean {
        if (!allowsTextInspectionFeatures()) return false
        return finishCompositionForDirectAction()
    }

    /**
     * Removes [expected] in front of the cursor, but only while it is exactly what is there. Text
     * typed or a cursor moved since it was dictated makes this do nothing and return false.
     */
    fun eraseDictatedText(expected: String): Boolean {
        if (!allowsTextInspectionFeatures() || currentInputSelection.isNotEmpty()) return false
        if (!finishCompositionForDirectAction()) return false
        val ic = currentInputConnection ?: return false
        if (!DictationErasePolicy.canErase(ic.getTextBeforeCursor(expected.length, 0), expected)) {
            return false
        }
        val deleted = ic.deleteSurroundingText(expected.length, 0)
        if (deleted) {
            selection.predictOffset(-expected.length)
            onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
            inputView?.postRefreshContextualCandidates(16L)
        }
        return deleted
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

    fun captureOnDeviceContextSnapshot(): AiInputCaptureResult =
        onDeviceContextCompletion.captureOnDeviceContextSnapshot()

    fun isOnDeviceContextSnapshotCurrent(snapshot: AiInputSnapshot): Boolean =
        onDeviceContextCompletion.isOnDeviceContextSnapshotCurrent(snapshot)

    fun applyOnDeviceContextCompletion(
        snapshot: AiInputSnapshot,
        suffix: String
    ): AiSuggestionApplyResult = onDeviceContextCompletion.applyOnDeviceContextCompletion(snapshot, suffix)

    val automaticSuggestionsSupported: Boolean
        get() = automaticSuggestion.automaticSuggestionsSupported

    val automaticSuggestionsEnabled: Boolean
        get() = automaticSuggestion.automaticSuggestionsEnabled

    val automaticSuggestionsUseGpu: Boolean
        get() = automaticSuggestion.automaticSuggestionsUseGpu

    val automaticSuggestionBackendFallbackOccurred: Boolean
        get() = automaticSuggestion.automaticSuggestionBackendFallbackOccurred

    val automaticSuggestionStatus: OnDeviceSuggestionCoordinator.Status
        get() = automaticSuggestion.automaticSuggestionStatus

    val automaticSuggestionWarmupState: OnDeviceAutomaticSuggestionWarmupState
        get() = automaticSuggestion.automaticSuggestionWarmupState

    val automaticSuggestionWarmupFailureCode: String?
        get() = automaticSuggestion.automaticSuggestionWarmupFailureCode

    val automaticSuggestionRuntimeWarm: Boolean
        get() = automaticSuggestion.automaticSuggestionRuntimeWarm

    fun setAutomaticSuggestionsEnabled(enabled: Boolean) =
        automaticSuggestion.setAutomaticSuggestionsEnabled(enabled)

    fun setAutomaticSuggestionsUseGpu(useGpu: Boolean): Boolean =
        automaticSuggestion.setAutomaticSuggestionsUseGpu(useGpu)

    fun getAutomaticSuggestionCandidates(): List<OnDeviceSuggestionCoordinator.Candidate> =
        automaticSuggestion.getAutomaticSuggestionCandidates()

    fun commitAutomaticSuggestionCandidate(
        candidate: OnDeviceSuggestionCoordinator.Candidate
    ): AiSuggestionApplyResult = automaticSuggestion.commitAutomaticSuggestionCandidate(candidate)

    fun retryAutomaticSuggestionWarmup() = automaticSuggestion.retryAutomaticSuggestionWarmup()

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

    fun getContextualSentencePredictions(limit: Int = 2): List<CandidateWord> =
        contextualPrediction.getContextualSentencePredictions(limit)

    fun getContextualWordPredictions(limit: Int = 4): List<CandidateWord> =
        contextualPrediction.getContextualWordPredictions(limit)

    fun getContextualCandidateSnapshot(wordLimit: Int = 4, sentenceLimit: Int = 2): ContextualCandidateSnapshot =
        contextualPrediction.getContextualCandidateSnapshot(wordLimit, sentenceLimit)

    fun recordContextualCandidateShown(candidate: PredictionMetricsSession.Candidate?) =
        contextualPrediction.recordContextualCandidateShown(candidate)

    fun recordContextualCandidatesIgnored(offeredSentences: List<String>) =
        contextualPrediction.recordContextualCandidatesIgnored(offeredSentences)

    fun recordContextualCandidateRejected(sentence: String, heavyPenalty: Boolean = true) =
        contextualPrediction.recordContextualCandidateRejected(sentence, heavyPenalty)

    fun commitContextualSentence(
        sentence: String,
        metricsCandidate: PredictionMetricsSession.Candidate? = null,
        appendSnapshot: ContextualAppendSnapshot? = null,
        replacementSnapshot: ContextualReplacementSnapshot? = null
    ): Boolean = contextualPrediction.commitContextualSentence(
        sentence,
        metricsCandidate,
        appendSnapshot,
        replacementSnapshot
    )

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
            onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
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
        val submitsFirst = BufferedHangulMode.submitsBeforeForwarding(data.sym.sym, data.unicode)
        val bufferedKey = when (data.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace,
            FcitxKeyMapping.FcitxKey_Return -> true
            else -> submitsFirst || data.unicode > 0
        }
        if (!bufferedKey) return false
        if (data.up) return data.states.virtual
        val handled = when (data.sym.sym) {
            FcitxKeyMapping.FcitxKey_BackSpace -> {
                val preeditEmpty = fcitx.runImmediately { inputPanelCached.preedit.isEmpty() }
                if (preeditEmpty) {
                    if (allowsTextInspectionFeatures()) {
                        personalLearning.correctionSessionTracker.onBackspace(personalLearning.currentWordBeforeCursor())
                    }
                    if (bufferedHangul.deleteLastCharacter()) {
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
            // Navigation and control keys never become buffered text. Moving the target cursor
            // would discard a pending segment as an unexpected selection change, so submit it
            // first, then send the key itself with its modifiers, such as Shift for selection.
            // A key without an Android key code continues on the normal forwarding path once
            // the segment is submitted.
            else -> if (submitsFirst) {
                val keyCode = data.sym.keyCode
                val submitted = submitBufferedHangul()
                if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    !submitted
                } else {
                    if (submitted) {
                        sendCombinationKeyEvents(
                            keyCode,
                            alt = data.states.alt,
                            ctrl = data.states.ctrl,
                            shift = data.states.shift
                        )
                    }
                    true
                }
            } else if (data.unicode > 0) {
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
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
        inputSessionEpoch += 1
        personalLearning.onStartInput()
        contextualPrediction.onStartInput()
        personalLearning.correctionSessionTracker.onEditorChanged()
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
        if (allowsCredentialProtectedFeatures) {
            // An app that clears its field after sending (setText("")) restarts the same field empty
            // instead of moving the cursor, so the pending text was sent and must not glue onto the next one.
            if (restarting && allowsTextInspectionFeatures() &&
                isEditorEmptied(attribute.initialSelStart, attribute.initialSelEnd, currentInputConnection)
            ) {
                flushTypingDnaForCurrentEditor()
            }
            personalLearning.typingDnaCommitSink.onEditorSessionStarted(attribute.packageName, attribute.fieldId, restarting)
        }
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
            setCapFlags(
                effectiveCapabilityFlags(flags, inputMethodEntryCached, attribute.packageName)
            )
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        if (allowsCredentialProtectedFeatures) {
            automaticSuggestion.cancelAutomaticSuggestionHideClose()
        }
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
        if (allowsCredentialProtectedFeatures) {
            automaticSuggestion.onStartInputView(info, viewCapabilityFlags)
        }
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
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
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
        if (allowsCredentialProtectedFeatures) {
            personalLearning.flushTypingDnaIfEditorEmptied(newSelStart, newSelEnd)
        }
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText?) {
        if (onDeviceContextCompletion.activeExtractedTextToken == token) {
            onDeviceContextCompletion.onExtractedTextUpdated()
            return
        }
        if (automaticSuggestion.activeExtractedTextToken == token) {
            automaticSuggestion.notifyAutomaticSuggestionSnapshotInvalidated()
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
                notifyTypingDnaExternalCursorMove(newSelStart, newSelEnd)
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
                notifyTypingDnaExternalCursorMove(newSelStart, newSelEnd)
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
        inputView?.finishInlineDictation()
        // 재료 생성의 경계는 입력 뷰 가시성이다. 자동 추천의 웜 유예(onKeyboardVisibilityChanged)와
        // 분리되어 숨김 즉시 배경 축적이 가능해진다.
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
        if (allowsCredentialProtectedFeatures) {
            automaticSuggestion.onFinishInputView()
        }
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        val wasBufferedHangul = bufferedHangulSessionActive
        if (wasBufferedHangul) {
            submitBufferedHangul()
        }
        flushTypingDnaForCurrentEditor()
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
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
        if (allowsCredentialProtectedFeatures) {
            automaticSuggestion.onFinishInput()
        }
        engineRestartEditorRehydrationGate.onFinishInput()
        personalLearning.finalizeCorrectionSessionAtBoundary()
        internalPrompt.cancelInternalPromptCapture(discardPreStartCallbacks = true)
        SensitivePhraseSession.lock()
        val wasBufferedHangul = bufferedHangulSessionActive
        if (wasBufferedHangul) {
            submitBufferedHangul()
        }
        flushTypingDnaForCurrentEditor()
        contextualPrediction.onFinishInput()
        if (allowsCredentialProtectedFeatures) {
            personalLearning.onFinishInput()
        }
        bufferedHangulSessionActive = false
        bufferedHangul.clear()
        postFcitxJob {
            if (wasBufferedHangul) reset()
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        onDeviceContextCompletion.notifyOnDeviceContextSnapshotInvalidated()
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
        if (!pressure || !allowsCredentialProtectedFeatures) return
        automaticSuggestion.onTrimMemory(level)
    }

    override fun onDestroy() {
        val credentialProtectedFeaturesAttached = allowsCredentialProtectedFeatures
        if (credentialProtectedFeaturesAttached) {
            automaticSuggestion.onDestroy()
        }
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        contextualPrediction.onDestroy()
        if (credentialProtectedFeaturesAttached) {
            personalLearning.onDestroy()
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        SensitivePhraseSession.lock()
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        bufferedHangulInputPref.unregisterOnChangeListener(bufferedHangulInputListener)
        if (credentialProtectedFeaturesAttached) {
            automaticSuggestion.unregisterListeners()
        }
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

/** True only when the cursor is at 0 and the editor confirms there is no text on either side. */
internal fun isEditorEmptied(newSelStart: Int, newSelEnd: Int, ic: InputConnection?): Boolean {
    if (newSelStart != 0 || newSelEnd != 0 || ic == null) return false
    return isEditorEmptied(newSelStart, newSelEnd, ic.getTextBeforeCursor(1, 0), ic.getTextAfterCursor(1, 0))
}

internal fun isEditorEmptied(
    newSelStart: Int,
    newSelEnd: Int,
    textBefore: CharSequence?,
    textAfter: CharSequence?
): Boolean = newSelStart == 0 && newSelEnd == 0 && textBefore?.isEmpty() == true && textAfter?.isEmpty() == true
