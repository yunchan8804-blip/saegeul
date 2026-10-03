/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.AlphabetPopupLegends
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, layout()) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        /** Swipe/long-press symbols of the top letter row when no number row is pinned. */
        private const val TopRowDigits = "1234567890"

        /**
         * With a pinned number row the digits here would be a duplicate of the row right above,
         * so the swipe layer earns its keep with symbols no other row offers.
         */
        private const val TopRowSymbols = "%^&_[]{}<>"

        private fun topRow(numberRowPinned: Boolean): List<KeyDef> {
            val alt = if (numberRowPinned) TopRowSymbols else TopRowDigits
            return "QWERTYUIOP".mapIndexed { i, c ->
                AlphabetKey(c.toString(), alt[i].toString())
            }
        }

        /**
         * With the mic key, left of `?123` so it is not pressed by mistake, the comma key is gone:
         * the period key also types the comma, and the emoji, quick phrase and Unicode menu moves
         * from the comma key to `?123`. Without the mic key the row is the original one.
         */
        internal fun bottomRow(showMicKey: Boolean): List<KeyDef> =
            if (showMicKey) {
                listOf(
                    MicKey(),
                    LayoutSwitchKey("?123", "", popup = arrayOf(editorToolsMenu())),
                    LanguageKey(),
                    SpaceKey(),
                    PeriodCommaKey(0.1f, variant = KeyDef.Appearance.Variant.Alternative),
                    ReturnKey()
                )
            } else {
                listOf(
                    LayoutSwitchKey("?123", ""),
                    CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                    LanguageKey(),
                    SpaceKey(),
                    SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                    ReturnKey()
                )
            }

        private val LowerRows: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            )
        )

        private fun layout(): List<List<KeyDef>> {
            val pinned = PinnedNumberRow.isEnabled()
            val bottom = bottomRow(AppPrefs.getInstance().keyboard.showVoiceInputButton.getValue())
            return PinnedNumberRow.prependTo(listOf(topRow(pinned)) + LowerRows + listOf(bottom), pinned)
        }
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey
    private var inputMethodLabel = ""

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    private var hangulInputMethodActive = false
    private var hangulKeyboardLayout: String? = null

    private val numberRowOffset = if (PinnedNumberRow.isEnabled()) 1 else 0

    override fun thumbSplitBoundaryIndex(rowIndex: Int, keyCount: Int): Int? =
        TextKeyboardSplitPolicy.boundaryIndex(rowIndex, keyCount, numberRowOffset)

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private fun transformAlphabetLegend(c: String): String =
        if (hangulInputMethodActive) {
            HangulKeyLegends.legend(c, capsState == CapsState.Once, hangulKeyboardLayout)
                ?: transformAlphabet(c)
        } else {
            transformAlphabet(c)
        }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        hangulInputMethodActive =
            HangulKeyLegends.isHangulInputMethod(ime.addon, ime.languageCode)
        hangulKeyboardLayout = null
        inputMethodLabel = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        updateSpaceLabel()
        if (capsState != CapsState.None) {
            switchCapsState()
        } else {
            updateAlphabetKeys()
        }
    }

    fun onHangulKeyboardLayoutUpdate(layout: String?) {
        hangulKeyboardLayout = layout
        updateSpaceLabel()
        updateAlphabetKeys()
    }

    private fun updateSpaceLabel() {
        val pickerAvailable = hangulInputMethodActive &&
            MobileHangulSurfaceSwitcher.isAvailable(hangulKeyboardLayout)
        space.mainText.text = if (pickerAvailable) {
            context.getString(R.string.mobile_hangul_switch_label, inputMethodLabel)
        } else {
            inputMethodLabel
        }
        space.contentDescription = if (pickerAvailable) {
            context.getString(R.string.mobile_hangul_switch_hint, inputMethodLabel)
        } else {
            inputMethodLabel
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabetLegend(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> resolveShowKeyboardAction(action)
            else -> action
        }
        super.onPopupAction(newAction)
    }

    /**
     * The alphabet key long-press popup's first entry must always match the alt legend printed
     * under the key, and in Hangul input its second entry (when any) is the key's Shift jamo,
     * sent as the Latin letter fcitx5-hangul already composes that jamo from. See
     * [AlphabetPopupLegends].
     *
     * [KeyDef.Popup.Keyboard.Preset.label] is the key's constant, always upper-case letter — not
     * the case the key is currently displaying — so when there is no override the label is still
     * replaced with the caps-cased letter, exactly as this used to work before the popup could
     * carry its own keys/labels: otherwise the default preset lookup would show the wrong case's
     * accented letters (e.g. Shift-less "e" long-press showing the "E" set's accents).
     */
    private fun resolveShowKeyboardAction(
        action: PopupAction.ShowKeyboardAction
    ): PopupAction.ShowKeyboardAction {
        val keyboard = action.keyboard
        if (keyboard !is KeyDef.Popup.Keyboard.Preset) return action
        val label = keyboard.label
        if (label.length != 1 || !label[0].isLetter()) return action
        val casedChar = transformAlphabet(label)[0]
        val entries = AlphabetPopupLegends.resolve(
            rawChar = label[0],
            casedChar = casedChar,
            altLegend = alphabetAltLegend(label),
            hangulActive = hangulInputMethodActive,
            hangulLayout = hangulKeyboardLayout
        )
        return if (entries != null) {
            action.copy(keysOverride = entries.keys, labelsOverride = entries.labels)
        } else {
            action.copy(keyboard = keyboard.copy(label = casedChar.toString()))
        }
    }

    /** The alt legend (swipe/long-press hint) currently printed under the alphabet key [label]. */
    private fun alphabetAltLegend(label: String): String? =
        textKeys.asSequence()
            .mapNotNull { it.def as? KeyDef.Appearance.AltText }
            .firstOrNull { it.displayText == label }
            ?.altText

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            // Skip this key, not the whole pass: non-AltText keys (the pinned number row, the
            // layout switch key) sit among the alphabet keys and must not cut the loop short.
            if (it.def !is KeyDef.Appearance.AltText) return@forEach
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (hangulInputMethodActive) transformAlphabetLegend(str)
                else if (keepLettersUppercase) str.uppercase()
                else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}
