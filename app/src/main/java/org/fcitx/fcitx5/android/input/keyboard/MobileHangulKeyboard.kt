/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.imageResource

private class MobileHangulKey(
    label: String,
    token: MobileHangulComposer.Token,
    percentWidth: Float,
    altLabel: String? = null,
    gesture: MoakeyGestureRecognizer? = null,
    includePressTokenOnGesture: Boolean = false,
    viewId: Int = -1
) : KeyDef(
    if (altLabel == null) {
        Appearance.Text(label, textSize = 19f, percentWidth = percentWidth, viewId = viewId)
    } else {
        Appearance.AltText(label, altLabel, textSize = 19f, percentWidth = percentWidth, viewId = viewId)
    },
    buildSet {
        add(Behavior.Press(KeyAction.MobileHangulAction(token)))
        gesture?.let { recognizer ->
            add(Behavior.Gesture { event ->
                recognizer.onEvent(event)?.let { gestureToken ->
                    if (includePressTokenOnGesture) {
                        KeyAction.MobileHangulSequenceAction(listOf(token, gestureToken))
                    } else {
                        KeyAction.MobileHangulAction(gestureToken)
                    }
                }
            })
        }
    },
    arrayOf(Popup.Preview(label))
)

/** Korean mobile surfaces transcribed from Samsung Keyboard's current layouts. */
@SuppressLint("ViewConstructor")
class MobileHangulKeyboard(
    context: Context,
    theme: Theme,
    val mobileLayout: MobileHangulLayout
) : BaseKeyboard(
    context,
    theme,
    PinnedNumberRow.prependTo(layoutFor(mobileLayout, effectiveDensity(context)))
) {

    companion object {
        fun name(layout: MobileHangulLayout) = "MobileHangul:${layout.name}"

        private const val QUARTER = 0.25f
        private const val EIGHTH = 0.125f
        private const val CENTER_SIDE = 0.16f
        private const val CENTER_KEY = (1f - CENTER_SIDE * 2f) / 3f

        /** Moakey's directional-swipe reach, in dp rather than a screen-density-blind px value. */
        private const val MOAKEY_GESTURE_THRESHOLD_DP = 28f

        private fun effectiveDensity(context: Context): Float =
            context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

        private fun jamo(label: String, value: Char, width: Float) =
            MobileHangulKey(label, MobileHangulComposer.Token.Jamo(value), width)

        private fun cycle(id: String, label: String, width: Float, vararg jamo: Char) =
            MobileHangulKey(
                label,
                MobileHangulComposer.Token.Cycle(id, jamo.toList()),
                width
            )

        private fun cycleAlt(
            id: String,
            label: String,
            altLabel: String,
            width: Float,
            vararg jamo: Char
        ) = MobileHangulKey(
            label,
            MobileHangulComposer.Token.Cycle(id, jamo.toList()),
            width,
            altLabel = altLabel
        )

        private fun singleVowelCycle(id: String, label: String, width: Float, vararg jamo: Char) =
            MobileHangulKey(
                label,
                MobileHangulComposer.Token.Cycle(
                    id,
                    jamo.toList(),
                    MobileHangulComposer.SINGLE_VOWEL_MULTITAP_TIMEOUT_MS
                ),
                width
            )

        private fun naratgulVowelCycle(id: String, label: String, width: Float, vararg jamo: Char) =
            MobileHangulKey(
                label,
                MobileHangulComposer.Token.Cycle(
                    id = id,
                    jamo = jamo.toList(),
                    naratgulVowelPair = true
                ),
                width
            )

        private fun symbolCycle(id: String, label: String, width: Float, vararg symbols: Char) =
            MobileHangulKey(
                label,
                MobileHangulComposer.Token.SymbolCycle(id, symbols.toList()),
                width
            )

        private fun token(
            label: String,
            value: MobileHangulComposer.Token,
            width: Float,
            viewId: Int = -1
        ) = MobileHangulKey(label, value, width, viewId = viewId)

        private fun symbol(label: String, value: String = label, width: Float) = KeyDef(
            KeyDef.Appearance.Text(label, textSize = 17f, percentWidth = width),
            setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(value))),
            arrayOf(KeyDef.Popup.Preview(label))
        )

        private fun mobileSpace(width: Float = 0f) = SpaceKey(
            percentWidth = width,
            pressAction = KeyAction.MobileHangulAction(MobileHangulComposer.Token.Boundary)
        )

        private fun moakeyJamo(label: String, value: Char, width: Float, gestureThresholdPx: Float) =
            MobileHangulKey(
                label,
                MobileHangulComposer.Token.Jamo(value),
                width,
                gesture = MoakeyGestureRecognizer(threshold = gestureThresholdPx),
                includePressTokenOnGesture = true
            )

        private fun qwertyBottom() = listOf(
            LayoutSwitchKey("?123", "", percentWidth = 0.13f),
            LanguageKey(),
            symbol(",", width = 0.1f),
            mobileSpace(),
            symbol(".", width = 0.1f),
            ReturnKey(percentWidth = 0.15f)
        )

        private fun chunjiin() = listOf(
            listOf(
                token("ㅣ", MobileHangulComposer.Token.VowelI, QUARTER),
                token("ㆍ", MobileHangulComposer.Token.VowelDot, QUARTER, R.id.button_chunjiin_dot),
                token("ㅡ", MobileHangulComposer.Token.VowelEu, QUARTER),
                BackspaceKey(percentWidth = QUARTER)
            ),
            listOf(
                cycle("cj_g", "ㄱㅋ", QUARTER, 'ㄱ', 'ㅋ', 'ㄲ'),
                cycle("cj_n", "ㄴㄹ", QUARTER, 'ㄴ', 'ㄹ'),
                cycle("cj_d", "ㄷㅌ", QUARTER, 'ㄷ', 'ㅌ', 'ㄸ'),
                ReturnKey(percentWidth = QUARTER)
            ),
            listOf(
                cycle("cj_b", "ㅂㅍ", QUARTER, 'ㅂ', 'ㅍ', 'ㅃ'),
                cycle("cj_s", "ㅅㅎ", QUARTER, 'ㅅ', 'ㅎ', 'ㅆ'),
                cycle("cj_j", "ㅈㅊ", QUARTER, 'ㅈ', 'ㅊ', 'ㅉ'),
                symbolCycle("cj_period", ".,", EIGHTH, '.', ','),
                symbolCycle("cj_question", "?!", EIGHTH, '?', '!')
            ),
            listOf(
                LayoutSwitchKey("?123", "", percentWidth = EIGHTH),
                LanguageKey(percentWidth = EIGHTH),
                cycle("cj_ng", "ㅇㅁ", QUARTER, 'ㅇ', 'ㅁ'),
                mobileSpace(QUARTER),
                symbol(",", width = QUARTER)
            )
        )

        private fun chunjiinPlus() = listOf(
            listOf(
                token("ㅣ", MobileHangulComposer.Token.VowelI, QUARTER),
                token("ㆍ", MobileHangulComposer.Token.VowelDot, QUARTER, R.id.button_chunjiin_dot),
                token("ㅡ", MobileHangulComposer.Token.VowelEu, QUARTER),
                BackspaceKey(percentWidth = QUARTER)
            ),
            listOf(
                jamo("ㄱ", 'ㄱ', EIGHTH),
                cycleAlt("cjp_k", "ㅋ", "ㄲ", EIGHTH, 'ㅋ', 'ㄲ'),
                jamo("ㄴ", 'ㄴ', EIGHTH),
                jamo("ㄹ", 'ㄹ', EIGHTH),
                jamo("ㄷ", 'ㄷ', EIGHTH),
                cycleAlt("cjp_t", "ㅌ", "ㄸ", EIGHTH, 'ㅌ', 'ㄸ'),
                ReturnKey(percentWidth = QUARTER)
            ),
            listOf(
                jamo("ㅂ", 'ㅂ', EIGHTH),
                cycleAlt("cjp_p", "ㅍ", "ㅃ", EIGHTH, 'ㅍ', 'ㅃ'),
                jamo("ㅅ", 'ㅅ', EIGHTH),
                cycleAlt("cjp_h", "ㅎ", "ㅆ", EIGHTH, 'ㅎ', 'ㅆ'),
                jamo("ㅈ", 'ㅈ', EIGHTH),
                cycleAlt("cjp_c", "ㅊ", "ㅉ", EIGHTH, 'ㅊ', 'ㅉ'),
                symbolCycle("cjp_period", ".,", EIGHTH, '.', ','),
                symbolCycle("cjp_question", "?!", EIGHTH, '?', '!')
            ),
            listOf(
                LayoutSwitchKey("?123", "", percentWidth = EIGHTH),
                LanguageKey(percentWidth = EIGHTH),
                jamo("ㅇ", 'ㅇ', EIGHTH),
                jamo("ㅁ", 'ㅁ', EIGHTH),
                mobileSpace(QUARTER),
                symbol(",", width = QUARTER)
            )
        )

        private fun danmoum() = listOf(
            listOf(
                singleVowelCycle("dm_b", "ㅂ", EIGHTH, 'ㅂ', 'ㅃ'),
                singleVowelCycle("dm_j", "ㅈ", EIGHTH, 'ㅈ', 'ㅉ'),
                singleVowelCycle("dm_d", "ㄷ", EIGHTH, 'ㄷ', 'ㄸ'),
                singleVowelCycle("dm_g", "ㄱ", EIGHTH, 'ㄱ', 'ㄲ'),
                singleVowelCycle("dm_s", "ㅅ", EIGHTH, 'ㅅ', 'ㅆ'),
                singleVowelCycle("dm_o", "ㅗ", EIGHTH, 'ㅗ', 'ㅛ'),
                singleVowelCycle("dm_ae", "ㅐ", EIGHTH, 'ㅐ', 'ㅒ'),
                singleVowelCycle("dm_e", "ㅔ", EIGHTH, 'ㅔ', 'ㅖ')
            ),
            listOf(
                jamo("ㅁ", 'ㅁ', EIGHTH), jamo("ㄴ", 'ㄴ', EIGHTH),
                jamo("ㅇ", 'ㅇ', EIGHTH), jamo("ㄹ", 'ㄹ', EIGHTH),
                jamo("ㅎ", 'ㅎ', EIGHTH),
                singleVowelCycle("dm_eo", "ㅓ", EIGHTH, 'ㅓ', 'ㅕ'),
                singleVowelCycle("dm_a", "ㅏ", EIGHTH, 'ㅏ', 'ㅑ'),
                token("ㅣ", MobileHangulComposer.Token.VowelI, EIGHTH)
            ),
            listOf(
                jamo("ㅋ", 'ㅋ', 1f / 7f), jamo("ㅌ", 'ㅌ', 1f / 7f),
                jamo("ㅊ", 'ㅊ', 1f / 7f), jamo("ㅍ", 'ㅍ', 1f / 7f),
                singleVowelCycle("dm_u", "ㅜ", 1f / 7f, 'ㅜ', 'ㅠ'),
                jamo("ㅡ", 'ㅡ', 1f / 7f),
                BackspaceKey(percentWidth = 1f / 7f)
            ),
            qwertyBottom()
        )

        private fun moakeyBottom(oneHand: Boolean, gestureThresholdPx: Float) = buildList {
            add(LayoutSwitchKey("?123", "", percentWidth = 0.13f))
            add(LanguageKey())
            add(symbol(",", width = 0.08f))
            add(mobileSpace())
            if (oneHand) {
                add(
                    MobileHangulKey(
                        "ㆍ ㅣ ㅡ",
                        MobileHangulComposer.Token.VowelDot,
                        0.16f,
                        gesture = MoakeyGestureRecognizer(
                            standaloneVowelKey = true,
                            threshold = gestureThresholdPx
                        )
                    )
                )
            } else {
                add(symbolCycle("mk_punct", "?.!", 0.10f, '.', '?', '!'))
            }
            add(ReturnKey(percentWidth = 0.15f))
        }

        private fun moakey(oneHand: Boolean, gestureThresholdPx: Float): List<List<KeyDef>> {
            val seven = 1f / 7f
            val six = 1f / 6f
            return buildList {
                add(
                    listOf(symbol("~", width = seven)) +
                        "ㅃㅉㄸㄲㅆ".map { moakeyJamo(it.toString(), it, seven, gestureThresholdPx) } +
                        symbol(if (oneHand) "!" else "#", width = seven)
                )
                add(
                    listOf(symbol("^", width = seven)) +
                        "ㅂㅈㄷㄱㅅ".map { moakeyJamo(it.toString(), it, seven, gestureThresholdPx) } +
                        if (oneHand) listOf(symbol("?", width = seven))
                        else listOf(BackspaceKey(percentWidth = seven))
                )
                add(
                    listOf(symbol(";", width = seven)) +
                        "ㅁㄴㅇㄹㅎ".map { moakeyJamo(it.toString(), it, seven, gestureThresholdPx) } +
                        if (oneHand) listOf(symbol(".", width = seven))
                        else listOf(token("ㅣ", MobileHangulComposer.Token.VowelI, seven))
                )
                add(
                    listOf(symbol("*", width = if (oneHand) six else seven)) +
                        "ㅋㅌㅊㅍ".map {
                            moakeyJamo(it.toString(), it, if (oneHand) six else seven, gestureThresholdPx)
                        } +
                        if (oneHand) {
                            listOf(BackspaceKey(percentWidth = six))
                        } else {
                            listOf(
                                token("ㅡ", MobileHangulComposer.Token.VowelEu, seven),
                                token("ㆍ", MobileHangulComposer.Token.VowelDot, seven)
                            )
                        }
                )
                add(moakeyBottom(oneHand, gestureThresholdPx))
            }
        }

        private fun vegaCore(width: Float) = listOf(
            listOf(
                cycle("vg_g", "ㄱㅋ", width, 'ㄱ', 'ㅋ', 'ㄲ'),
                cycle("vg_ie", "ㅣㅡ", width, 'ㅣ', 'ㅡ', 'ㅢ'),
                cycle("vg_a", "ㅏㅑ", width, 'ㅏ', 'ㅑ')
            ),
            listOf(
                cycle("vg_d", "ㄷㅌ", width, 'ㄷ', 'ㅌ', 'ㄸ'),
                cycle("vg_n", "ㄴㄹ", width, 'ㄴ', 'ㄹ'),
                cycle("vg_eo", "ㅓㅕ", width, 'ㅓ', 'ㅕ')
            ),
            listOf(
                cycle("vg_m", "ㅁㅅ", width, 'ㅁ', 'ㅅ', 'ㅆ'),
                cycle("vg_b", "ㅂㅍ", width, 'ㅂ', 'ㅍ', 'ㅃ'),
                cycle("vg_o", "ㅗㅛ", width, 'ㅗ', 'ㅛ')
            ),
            listOf(
                cycle("vg_j", "ㅈㅊ", width, 'ㅈ', 'ㅊ', 'ㅉ'),
                cycle("vg_ng", "ㅇㅎ", width, 'ㅇ', 'ㅎ'),
                cycle("vg_u", "ㅜㅠ", width, 'ㅜ', 'ㅠ')
            )
        )

        private fun vega(centered: Boolean): List<List<KeyDef>> {
            val width = if (centered) CENTER_KEY else QUARTER
            val core = vegaCore(width)
            return if (centered) {
                listOf(
                    listOf(symbolCycle("vg_qm", "?!", CENTER_SIDE, '?', '!')) + core[0] +
                        BackspaceKey(CENTER_SIDE),
                    listOf(symbol(",", width = CENTER_SIDE)) + core[1] + mobileSpace(CENTER_SIDE),
                    listOf(LanguageKey(CENTER_SIDE)) + core[2] + ReturnKey(CENTER_SIDE),
                    listOf(LayoutSwitchKey("?123", "", CENTER_SIDE)) + core[3] +
                        symbol(".", width = CENTER_SIDE)
                )
            } else {
                listOf(
                    core[0] + BackspaceKey(QUARTER),
                    core[1] + mobileSpace(QUARTER),
                    core[2] + listOf(
                        symbolCycle("vg_punct", ".,?!", EIGHTH, '.', ',', '?', '!'),
                        ReturnKey(EIGHTH)
                    ),
                    core[3] + listOf(LayoutSwitchKey("?123", "", EIGHTH), LanguageKey(EIGHTH))
                )
            }
        }

        private fun naratgulCore(width: Float) = listOf(
            listOf(
                jamo("ㄱ", 'ㄱ', width),
                jamo("ㄴ", 'ㄴ', width),
                naratgulVowelCycle("nr_a", "ㅏㅓ", width, 'ㅏ', 'ㅓ')
            ),
            listOf(
                jamo("ㄹ", 'ㄹ', width),
                jamo("ㅁ", 'ㅁ', width),
                naratgulVowelCycle("nr_o", "ㅗㅜ", width, 'ㅗ', 'ㅜ')
            ),
            listOf(
                jamo("ㅅ", 'ㅅ', width),
                jamo("ㅇ", 'ㅇ', width),
                token("ㅣ", MobileHangulComposer.Token.VowelI, width)
            ),
            listOf(
                token("획추가", MobileHangulComposer.Token.AddStroke, width),
                jamo("ㅡ", 'ㅡ', width),
                token("쌍자음", MobileHangulComposer.Token.DoubleConsonant, width)
            )
        )

        private fun naratgul(centered: Boolean): List<List<KeyDef>> {
            val width = if (centered) CENTER_KEY else QUARTER
            val core = naratgulCore(width)
            return if (centered) {
                listOf(
                    listOf(symbolCycle("nr_qm", "?!", CENTER_SIDE, '?', '!')) + core[0] +
                        BackspaceKey(CENTER_SIDE),
                    listOf(symbol(",", width = CENTER_SIDE)) + core[1] + mobileSpace(CENTER_SIDE),
                    listOf(LanguageKey(CENTER_SIDE)) + core[2] + ReturnKey(CENTER_SIDE),
                    listOf(LayoutSwitchKey("?123", "", CENTER_SIDE)) + core[3] +
                        symbol(".", width = CENTER_SIDE)
                )
            } else {
                listOf(
                    core[0] + BackspaceKey(QUARTER),
                    core[1] + mobileSpace(QUARTER),
                    core[2] + listOf(
                        symbolCycle("nr_punct", ".,?!", EIGHTH, '.', ',', '?', '!'),
                        ReturnKey(EIGHTH)
                    ),
                    core[3] + listOf(LayoutSwitchKey("?123", "", EIGHTH), LanguageKey(EIGHTH))
                )
            }
        }

        fun layoutFor(layout: MobileHangulLayout, density: Float = 1f): List<List<KeyDef>> =
            when (layout) {
                MobileHangulLayout.Chunjiin -> chunjiin()
                MobileHangulLayout.ChunjiinPlus -> chunjiinPlus()
                MobileHangulLayout.Danmoum -> danmoum()
                MobileHangulLayout.MoakeyOneHand ->
                    moakey(true, MOAKEY_GESTURE_THRESHOLD_DP * density)
                MobileHangulLayout.MoakeyTwoHand ->
                    moakey(false, MOAKEY_GESTURE_THRESHOLD_DP * density)
                MobileHangulLayout.Vega -> vega(false)
                MobileHangulLayout.VegaCenter -> vega(true)
                MobileHangulLayout.Naratgul -> naratgul(false)
                MobileHangulLayout.NaratgulCenter -> naratgul(true)
                MobileHangulLayout.Physical -> error("Physical layout does not use MobileHangulKeyboard")
            }
    }

    // The layout's own row count, before PinnedNumberRow prepends a number row on top (K15).
    override val baseRowCount: Int = layoutFor(mobileLayout).size

    private val composer = MobileHangulComposer(family = mobileHangulFamily(mobileLayout))
    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    private val dotKeyView: TextKeyView? by lazy { findViewById(R.id.button_chunjiin_dot) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Suppress("unused")
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        lang.visibility = if (value) View.VISIBLE else View.GONE
    }

    init {
        lang.visibility = if (showLangSwitchKey.getValue()) View.VISIBLE else View.GONE
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val router = MobileHangulActionRouter(composer)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        router.route(action).forEach { routed ->
            when (routed) {
                is MobileHangulActionRouter.RoutedAction.ComposerOutputs ->
                    routed.outputs.forEach { dispatch(it, source) }
                is MobileHangulActionRouter.RoutedAction.Forward ->
                    super.onAction(routed.action, source)
            }
        }
        updateDotLabel()
    }

    private fun dispatch(output: MobileHangulComposer.Output, source: KeyActionListener.Source) {
        when (output) {
            MobileHangulComposer.Output.Backspace -> super.onAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace), KeyStates.Virtual),
                source
            )
            MobileHangulComposer.Output.Space -> super.onAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space), KeyStates.Virtual),
                source
            )
            is MobileHangulComposer.Output.Keys -> output.value.forEach { character ->
                super.onAction(KeyAction.FcitxKeyAction(character.toString()), source)
            }
        }
    }

    private fun updateDotLabel() {
        val pending = composer.pendingDotCount()
        dotKeyView?.mainText?.apply {
            text = if (pending == 2) "‥" else "ㆍ"
            setTextColor(if (pending > 0) theme.accentKeyBackgroundColor else theme.keyTextColor)
        }
    }

    override fun onAttach() {
        router.reset()
        updateDotLabel()
    }

    override fun onStartInput() {
        router.reset()
        updateDotLabel()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        router.onSelectionUpdate(start, end)
        updateDotLabel()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        router.onPreeditEmptyStateUpdate(empty)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        val label = context.getString(mobileLayout.stringRes)
        space.mainText.text = context.getString(R.string.mobile_hangul_switch_label, label)
        space.contentDescription = context.getString(R.string.mobile_hangul_switch_hint, label)
    }
}

/** Which vowel-combination table this layout's ㅣ/ㅡ primitives should use. See K10. */
private fun mobileHangulFamily(layout: MobileHangulLayout): MobileHangulFamily = when (layout) {
    MobileHangulLayout.Chunjiin, MobileHangulLayout.ChunjiinPlus -> MobileHangulFamily.Chunjiin
    else -> MobileHangulFamily.Other
}
