/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Color
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.ai.AiAssistantWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.BufferedHangulWindow
import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.dynamicphrase.DynamicPhraseEditorTarget
import org.fcitx.fcitx5.android.input.dynamicphrase.SensitivePhraseWindow
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.gif.GifSearchWindow
import org.fcitx.fcitx5.android.input.ocr.OcrWindow
import org.fcitx.fcitx5.android.input.search.KoreanSearchWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.typo.TypoRecoveryWindow
import org.fcitx.fcitx5.android.input.voice.VoiceProviderModeStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderPolicy
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val showNumberRow by prefs.keyboard.showNumberRow
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var inlineSuggestionGeneration = 0L
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false
    private var hasStartedInput: Boolean = false
    private var expandToolbarForEditor: Boolean = expandToolbarByDefault
    private var toolbarNeedsSecondRow: Boolean = false
    private var lastPreeditEmpty: Boolean = true
    private var lastCandidateListEmpty: Boolean = true

    // Suggestion row (candidateUi.root) visibility, latched to avoid flicker when preedit
    // momentarily empties out mid-composition. See onCandidatesVisibilityChanged().
    private var candidateRowVisible: Boolean = false
    private var candidateRowCollapseRunnable: Runnable? = null
    private var toolbarHeightSession = ToolbarHeightSession.start(
        toolbarVisible = expandToolbarForEditor,
        needsSecondRow = false
    )

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    private fun isToolbarRequested(): Boolean =
        expandToolbarForEditor != isToolbarManuallyToggled

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    idleUi.clipboardUi.text.text = if (it.sensitive && clipboardMaskSensitive) {
                        ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                    } else {
                        it.text.take(42)
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                isClipboardFresh = false
                evalIdleUiState()
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // renew timeout when clipboard suggestion is present
                    launchClipboardTimeoutJob()
                }
                else -> {}
            }
        }

    private val bufferedHangulInputPref = prefs.advanced.bufferedHangulInput
    private val bufferedHangulTransportPref = prefs.advanced.bufferedHangulTransport

    @Keep
    private val onBufferedHangulInputChangeListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ ->
            updateBufferedHangulButtonVisual()
        }

    @Keep
    private val onBufferedHangulTransportChangeListener =
        ManagedPreference.OnChangeListener<BufferedInputTransport> { _, _ ->
            updateBufferedHangulButtonVisual()
        }

    private fun updateBufferedHangulButtonVisual() {
        val isEnabled = bufferedHangulInputPref.getValue()
        val color = if (isEnabled) theme.accentKeyBackgroundColor else theme.altKeyTextColor
        idleUi.buttonsUi.bufferedHangulButton.setIconTint(color)
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout < 0
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            isClipboardFresh -> IdleUi.State.Clipboard
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * isToolbarManuallyToggled |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarForEditor == isToolbarManuallyToggled -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState != idleUi.currentState) {
            idleUi.updateState(newState, fromUser)
        }
        when {
            newState == IdleUi.State.Toolbar -> {
                toolbarHeightSession = toolbarHeightSession.onToolbarVisibilityChanged(
                    visible = true,
                    needsSecondRow = toolbarNeedsSecondRow
                )
            }
            fromUser && newState == IdleUi.State.Empty -> {
                toolbarHeightSession = toolbarHeightSession.onToolbarVisibilityChanged(
                    visible = false,
                    needsSecondRow = toolbarNeedsSecondRow
                )
            }
            else -> {
                toolbarHeightSession = toolbarHeightSession.onTransientSurfaceChanged()
            }
        }
        updateBarHeight()
    }

    private val hideKeyboardCallback = View.OnClickListener {
        service.requestHideSelf(0)
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        require(v is ToolButton)
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // `e.x` and `e.y` are relative to the view's top-left corner
            val centerX = e.x - v.width / 2f
            val centerY = e.y - v.height / 2f

            val distance = hypot(centerX, centerY)
            // the button is ↓, so apply -90 degrees offset
            var angle = atan2(-centerX, centerY) * (180f / PI.toFloat())

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.iconRotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val handled = when (angle) {
                        in -45f..45f if distance > v.swipeThresholdX -> {
                            service.requestHideSelf(0)
                            true
                        }
                        !in -45f..45f if distance > v.swipeThresholdY -> {
                            v.iconRotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.iconRotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

    private val switchToVoiceInputCallback = View.OnClickListener {
        val (id, subtype) = voiceInputSubtype ?: return@OnClickListener
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    private val idleUi: IdleUi by lazy {
        IdleUi(context, theme, popup, commonKeyActionListener).apply {
            menuButton.setOnClickListener {
                when (idleUi.currentState) {
                    IdleUi.State.Empty -> {
                        isToolbarManuallyToggled = !expandToolbarForEditor
                        evalIdleUiState(fromUser = true)
                    }
                    IdleUi.State.Toolbar -> {
                        isToolbarManuallyToggled = expandToolbarForEditor
                        evalIdleUiState(fromUser = true)
                    }
                    else -> {
                        isToolbarManuallyToggled = !expandToolbarForEditor
                        idleUi.updateState(IdleUi.State.Toolbar, fromUser = true)
                    }
                }
                toolbarHeightSession = toolbarHeightSession.onToolbarVisibilityChanged(
                    visible = idleUi.currentState == IdleUi.State.Toolbar,
                    needsSecondRow = toolbarNeedsSecondRow
                )
                updateBarHeight()
                // reset timeout timer (if present) when user switch layout
                if (clipboardTimeoutJob != null) {
                    launchClipboardTimeoutJob()
                }
            }
            hideKeyboardButton.apply {
                setOnClickListener(hideKeyboardCallback)
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                swipeThresholdX = swipeThresholdY
                onGestureListener = swipeHideKeyboardCallback
            }
            buttonsUi.apply {
                fun canOpenEditorTool() = !service.isInternalPromptInputOwned

                onNeedsSecondRowChanged = {
                    toolbarNeedsSecondRow = it
                    toolbarHeightSession = toolbarHeightSession.onToolbarRowsChanged(
                        toolbarVisible = isToolbarRequested(),
                        expanded = it
                    )
                    // Row expansion originates from a child click. Defer the host resize to the
                    // next frame so ConstraintLayout applies the new two-row child and ancestor
                    // height in the same settled pass.
                    view.post { updateBarHeight() }
                }
                undoButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
                }
                redoButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
                }
                cursorMoveButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(TextEditingWindow())
                }
                clipboardButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(ClipboardWindow())
                }
                clipboardButton.setOnLongClickListener {
                    if (!canOpenEditorTool()) return@setOnLongClickListener true
                    windowManager.attachWindow(org.fcitx.fcitx5.android.tab.UnifiedTabExtensionWindow(org.fcitx.fcitx5.android.tab.TabId.CLIPBOARD))
                    true
                }
                bufferedHangulButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(BufferedHangulWindow())
                }
                bufferedHangulButton.setOnLongClickListener {
                    if (!canOpenEditorTool()) return@setOnLongClickListener true
                    val currentEnabled = prefs.advanced.bufferedHangulInput.getValue()
                    val newEnabled = !currentEnabled
                    prefs.advanced.bufferedHangulInput.setValue(newEnabled)
                    val toastMsg = if (newEnabled) {
                        context.getString(
                            R.string.buffered_hangul_toast_on,
                            context.getString(prefs.advanced.bufferedHangulTransport.getValue().stringRes)
                        )
                    } else {
                        context.getString(R.string.buffered_hangul_toast_off)
                    }
                    android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                quickPhraseButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    service.postFcitxJob {
                        reset()
                        triggerQuickPhrase()
                    }
                }
                quickPhraseButton.setOnLongClickListener {
                    if (!canOpenEditorTool()) return@setOnLongClickListener true
                    val info = service.currentInputEditorInfo
                    val selection = service.currentInputSelection
                    windowManager.attachWindow(SensitivePhraseWindow(
                        DynamicPhraseEditorTarget(
                            packageName = info.packageName,
                            fieldId = info.fieldId,
                            inputType = info.inputType,
                            selectionStart = selection.start,
                            selectionEnd = selection.end
                        )
                    ))
                    true
                }
                koreanSearchButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(KoreanSearchWindow())
                }
                typoRecoveryButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(TypoRecoveryWindow())
                }
                aiAssistantButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(AiAssistantWindow())
                }
                precisionDictationButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(VoiceTranscriptionWindow())
                }
                ocrButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(OcrWindow())
                }
                gifButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(GifSearchWindow())
                }
                gifButton.setOnLongClickListener {
                    if (!canOpenEditorTool()) return@setOnLongClickListener true
                    windowManager.attachWindow(org.fcitx.fcitx5.android.tab.UnifiedTabExtensionWindow(org.fcitx.fcitx5.android.tab.TabId.MEDIA))
                    true
                }
                moreButton.setOnClickListener {
                    if (!canOpenEditorTool()) return@setOnClickListener
                    windowManager.attachWindow(StatusAreaWindow())
                }
                moreButton.setOnLongClickListener {
                    if (!canOpenEditorTool()) return@setOnLongClickListener true
                    windowManager.attachWindow(org.fcitx.fcitx5.android.tab.UnifiedTabExtensionWindow(org.fcitx.fcitx5.android.tab.TabId.SETTINGS))
                    true
                }
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val entry = ClipboardManager.lastEntry ?: return@setOnClickListener
                    if (!service.insertImeText(entry.text)) return@setOnClickListener
                    clipboardTimeoutJob?.cancel()
                    clipboardTimeoutJob = null
                    isClipboardFresh = false
                    evalIdleUiState()
                }
                setOnLongClickListener {
                    ClipboardManager.lastEntry?.let {
                        AppUtil.launchClipboardEdit(context, it.id, true)
                    }
                    true
                }
            }
            numberRow.apply {
                onCollapseListener = {
                    numberRowState = NumberRowState.ForceHide
                    evalIdleUiState(fromUser = true)
                }
            }
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            // Idle state (no candidates yet) starts collapsed; onCandidatesVisibilityChanged()
            // expands it as soon as HorizontalCandidateComponent reports a visible candidate.
            root.visibility = View.GONE
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    // Normal (non-Title) mode always shows two stacked rows: the tool row on top and the
    // candidate row below it. Neither row is ever hidden by the other; the initial heights here
    // are placeholders that updateBarHeight() immediately corrects once real state is known.
    private val normalRoot: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                idleUi.root,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(HEIGHT))
            )
            addView(
                candidateUi.root,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(HEIGHT))
            )
        }
    }

    val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }
            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }
            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(
                when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        candidateUi.expandButton.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        // Idle and Candidate both display normalRoot (tool row + candidate row, always visible
        // together). Only Title swaps the whole bar out for the extended-window title row.
        val index = if (state == KawaiiBarStateMachine.State.Title) TITLE_CHILD_INDEX else NORMAL_CHILD_INDEX
        if (view.displayedChild != index) {
            if (index != TITLE_CHILD_INDEX) {
                titleUi.setReturnButtonOnClickListener { }
                titleUi.setTitle("")
                titleUi.removeExtension()
            }
            view.displayedChild = index
        }
        updateBarHeight()
    }

    override val view by lazy {
        ViewAnimator(context).apply {
            backgroundColor =
                if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT
                else theme.barColor
            add(normalRoot, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    cancelCandidateRowCollapse()
                }
            })
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        if (service.isDirectBootInputMode) {
            isClipboardFresh = false
            return
        }
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
        bufferedHangulInputPref.registerOnChangeListener(onBufferedHangulInputChangeListener)
        bufferedHangulTransportPref.registerOnChangeListener(onBufferedHangulTransportChangeListener)
        updateBufferedHangulButtonVisual()
    }

    override fun onStartInput(
        info: EditorInfo,
        capFlags: CapabilityFlags,
        restarting: Boolean
    ) {
        updateBufferedHangulButtonVisual()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        // The pinned number row already covers password fields, so don't stack a second one here.
        isCapabilityFlagsPassword =
            toolbarNumRowOnPassword && !showNumberRow && capFlags.has(CapabilityFlag.Password)
        val allowsTextInspection = service.allowsTextInspectionFeatures()
        val allowsNetwork = service.allowsNetworkInputFeatures()
        val allowsAi = service.allowsAiInputFeatures()
        val previouslyRequested = isToolbarRequested()
        if (!restarting || !hasStartedInput) {
            // Every new editor starts compact. A same-editor Android restart preserves the user's
            // explicit two-row session, just like the toolbar visibility state below.
            idleUi.buttonsUi.collapse()
        }
        expandToolbarForEditor = service.effectiveToolbarExpanded(expandToolbarByDefault)
        isToolbarManuallyToggled = ToolbarInputRestartPolicy.manualToggleForStart(
            expandedForEditor = expandToolbarForEditor,
            preserveVisibleState = restarting && hasStartedInput,
            previouslyRequested = previouslyRequested
        )
        hasStartedInput = true
        toolbarHeightSession = ToolbarHeightSession.start(
            toolbarVisible = isToolbarRequested(),
            needsSecondRow = idleUi.buttonsUi.needsSecondRow()
        )
        idleUi.buttonsUi.typoRecoveryButton.apply {
            isEnabled = allowsTextInspection
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.clipboardButton.apply {
            isEnabled = allowsTextInspection
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.quickPhraseButton.apply {
            isEnabled = allowsTextInspection
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.koreanSearchButton.apply {
            isEnabled = allowsTextInspection
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.gifButton.apply {
            isEnabled = allowsNetwork
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.aiAssistantButton.apply {
            isEnabled = allowsAi
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.precisionDictationButton.apply {
            val voiceMode = if (allowsTextInspection) {
                runCatching { VoiceProviderModeStore(context).load() }.getOrNull()
            } else {
                null
            }
            isEnabled = voiceMode != null && VoiceProviderPolicy.allowsSelectedMode(
                mode = voiceMode,
                allowsNetworkInput = allowsNetwork
            )
            alpha = if (isEnabled) 1f else 0.35f
        }
        idleUi.buttonsUi.ocrButton.apply {
            isEnabled = allowsTextInspection
            alpha = if (isEnabled) 1f else 0.35f
        }
        inlineSuggestionGeneration += 1
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val shouldShowVoiceInput =
            showVoiceInputButton && voiceInputSubtype != null && allowsTextInspection
        idleUi.setHideKeyboardIsVoiceInput(
            shouldShowVoiceInput,
            if (shouldShowVoiceInput) switchToVoiceInputCallback else hideKeyboardCallback
        )
        evalIdleUiState()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        lastPreeditEmpty = empty
        if (empty && service.allowsTextInspectionFeatures()) {
            // 유휴 상태(preedit 없음)에서는 문맥 후보 유무와 무관하게 native 후보 유무만으로
            // CandidateEmpty를 판단한다. 문맥 예측이 계속 non-empty를 돌려주더라도 KawaiiBar가
            // Candidate 상태에 고착되지 않게 하기 위함이다.
            barStateMachine.push(PreeditUpdated, PreeditEmpty to empty, CandidateEmpty to lastCandidateListEmpty)
        } else {
            barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
        }
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        val hasNative = data.candidates.isNotEmpty()
        lastCandidateListEmpty = !hasNative
        val hasContextual = service.getContextualSentencePredictions().isNotEmpty() || service.getContextualWordPredictions().isNotEmpty()
        val isEmpty = !hasNative && !hasContextual
        barStateMachine.push(CandidatesUpdated, CandidateEmpty to isEmpty)
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        if (service.allowsTextInspectionFeatures()) {
            if (lastPreeditEmpty) {
                barStateMachine.push(CandidatesUpdated, CandidateEmpty to lastCandidateListEmpty)
            } else {
                val hasContextual = service.getContextualSentencePredictions().isNotEmpty() || service.getContextualWordPredictions().isNotEmpty()
                if (hasContextual) {
                    barStateMachine.push(CandidatesUpdated, CandidateEmpty to false)
                }
            }
        }
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        barStateMachine.push(WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun clearInlineSuggestions() {
        inlineSuggestionGeneration += 1
        isInlineSuggestionPresent = false
        idleUi.inlineSuggestionsBar.clear()
        evalIdleUiState()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            clearInlineSuggestions()
            return true
        }
        val generation = ++inlineSuggestionGeneration
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            val view = pinned?.let { inflateInlineContentView(it) }
            if (generation != inlineSuggestionGeneration || service.isInternalPromptInputOwned) {
                return@launch
            }
            idleUi.inlineSuggestionsBar.setPinnedView(view)
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            if (generation != inlineSuggestionGeneration || service.isInternalPromptInputOwned) {
                return@launch
            }
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCancellableCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = ToolbarLayoutPolicy.TOUCH_TARGET_DP
        const val EXPANDED_HEIGHT =
            ToolbarLayoutPolicy.TOUCH_TARGET_DP * ToolbarLayoutPolicy.EXPANDED_ROWS

        // ViewAnimator child indices for the top-level bar view.
        private const val NORMAL_CHILD_INDEX = 0
        private const val TITLE_CHILD_INDEX = 1

        // Height of the candidate row when HorizontalCandidateComponent renders both the
        // word-level and sentence-level rows (see HorizontalCandidateComponent.isCandidateTwoRow).
        private const val CANDIDATE_TWO_ROW_HEIGHT_DP = 60

        // Debounce delay before collapsing the suggestion row after candidates disappear, so a
        // single empty frame mid-composition doesn't cause a visible collapse/expand flicker.
        private const val CANDIDATE_ROW_COLLAPSE_DELAY_MS = 120L
    }

    var isCandidateTwoRow: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateBarHeight()
            }
        }

    var candidateConnectionHintVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateBarHeight()
            }
        }

    private fun setRowHeight(row: View, heightDp: Int) {
        val heightPx = context.dp(heightDp)
        val params = row.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.height == heightPx) return
        params.height = heightPx
        row.layoutParams = params
    }

    fun updateBarHeight() {
        // Re-evaluate the explicit row state on every surface transition. Overflow itself never
        // changes IME height: the compact toolbar scrolls horizontally until the user expands it.
        toolbarNeedsSecondRow = idleUi.buttonsUi.needsSecondRow()
        toolbarHeightSession = toolbarHeightSession.onToolbarRowsChanged(
            toolbarVisible = isToolbarRequested(),
            expanded = toolbarNeedsSecondRow
        )
        val toolRowHeightDp = toolbarHeightSession.heightDp
        val candidateRowHeightDp = when {
            candidateConnectionHintVisible -> 77
            isCandidateTwoRow -> CANDIDATE_TWO_ROW_HEIGHT_DP
            else -> HEIGHT
        }
        setRowHeight(idleUi.root, toolRowHeightDp)
        setRowHeight(candidateUi.root, candidateRowHeightDp)
        // Normal mode stacks both rows, so the bar height is their sum. When the suggestion row
        // is collapsed (no candidates), it contributes zero height. Title mode keeps its
        // pre-existing height contract (unrelated to candidate row height).
        val targetHeight = if (view.displayedChild == TITLE_CHILD_INDEX) {
            context.dp(toolbarHeightSession.heightDp)
        } else {
            val visibleCandidateRowHeightDp = if (candidateRowVisible) candidateRowHeightDp else 0
            context.dp(toolRowHeightDp + visibleCandidateRowHeightDp)
        }
        val params = view.layoutParams ?: return
        if (params.height == targetHeight) return
        params.height = targetHeight
        view.layoutParams = params
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

    /**
     * Called by HorizontalCandidateComponent whenever the set of candidates it actually renders
     * (word row + sentence row combined) transitions between empty and non-empty. Expanding the
     * suggestion row happens immediately; collapsing it is debounced so a single empty frame
     * mid-composition doesn't cause a visible flicker.
     */
    fun onCandidatesVisibilityChanged(hasCandidates: Boolean) {
        if (hasCandidates) {
            cancelCandidateRowCollapse()
            setCandidateRowVisible(true)
        } else {
            if (candidateRowCollapseRunnable != null || !candidateRowVisible) return
            val runnable = Runnable {
                candidateRowCollapseRunnable = null
                setCandidateRowVisible(false)
            }
            candidateRowCollapseRunnable = runnable
            view.postDelayed(runnable, CANDIDATE_ROW_COLLAPSE_DELAY_MS)
        }
    }

    private fun cancelCandidateRowCollapse() {
        candidateRowCollapseRunnable?.let { view.removeCallbacks(it) }
        candidateRowCollapseRunnable = null
    }

    private fun setCandidateRowVisible(visible: Boolean) {
        if (candidateRowVisible == visible) return
        candidateRowVisible = visible
        candidateUi.root.visibility = if (visible) View.VISIBLE else View.GONE
        updateBarHeight()
    }

}
