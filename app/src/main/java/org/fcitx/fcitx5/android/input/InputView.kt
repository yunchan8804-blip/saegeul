/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.policy.ImeTouchableTopPolicy
import org.fcitx.fcitx5.android.input.prompt.InternalPromptFinishResult
import org.fcitx.fcitx5.android.input.prompt.InternalPromptInputBar
import org.fcitx.fcitx5.android.input.prompt.InternalPromptSpec
import org.fcitx.fcitx5.android.input.prompt.InternalPromptSpecs
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionIndicator
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
import org.fcitx.fcitx5.android.input.keyboard.FoldKeyboardProfileResolver
import org.fcitx.fcitx5.android.input.keyboard.KeyboardViewportReader
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.PinnedNumberRow
import org.fcitx.fcitx5.android.input.keyboard.ThumbSplitPreferences
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

/** Upper bound of the keyboard height preference; scaling must not exceed what users can set. */
private const val MAX_HEIGHT_PERCENT = 90

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

    // Shown in the empty side of a one-hand keyboard, over left/rightPaddingSpace.
    private val oneHandSwitchSideButton =
        ToolButton(themedContext, R.drawable.ic_baseline_swap_horiz_24, theme).apply {
            contentDescription = context.getString(R.string.one_hand_mode_switch_side)
            setOnClickListener {
                oneHandMode.setValue(
                    when (oneHandMode.getValue()) {
                        OneHandMode.Left -> OneHandMode.Right
                        OneHandMode.Right -> OneHandMode.Left
                        OneHandMode.Off -> OneHandMode.Off
                    }
                )
            }
        }
    private val oneHandOffButton =
        ToolButton(themedContext, R.drawable.ic_baseline_close_24, theme).apply {
            contentDescription = context.getString(R.string.one_hand_mode_turn_off)
            setOnClickListener { oneHandMode.setValue(OneHandMode.Off) }
        }
    private val oneHandControls = LinearLayout(themedContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = dp(48)
        addView(oneHandSwitchSideButton, LinearLayout.LayoutParams(size, size))
        addView(
            oneHandOffButton,
            LinearLayout.LayoutParams(size, size).apply { topMargin = dp(8) }
        )
        visibility = GONE
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
    private val promptInputBar = InternalPromptInputBar(themedContext, theme)
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    fun showDynamicPhrasePreview(template: String, editor: DynamicPhraseEditorTarget) {
        windowManager.attachWindow(DynamicPhraseWindow(template, editor))
    }

    fun refreshContextualCandidates() {
        horizontalCandidate.refreshContextualCandidatesIfNeeded()
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
    private val oneHandMode = keyboardPrefs.oneHandMode

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        oneHandMode,
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
            // A pinned number row adds one more row on top of the active surface's own (K15: not
            // every surface starts at four, e.g. Moakey is five), so grow the keyboard with it to
            // keep the existing keys the same size instead of squeezing them.
            val effectivePercent = PinnedNumberRow.scaleHeightPercent(
                percent,
                MAX_HEIGHT_PERCENT,
                baseRows = keyboardWindow.currentBaseRowCount()
            )
            val metrics = resources.displayMetrics
            return KeyboardHeightFloor.apply(
                percentHeightPx = metrics.heightPixels * effectivePercent / 100,
                density = metrics.density,
                isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                rows = KeyboardHeightFloor.rowCount(
                    PinnedNumberRow.isEnabled(),
                    keyboardWindow.currentBaseRowCount()
                )
            )
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
    private var promptOnSubmit: ((String) -> Unit)? = null
    private var promptOnCancel: (() -> Unit)? = null
    private var promptCaptureToken: Long? = null
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
            // split-active width also decides whether one-hand mode is honored
            updateKeyboardSize()
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
            add(oneHandControls, lParams {
                below(kawaiiBar.view)
                startOfParent()
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
        // A surface switch can change baseRowCount (e.g. Text <-> Moakey), which changes how much
        // room a pinned number row needs (K15). switchLayout/onAttached call this back.
        keyboardWindow.onKeyboardSurfaceChanged = { updateKeyboardSize() }

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(promptInputBar)
            centerHorizontally()
        })
        add(promptInputBar, lParams(matchParent, promptInputBar.preferredHeightPx) {
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

        promptInputBar.onCancel = { finishInternalPromptInput(submit = false) }
        promptInputBar.onSubmit = { finishInternalPromptInput(submit = true) }
    }

    private fun isThumbSplitActive(): Boolean = FoldKeyboardProfileResolver.resolve(
        KeyboardViewportReader.read(context),
        ThumbSplitPreferences(
            compactEnabled = keyboardPrefs.splitKeyboardCompact.getValue(),
            expandedEnabled = keyboardPrefs.splitKeyboardExpanded.getValue(),
            compactPortraitGapDp = keyboardPrefs.splitKeyboardCompactGapPortrait.getValue(),
            compactLandscapeGapDp = keyboardPrefs.splitKeyboardCompactGapLandscape.getValue(),
            expandedPortraitGapDp = keyboardPrefs.splitKeyboardExpandedGapPortrait.getValue(),
            expandedLandscapeGapDp = keyboardPrefs.splitKeyboardExpandedGapLandscape.getValue()
        )
    ).enabled

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = inputContentHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val splitActive = isThumbSplitActive()
        val mode = oneHandMode.getValue()
        val insets = KeyboardFrame.compute(
            windowWidthPx = resources.displayMetrics.widthPixels,
            density = resources.displayMetrics.density,
            isLandscape = landscape,
            userSidePaddingPx = keyboardSidePaddingPx,
            oneHandMode = mode,
            isSplitActive = splitActive,
            isMobileHangulLayout = keyboardWindow.isMobileHangulLayout()
        )
        applyFrameInsets(insets)
        updateOneHandControls(if (splitActive) OneHandMode.Off else mode, insets)
    }

    private fun applyFrameInsets(insets: KeyboardFrame.Insets) {
        // hide side padding space views when unnecessary
        leftPaddingSpace.visibility = if (insets.startPx == 0) GONE else VISIBLE
        rightPaddingSpace.visibility = if (insets.endPx == 0) GONE else VISIBLE
        leftPaddingSpace.updateLayoutParams {
            width = insets.startPx
        }
        rightPaddingSpace.updateLayoutParams {
            width = insets.endPx
        }
        windowManager.view.updateLayoutParams<LayoutParams> {
            startToStart = unset
            endToEnd = unset
            startToEnd = unset
            endToStart = unset
            if (insets.startPx == 0) startOfParent() else startToEndOf(leftPaddingSpace)
            if (insets.endPx == 0) endOfParent() else endToStartOf(rightPaddingSpace)
        }
        preedit.ui.root.setPadding(insets.startPx, 0, insets.endPx, 0)
        kawaiiBar.view.setPadding(insets.startPx, 0, insets.endPx, 0)
    }

    private fun updateOneHandControls(mode: OneHandMode, insets: KeyboardFrame.Insets) {
        when (mode) {
            OneHandMode.Off -> oneHandControls.visibility = GONE
            OneHandMode.Right -> {
                // keyboard docked right, empty space (and controls) on the left
                oneHandControls.visibility = VISIBLE
                oneHandControls.updateLayoutParams<LayoutParams> {
                    width = insets.startPx
                    startToEnd = unset
                    endToStart = unset
                    endToEnd = unset
                    startOfParent()
                }
            }
            OneHandMode.Left -> {
                // keyboard docked left, empty space (and controls) on the right
                oneHandControls.visibility = VISIBLE
                oneHandControls.updateLayoutParams<LayoutParams> {
                    width = insets.endPx
                    startToEnd = unset
                    startToStart = unset
                    endToStart = unset
                    endOfParent()
                }
            }
        }
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
        discardInternalPromptInput()
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
        if (promptInputBar.visibility != VISIBLE) return
        promptInputBar.getLocationInWindow(promptInputBarLocation)
        outLocation[1] = ImeTouchableTopPolicy.resolve(
            keyboardTop = outLocation[1],
            promptTop = promptInputBarLocation[1],
            promptVisible = true
        )
    }

    /** Opens the canonical keyboard for a GIF query without letting it touch the target editor. */
    fun beginGifSearchPromptInput(
        initialText: String,
        maxCharacters: Int,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit
    ): Boolean = beginInternalPromptInput(
        spec = InternalPromptSpecs.gifSearch(maxCharacters),
        initialText = initialText,
        onSubmit = onSubmit,
        onCancel = onCancel
    )

    private fun beginInternalPromptInput(
        spec: InternalPromptSpec,
        initialText: String,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        promptOnSubmit = onSubmit
        promptOnCancel = onCancel
        promptCaptureToken = null
        promptInputBar.configure(spec)
        promptInputBar.updateLayoutParams<LayoutParams> {
            height = promptInputBar.preferredHeightPx
        }
        promptInputBar.setSubmitPending(false)
        val token = service.beginInternalPromptCapture(
            spec = spec,
            initialText = initialText,
            onStarted = { startedToken ->
                promptInputBar.post {
                    if (promptCaptureToken != startedToken ||
                        !service.isInternalPromptCaptureActive(startedToken)
                    ) {
                        return@post
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        kawaiiBar.clearInlineSuggestions()
                    }
                    setAssistantContentExpanded(false)
                    promptInputBar.visibility = VISIBLE
                    windowManager.attachWindow(KeyboardWindow)
                    requestLayout()
                }
            },
            onChanged = { changedToken, committed, preeditText ->
                promptInputBar.post {
                    if (promptCaptureToken != changedToken ||
                        !service.isInternalPromptCaptureActive(changedToken)
                    ) return@post
                    promptInputBar.render(committed, preeditText)
                }
            }
        )
        if (token == null) {
            promptOnSubmit = null
            promptOnCancel = null
            return false
        }
        promptCaptureToken = token
        return true
    }

    fun submitInternalPromptInput() {
        finishInternalPromptInput(submit = true)
    }

    private fun finishInternalPromptInput(submit: Boolean) {
        if (submit) {
            when (service.finishInternalPromptCapture()) {
                InternalPromptFinishResult.Pending -> {
                    promptInputBar.setSubmitPending(true)
                    requestLayout()
                }
                InternalPromptFinishResult.Rejected -> promptInputBar.setSubmitPending(false)
            }
        } else {
            service.cancelInternalPromptCapture()
            finishInternalPromptInput(null)
        }
    }

    /** Completes a prompt only after its service-side text ordering contract has settled. */
    private fun finishInternalPromptInput(text: String?) {
        promptInputBar.visibility = GONE
        promptInputBar.setSubmitPending(false)
        promptCaptureToken = null
        val submitted = promptOnSubmit
        val cancelled = promptOnCancel
        promptOnSubmit = null
        promptOnCancel = null
        requestLayout()
        if (text != null) submitted?.invoke(text) else cancelled?.invoke()
    }

    /** Delivers a fenced submission only to the InputView that still owns its capture token. */
    fun completeInternalPromptSubmission(token: Long, text: String) {
        if (promptCaptureToken != token) return
        finishInternalPromptInput(text)
    }

    /** Restores the Run/Search button when a submit or direct-insert worker cannot complete. */
    fun restoreInternalPromptSubmission(token: Long) {
        if (promptCaptureToken != token) return
        promptInputBar.setSubmitPending(false)
        requestLayout()
    }

    /** Fcitx restarted, so this prompt can no longer receive its ordered completion marker. */
    fun abortInternalPromptInput() {
        if (promptCaptureToken != null) finishInternalPromptInput(null)
    }

    private fun discardInternalPromptInput() {
        service.cancelInternalPromptCapture()
        promptInputBar.visibility = GONE
        promptInputBar.setSubmitPending(false)
        promptCaptureToken = null
        promptOnSubmit = null
        promptOnCancel = null
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
        // Android may reuse identical EditorInfo metadata for another field. An internal prompt
        // fails closed across every new input session instead of risking a later tool action on
        // the wrong editor.
        if (!service.shouldRetainInternalPromptCapture(info)) discardInternalPromptInput()
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

    fun updateAutomaticSuggestionIndicator(indicator: OnDeviceAutomaticSuggestionIndicator) {
        kawaiiBar.updateAutomaticSuggestionIndicator(indicator)
    }

    fun postRefreshContextualCandidates(delayMs: Long = 16L) {
        horizontalCandidate.postRefreshContextualCandidates(delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        if (service.isInternalPromptInputOwned) {
            kawaiiBar.clearInlineSuggestions()
            return true
        }
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        discardInternalPromptInput()
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
