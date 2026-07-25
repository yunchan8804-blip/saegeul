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
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

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

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf<String, BaseKeyboard>(
            TextKeyboard.Name to TextKeyboard(context, theme),
            HangulKeyboard.Name to HangulKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        ).apply {
            MobileHangulLayout.entries
                .filterNot { it == MobileHangulLayout.Physical }
                .forEach { layout ->
                    put(MobileHangulKeyboard.name(layout), MobileHangulKeyboard(context, theme, layout))
                }
        }
    }
    private var currentKeyboardName = ""
    private var activeHangulLayout: String? = null
    private var mobileHangulLayout by AppPrefs.getInstance().keyboard.mobileHangulLayout
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private var inputMethodConfigRequest = 0

    private val keyActionListener = KeyActionListener { action, source ->
        when {
            action is KeyAction.LayoutSwitchAction -> switchLayout(action.act)
            action is KeyAction.SpaceLongPressAction &&
                MobileHangulSurfaceSwitcher.isAvailable(activeHangulLayout) ->
                showMobileHangulLayoutPicker()
            else -> commonKeyActionListener.listener.onKeyAction(action, source)
        }
    }

    private fun showMobileHangulLayoutPicker() {
        val entries = MobileHangulLayout.entries
        val labels = entries.map { context.getString(it.stringRes) }.toTypedArray()
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.mobile_hangul_layout)
            .setSingleChoiceItems(labels, entries.indexOf(mobileHangulLayout)) { _, which ->
                val selected = entries.getOrNull(which) ?: return@setSingleChoiceItems
                dialog.dismiss()
                mobileHangulLayout = selected
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
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            updateInputMethod(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    private fun updateInputMethod(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
        val request = ++inputMethodConfigRequest
        val textKeyboard = keyboards[TextKeyboard.Name] as TextKeyboard
        if (!HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)) {
            activeHangulLayout = null
            textKeyboard.onHangulKeyboardLayoutUpdate(null)
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
            textKeyboard.onHangulKeyboardLayoutUpdate(layout)
            (keyboards[HangulKeyboard.Name] as HangulKeyboard)
                .onHangulKeyboardLayoutUpdate(layout)
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
        mobileHangulLayout != MobileHangulLayout.Physical &&
            (layout == "Dubeolsik" || layout == "0") ->
            MobileHangulKeyboard.name(mobileHangulLayout)
        else -> TextKeyboard.Name
    }

    private fun resolveTextLayout(target: String): String =
        if (target == TextKeyboard.Name) {
            resolveHangulLayout(activeHangulLayout)
        } else {
            target
        }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = resolveTextLayout(to.ifEmpty { lastSymbolType })
        ContextCompat.getMainExecutor(service).execute {
            if (keyboards.containsKey(target)) {
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> resolveTextLayout(TextKeyboard.Name)
        }
        switchLayout(targetLayout, remember = false)
        updateInputMethod(fcitx.runImmediately { inputMethodEntryCached })
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        updateInputMethod(ime)
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
