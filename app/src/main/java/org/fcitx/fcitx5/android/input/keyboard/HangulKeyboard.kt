/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.imageResource

class HangulPositionKey(
    val character: Char,
    percentWidth: Float
) : KeyDef(
    Appearance.Text(character.toString(), textSize = 20f, percentWidth = percentWidth),
    setOf(Behavior.Press(KeyAction.FcitxKeyAction(character.toString()))),
    arrayOf(Popup.Preview(character.toString()))
)

/** Full physical-key surface required by three-set and Ahnmatae layouts. */
@SuppressLint("ViewConstructor")
class HangulKeyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, Layout) {

    enum class ShiftState { None, Once, Lock }

    companion object {
        const val Name = "Hangul"

        private fun row(keys: String, width: Float) =
            keys.map { HangulPositionKey(it, width) }

        val Layout: List<List<KeyDef>> = listOf(
            row("`1234567890-=", 1f / 13f),
            row("qwertyuiop[]", 1f / 12f),
            row("asdfghjkl;'\\", 0.07f) + BackspaceKey(percentWidth = 0.16f),
            listOf(CapsKey()) + row("zxcvbnm,./", 0.085f),
            listOf(
                LayoutSwitchKey("?123", "", percentWidth = 0.15f),
                LanguageKey(),
                SpaceKey(),
                ReturnKey()
            )
        )

        private val positionByAppearance = Layout.flatten()
            .filterIsInstance<HangulPositionKey>()
            .associateBy { it.appearance }
    }

    private val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    private val positionKeys by lazy {
        allViews.filterIsInstance<TextKeyView>()
            .filter { it.def in positionByAppearance }
            .toList()
    }

    private var layoutName: String? = null
    private var shiftState = ShiftState.None
    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Suppress("unused")
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        lang.visibility = if (value) View.VISIBLE else View.GONE
    }

    init {
        lang.visibility = if (showLangSwitchKey.getValue()) View.VISIBLE else View.GONE
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        when (action) {
            is KeyAction.CapsAction -> switchShift(action.lock)
            is KeyAction.FcitxKeyAction -> {
                val base = action.act.singleOrNull()
                if (base == null) {
                    super.onAction(action, source)
                    return
                }
                val shifted = shiftState != ShiftState.None
                val character = HangulKeyLegends.actionCharacter(base, shifted)
                val states = when (shiftState) {
                    ShiftState.None -> KeyStates.Virtual
                    ShiftState.Once -> KeyStates(KeyState.Virtual, KeyState.Shift)
                    ShiftState.Lock -> KeyStates(KeyState.Virtual, KeyState.CapsLock)
                }
                super.onAction(action.copy(act = character.toString(), states = states), source)
                if (shiftState == ShiftState.Once) switchShift()
            }
            else -> super.onAction(action, source)
        }
    }

    override fun onAttach() {
        shiftState = ShiftState.None
        updateShiftIcon()
        updateLegends()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space.mainText.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }
                ?.let { append(" ($it)") }
        }
    }

    fun onHangulKeyboardLayoutUpdate(layout: String?) {
        layoutName = layout
        updateLegends()
    }

    private fun switchShift(lock: Boolean = false) {
        shiftState = if (lock) {
            if (shiftState == ShiftState.Lock) ShiftState.None else ShiftState.Lock
        } else {
            if (shiftState == ShiftState.None) ShiftState.Once else ShiftState.None
        }
        updateShiftIcon()
        updateLegends()
    }

    private fun updateShiftIcon() {
        caps.img.imageResource = when (shiftState) {
            ShiftState.None -> R.drawable.ic_capslock_none
            ShiftState.Once -> R.drawable.ic_capslock_once
            ShiftState.Lock -> R.drawable.ic_capslock_lock
        }
    }

    private fun updateLegends() {
        val shifted = shiftState != ShiftState.None
        positionKeys.forEach { view ->
            val key = positionByAppearance.getValue(view.def).character
            view.mainText.text = HangulKeyLegends.legend(key.toString(), shifted, layoutName)
                ?: HangulKeyLegends.actionCharacter(key, shifted).toString()
        }
    }
}
