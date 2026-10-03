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
import org.fcitx.fcitx5.android.input.popup.PopupAction
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
class HangulKeyboard private constructor(
    context: Context,
    theme: Theme,
    private val rows: List<List<KeyDef>>
) : BaseKeyboard(context, theme, rows) {

    constructor(context: Context, theme: Theme) : this(
        context,
        theme,
        layoutFor(AppPrefs.getInstance().keyboard.showVoiceInputButton.getValue())
    )

    enum class ShiftState { None, Once, Lock }

    companion object {
        const val Name = "Hangul"

        private fun row(keys: String, width: Float) =
            keys.map { HangulPositionKey(it, width) }

        // The position keys are shared by every layout: their views are matched to them by
        // appearance when the legends are refreshed.
        private val PositionRows: List<List<KeyDef>> = listOf(
            row("`1234567890-=", 1f / 13f),
            row("qwertyuiop[]", 1f / 12f),
            row("asdfghjkl;'\\", 0.07f) + BackspaceKey(percentWidth = 0.16f),
            listOf(CapsKey()) + row("zxcvbnm,./", 0.085f)
        )

        private fun bottomRow(showMicKey: Boolean): List<KeyDef> = buildList {
            if (showMicKey) add(MicKey())
            add(LayoutSwitchKey("?123", "", percentWidth = 0.15f))
            add(LanguageKey())
            add(SpaceKey())
            add(ReturnKey())
        }

        fun layoutFor(showMicKey: Boolean): List<List<KeyDef>> =
            PositionRows + listOf(bottomRow(showMicKey))

        /** With the microphone key the bottom row splits before the space: mic, `?123` and language on the left. */
        internal fun bottomRowSplitBoundary(bottomRow: List<KeyDef>): Int? =
            bottomRow.takeIf { row -> row.any { it is MicKey } }?.indexOfFirst { it is SpaceKey }

        private val positionByAppearance = PositionRows.flatten()
            .filterIsInstance<HangulPositionKey>()
            .associateBy { it.appearance }
    }

    // Full physical-key surface, never pinned; its layout is already five rows on its own.
    override val baseRowCount: Int = rows.size

    override fun thumbSplitBoundaryIndex(rowIndex: Int, keyCount: Int): Int? =
        if (rowIndex == rows.lastIndex) {
            bottomRowSplitBoundary(rows.last())?.takeIf { it in 1 until keyCount }
        } else {
            null
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

    /** Shows the key's actual Hangul jamo instead of its Latin position letter. See K8. */
    override fun onPopupAction(action: PopupAction) {
        val transformed = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPreview(action.content))
            else -> action
        }
        super.onPopupAction(transformed)
    }

    private fun transformPreview(c: String): String {
        if (c.length != 1) return c
        val shifted = shiftState == ShiftState.Once
        return HangulKeyLegends.legend(c, shifted, layoutName)
            ?: HangulKeyLegends.actionCharacter(c.single(), shifted).toString()
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
        // The Shift jamo legend is only ever shown for a one-time Shift (K7); a locked Caps
        // still types the shifted jamo, but the persistent key labels stay unshifted.
        val shifted = shiftState == ShiftState.Once
        positionKeys.forEach { view ->
            val key = positionByAppearance.getValue(view.def).character
            view.mainText.text = HangulKeyLegends.legend(key.toString(), shifted, layoutName)
                ?: HangulKeyLegends.actionCharacter(key, shifted).toString()
        }
    }
}
