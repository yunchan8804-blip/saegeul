/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.Slide
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.voice.VoiceStartMode
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.dimensions.dp

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    // 자판은 처음 쓸 때 만든다. 예전에는 첫 표시 때 모든 자판(영문·두벌식·숫자·모바일 한글 전부)을 한꺼번에
    // 만들어 키보드가 뜨는 순간 메인 스레드를 수백 ms 막았다.
    private val keyboardFactories: Map<String, () -> BaseKeyboard> = buildMap {
        put(TextKeyboard.Name) { TextKeyboard(context, theme) }
        put(HangulKeyboard.Name) { HangulKeyboard(context, theme) }
        put(NumberKeyboard.Name) { NumberKeyboard(context, theme) }
        MobileHangulLayout.entries
            .filterNot { it == MobileHangulLayout.Physical }
            .forEach { layout ->
                put(MobileHangulKeyboard.name(layout)) { MobileHangulKeyboard(context, theme, layout) }
            }
    }
    private val keyboards = HashMap<String, BaseKeyboard>()
    private var currentKeyboardName = ""
    private var activeHangulLayout: String? = null
    // 한글 자판 설정을 한 번이라도 받았는지. 그 뒤에 만들어지는 자판에도 같은 설정을 적용한다.
    private var hangulLayoutKnown = false
    private var mobileHangulLayout by AppPrefs.getInstance().keyboard.mobileHangulLayout
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout
    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    /** Called whenever the attached surface changes in a way that can change [baseRowCount] (K15). */
    var onKeyboardSurfaceChanged: (() -> Unit)? = null

    fun currentBaseRowCount(): Int = currentKeyboard?.baseRowCount ?: 4

    /** Whether the attached surface is a phone-keypad-style mobile Hangul keyboard (K20). */
    fun isMobileHangulLayout(): Boolean = currentKeyboardName.startsWith("MobileHangul:")

    private fun keyboard(name: String): BaseKeyboard? = keyboards[name] ?: keyboardFactories[name]?.invoke()?.also {
        keyboards[name] = it
        if (hangulLayoutKnown) applyHangulLayout(it, activeHangulLayout)
    }

    private fun applyHangulLayout(target: BaseKeyboard, layout: String?) {
        when (target) {
            is TextKeyboard -> target.onHangulKeyboardLayoutUpdate(layout)
            is HangulKeyboard -> target.onHangulKeyboardLayoutUpdate(layout)
            else -> Unit
        }
    }

    private var inputMethodConfigRequest = 0

    private val keyActionListener = KeyActionListener { action, source ->
        when {
            action is KeyAction.LayoutSwitchAction -> switchLayout(action.act)
            action is KeyAction.VoiceInputAction -> when (action.phase) {
                KeyAction.VoiceInputAction.Phase.Tap ->
                    bar.openVoiceFromKeyboard(VoiceStartMode.Tap)
                KeyAction.VoiceInputAction.Phase.HoldStart ->
                    bar.openVoiceFromKeyboard(VoiceStartMode.PushToTalk)
                KeyAction.VoiceInputAction.Phase.HoldEnd -> bar.finishVoiceHold()
            }
            action is KeyAction.SpaceLongPressAction &&
                MobileHangulSurfaceSwitcher.isAvailable(activeHangulLayout) ->
                showMobileHangulLayoutPicker()
            else -> commonKeyActionListener.listener.onKeyAction(action, source)
        }
    }

    private fun showMobileHangulLayoutPicker() {
        val entries = MobileHangulLayout.entries
        val labels = entries.map { context.getString(it.stringRes) }.toTypedArray()
        val effectiveLayout = service.effectiveMobileHangulLayout(mobileHangulLayout)
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.mobile_hangul_layout)
            .setSingleChoiceItems(labels, entries.indexOf(effectiveLayout)) { _, which ->
                val selected = entries.getOrNull(which) ?: return@setSingleChoiceItems
                dialog.dismiss()
                if (!service.updateCurrentAppMobileHangulLayout(selected)) {
                    mobileHangulLayout = selected
                }
                switchLayout(MobileHangulSurfaceSwitcher.target(selected), remember = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = resolveTextLayout(target)
        keyboard(currentKeyboardName)?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            updateThumbSplitProfile()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            updateInputMethod(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    private fun updateInputMethod(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
        val request = ++inputMethodConfigRequest
        if (!HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)) {
            activeHangulLayout = null
            hangulLayoutKnown = true
            (keyboards[TextKeyboard.Name] as? TextKeyboard)?.onHangulKeyboardLayoutUpdate(null)
            if (currentKeyboardName == HangulKeyboard.Name || currentKeyboardName.startsWith("MobileHangul:")) {
                switchLayout(TextKeyboard.Name, false)
            }
            return
        }
        service.lifecycleScope.launch {
            val layout = runCatching {
                fcitx.runOnReady {
                    getImConfig(ime.uniqueName)
                        .findByName("cfg")
                        ?.findByName("Keyboard")
                        ?.value
                }
            }.getOrNull()
            if (request != inputMethodConfigRequest) return@launch
            activeHangulLayout = layout
            hangulLayoutKnown = true
            (keyboards[TextKeyboard.Name] as? TextKeyboard)?.onHangulKeyboardLayoutUpdate(layout)
            (keyboards[HangulKeyboard.Name] as? HangulKeyboard)?.onHangulKeyboardLayoutUpdate(layout)
            val target = resolveHangulLayout(layout)
            if (currentKeyboardName == TextKeyboard.Name ||
                currentKeyboardName == HangulKeyboard.Name ||
                currentKeyboardName.startsWith("MobileHangul:")
            ) {
                switchLayout(target, remember = false)
            }
        }
    }

    private fun resolveHangulLayout(layout: String?): String = when {
        layout in HangulKeyLegends.fullSurfaceLayouts -> HangulKeyboard.Name
        service.effectiveMobileHangulLayout(mobileHangulLayout) != MobileHangulLayout.Physical &&
            (layout == "Dubeolsik" || layout == "0") ->
            MobileHangulKeyboard.name(service.effectiveMobileHangulLayout(mobileHangulLayout))
        else -> TextKeyboard.Name
    }

    private fun resolveTextLayout(target: String): String =
        if (target == TextKeyboard.Name) {
            resolveHangulLayout(activeHangulLayout)
        } else {
            target
        }

    private fun rememberedSymbolTarget(): String {
        val sanitized = KeyboardLayoutMemory.sanitizeStoredTarget(
            lastSymbolType,
            PickerWindow.Key.Symbol.name
        )
        if (sanitized != lastSymbolType) {
            lastSymbolType = sanitized
        }
        return sanitized
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = resolveTextLayout(to.ifEmpty { rememberedSymbolTarget() })
        ContextCompat.getMainExecutor(service).execute {
            if (keyboardFactories.containsKey(target)) {
                if (remember && KeyboardLayoutMemory.shouldRememberAsSymbolLayout(target)) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
                onKeyboardSurfaceChanged?.invoke()
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    fun updateThumbSplitProfile() {
        val profile = FoldKeyboardProfileResolver.resolve(
            KeyboardViewportReader.read(context),
            ThumbSplitPreferences(
                compactEnabled = keyboardPrefs.splitKeyboardCompact.getValue(),
                expandedEnabled = keyboardPrefs.splitKeyboardExpanded.getValue(),
                compactPortraitGapDp = keyboardPrefs.splitKeyboardCompactGapPortrait.getValue(),
                compactLandscapeGapDp = keyboardPrefs.splitKeyboardCompactGapLandscape.getValue(),
                expandedPortraitGapDp = keyboardPrefs.splitKeyboardExpandedGapPortrait.getValue(),
                expandedLandscapeGapDp = keyboardPrefs.splitKeyboardExpandedGapLandscape.getValue()
            )
        )
        val splitCurrentSurface = profile.enabled &&
            currentKeyboardName != NumberKeyboard.Name &&
            !currentKeyboardName.startsWith("MobileHangul:")
        currentKeyboard?.updateThumbSplit(
            enabled = splitCurrentSurface,
            centerGapPx = if (splitCurrentSurface) context.dp(profile.centerGapDp) else 0
        )
    }

    override fun onStartInput(
        info: EditorInfo,
        capFlags: CapabilityFlags,
        restarting: Boolean
    ) {
        // Re-resolve for every editor session so a Fold posture or multi-window viewport change is
        // reflected without requiring the user to toggle the preference.
        updateThumbSplitProfile()
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> resolveTextLayout(TextKeyboard.Name)
        }
        switchLayout(targetLayout, remember = false)
        updateInputMethod(fcitx.runImmediately { inputMethodEntryCached })
        currentKeyboard?.onStartInput()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        updateInputMethod(ime)
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        currentKeyboard?.onSelectionUpdate(start, end)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        currentKeyboard?.onPreeditEmptyStateUpdate(empty)
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        currentKeyboard?.onClientPreeditUpdate(data)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
        onKeyboardSurfaceChanged?.invoke()
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}
