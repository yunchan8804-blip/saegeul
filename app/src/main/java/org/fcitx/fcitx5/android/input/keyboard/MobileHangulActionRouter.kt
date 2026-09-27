/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym

/**
 * Decides, for a mobile Hangul keyboard, what a [KeyAction] should do to the composer and what
 * (if anything) should be forwarded on — pulled out of [MobileHangulKeyboard] as a pure class so
 * this routing can be unit tested without a View.
 *
 * [KeyAction.DeleteSelectionAction] and [KeyAction.MoveSelectionAction] are ancillary actions a
 * key's gesture release sends on top of its click (e.g. Backspace's swipe-to-move-cursor handler
 * fires [KeyAction.DeleteSelectionAction] on every Up, tap or swipe alike, right before the click
 * itself sends the real Backspace). They must never touch composer state themselves — only a
 * real K2 selection-change signal does — or an accidental ㆍ tap's pending state would be wiped
 * out just before the Backspace meant to cancel it arrives (K3's "조합기 상태는 키 동작 순서에
 * 흔들리지 않는다").
 */
class MobileHangulActionRouter(private val composer: MobileHangulComposer) {

    sealed interface RoutedAction {
        data class ComposerOutputs(val outputs: List<MobileHangulComposer.Output>) : RoutedAction
        data class Forward(val action: KeyAction) : RoutedAction
    }

    private val backspaceSym = KeySym(FcitxKeyMapping.FcitxKey_BackSpace)

    fun route(action: KeyAction): List<RoutedAction> = when (action) {
        is KeyAction.MobileHangulAction ->
            listOf(RoutedAction.ComposerOutputs(composer.press(action.token)))
        is KeyAction.MobileHangulSequenceAction ->
            listOf(RoutedAction.ComposerOutputs(action.tokens.flatMap(composer::press)))
        is KeyAction.DeleteSelectionAction, is KeyAction.MoveSelectionAction ->
            listOf(RoutedAction.Forward(action))
        is KeyAction.SymAction ->
            if (action.sym == backspaceSym && composer.cancelPendingDot()) {
                // Absorbed locally: a lone accidental ㆍ tap must not reach the Dubeolsik backend.
                emptyList()
            } else {
                composer.reset()
                listOf(RoutedAction.Forward(action))
            }
        else -> {
            composer.reset()
            listOf(RoutedAction.Forward(action))
        }
    }
}
