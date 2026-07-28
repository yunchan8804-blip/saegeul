/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.ai.AiPromptInputBar
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dynamicphrase.DynamicPhraseEditorTarget
import org.fcitx.fcitx5.android.input.dynamicphrase.DynamicPhraseWindow
import org.fcitx.fcitx5.android.input.dynamicphrase.SensitivePhraseAuthCoordinator
import org.fcitx.fcitx5.android.input.dynamicphrase.SensitivePhraseWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.ocr.OcrDocumentCoordinator
import org.fcitx.fcitx5.android.input.ocr.OcrWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.voice.MeetingTranscriptionWindow
import org.fcitx.fcitx5.android.input.voice.VoiceAudioDocumentCoordinator
import org.fcitx.fcitx5.android.input.voice.VoicePermissionCoordinator
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.max

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val aiPromptInputBar = AiPromptInputBar(themedContext, theme)
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    fun showDynamicPhrasePreview(template: String, editor: DynamicPhraseEditorTarget) {
        windowManager.attachWindow(DynamicPhraseWindow(template, editor))
    }

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val thumbSplitPrefs = listOf(
        keyboardPrefs.splitKeyboardCompact,
        keyboardPrefs.splitKeyboardExpanded,
        keyboardPrefs.splitKeyboardCompactGapPortrait,
        keyboardPrefs.splitKeyboardCompactGapLandscape,
        keyboardPrefs.splitKeyboardExpandedGapPortrait,
        keyboardPrefs.splitKeyboardExpandedGapLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    private var assistantContentExpanded = false
    private var aiPromptOnSubmit: ((String) -> Unit)? = null
    private var aiPromptOnCancel: (() -> Unit)? = null
    private val promptInputBarLocation = IntArray(2)

    private val inputContentHeightPx: Int
        get() = if (assistantContentExpanded) {
            max(keyboardHeightPx, resources.displayMetrics.heightPixels * 48 / 100)
        } else {
            keyboardHeightPx
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
        if (thumbSplitPrefs.any { it.key == key }) {
            keyboardWindow.updateThumbSplitProfile()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(aiPromptInputBar)
            centerHorizontally()
        })
        add(aiPromptInputBar, lParams(matchParent, dp(56)) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)

        aiPromptInputBar.onCancel = { finishAiPromptInput(submit = false) }
        aiPromptInputBar.onSubmit = { finishAiPromptInput(submit = true) }
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = inputContentHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    /** Gives result-heavy assistant surfaces more room, then restores the user's keyboard size. */
    fun setAssistantContentExpanded(expanded: Boolean) {
        if (assistantContentExpanded == expanded) return
        assistantContentExpanded = expanded
        windowManager.view.updateLayoutParams {
            height = inputContentHeightPx
        }
        requestLayout()
    }

    /** Clears transient IME surfaces before launching an IME-owned settings activity. */
    fun prepareForSettingsActivity() {
        discardAiPromptInput()
        setAssistantContentExpanded(false)
        windowManager.attachWindow(KeyboardWindow)
        requestLayout()
    }

    /**
     * Returns the upper edge of every interactive IME-owned surface. The prompt strip sits above
     * [keyboardView], so reporting only the keyboard edge lets touches on Run/Cancel fall through
     * to the editor app underneath.
     */
    fun getTouchableTopLocationInWindow(outLocation: IntArray) {
        keyboardView.getLocationInWindow(outLocation)
        if (aiPromptInputBar.visibility != VISIBLE) return
        aiPromptInputBar.getLocationInWindow(promptInputBarLocation)
        outLocation[1] = ImeTouchableTopPolicy.resolve(
            keyboardTop = outLocation[1],
            promptTop = promptInputBarLocation[1],
            promptVisible = true
        )
    }

    /**
     * Reattaches the one canonical keyboard surface and redirects its engine output into an
     * IME-owned prompt buffer. The target editor is never used as scratch space.
     */
    fun beginAiPromptInput(
        initialText: String,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        aiPromptOnSubmit = onSubmit
        aiPromptOnCancel = onCancel
        val started = service.beginAiPromptCapture(initialText) { committed, preeditText ->
            aiPromptInputBar.post {
                aiPromptInputBar.render(committed, preeditText)
            }
        }
        if (!started) {
            aiPromptOnSubmit = null
            aiPromptOnCancel = null
            return false
        }
        setAssistantContentExpanded(false)
        aiPromptInputBar.visibility = VISIBLE
        windowManager.attachWindow(KeyboardWindow)
        requestLayout()
        return true
    }

    fun submitAiPromptInput() {
        finishAiPromptInput(submit = true)
    }

    private fun finishAiPromptInput(submit: Boolean) {
        val text = if (submit) service.finishAiPromptCapture() else {
            service.cancelAiPromptCapture()
            null
        }
        if (submit && text.isNullOrBlank()) return
        aiPromptInputBar.visibility = GONE
        val submitted = aiPromptOnSubmit
        val cancelled = aiPromptOnCancel
        aiPromptOnSubmit = null
        aiPromptOnCancel = null
        requestLayout()
        if (text != null) submitted?.invoke(text) else cancelled?.invoke()
    }

    private fun discardAiPromptInput() {
        service.cancelAiPromptCapture()
        aiPromptInputBar.visibility = GONE
        aiPromptOnSubmit = null
        aiPromptOnCancel = null
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        // Dialogs such as the Hangul surface picker can restart the same editor. Keep the
        // internal target in that case, but fail closed as soon as editor identity changes.
        if (!service.shouldRetainAiPromptCapture(info)) discardAiPromptInput()
        broadcaster.onStartInput(info, capFlags, restarting)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
        val sensitivePhraseResume = SensitivePhraseAuthCoordinator.consumeForEditor(
            info.packageName,
            info.fieldId,
            info.inputType
        )
        if (sensitivePhraseResume != null) {
            windowManager.attachWindow(SensitivePhraseWindow(
                sensitivePhraseResume.target,
                sensitivePhraseResume
            ))
            return
        }
        val ocrResume = OcrDocumentCoordinator.consumeForEditor(
            info.packageName,
            info.fieldId,
            info.inputType
        )
        if (ocrResume != null) {
            windowManager.attachWindow(OcrWindow(ocrResume))
            return
        }
        val audioResume = VoiceAudioDocumentCoordinator.consumeForEditor(
            info.packageName,
            info.fieldId,
            info.inputType
        )
        if (audioResume != null) {
            windowManager.attachWindow(MeetingTranscriptionWindow(audioResume))
            return
        }
        VoicePermissionCoordinator.consumeForEditor(
            info.packageName,
            info.fieldId,
            info.inputType
        )?.let { voiceResume ->
            windowManager.attachWindow(VoiceTranscriptionWindow(voiceResume))
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                handleInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    private fun handleInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        val decorated = service.decorateBufferedHangulPreedit(data)
        preeditEmptyState.updatePreeditEmptyState(preedit = decorated.preedit)
        broadcaster.onInputPanelUpdate(decorated)
    }

    fun refreshBufferedHangulPreedit() {
        handleInputPanelUpdate(fcitx.runImmediately { inputPanelCached })
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        discardAiPromptInput()
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
