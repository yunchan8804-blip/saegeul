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
 *
 * It also owns K2's "초기화 시점" call: while there is no preedit, a composer output that commits
 * straight to the editor (a symbol cycle's Backspace/character, Space, …) moves the selection on
 * its own, and that self-caused move must not be mistaken for the user moving the cursor. Before
 * dispatching such outputs, [route] projects the selection positions they should produce from the
 * last known selection end; [onSelectionUpdate] then recognizes a collapsed selection landing on
 * that projected path (including its intermediate steps) as its own echo and leaves the composer
 * alone, consuming the path up to and including the matched step. Anything else — an unknown
 * starting position, a position off the path, or a non-collapsed selection — resets the composer,
 * exactly as before.
 */
class MobileHangulActionRouter(private val composer: MobileHangulComposer) {

    sealed interface RoutedAction {
        data class ComposerOutputs(val outputs: List<MobileHangulComposer.Output>) : RoutedAction
        data class Forward(val action: KeyAction) : RoutedAction
    }

    private val backspaceSym = KeySym(FcitxKeyMapping.FcitxKey_BackSpace)

    private var preeditEmpty = true
    private var lastSelectionEnd: Int? = null
    private val expectedSelectionEnds = mutableListOf<Int>()

    fun route(action: KeyAction): List<RoutedAction> = when (action) {
        is KeyAction.MobileHangulAction -> routeComposerOutputs(composer.press(action.token))
        is KeyAction.MobileHangulSequenceAction ->
            routeComposerOutputs(action.tokens.flatMap(composer::press))
        is KeyAction.DeleteSelectionAction, is KeyAction.MoveSelectionAction ->
            listOf(RoutedAction.Forward(action))
        is KeyAction.SymAction ->
            if (action.sym == backspaceSym && composer.cancelPendingDot()) {
                // Absorbed locally: a lone accidental ㆍ tap must not reach the Dubeolsik backend.
                emptyList()
            } else {
                resetComposer()
                listOf(RoutedAction.Forward(action))
            }
        else -> {
            resetComposer()
            listOf(RoutedAction.Forward(action))
        }
    }

    /** Forwarded from [BaseKeyboard.onPreeditEmptyStateUpdate]; no other state changes. */
    fun onPreeditEmptyStateUpdate(empty: Boolean) {
        preeditEmpty = empty
    }

    /**
     * Forwarded from [BaseKeyboard.onClientPreeditUpdate] (K21): tells the composer which
     * syllable is still composing, so a later multitap/transform key can recover a batchim
     * libhangul already committed. Only the preedit's last, complete Hangul syllable matters; a
     * bare open jamo or punctuation carries nothing usable and clears it instead.
     */
    fun onClientPreeditUpdate(text: String) {
        composer.setComposingSyllable(text.lastOrNull()?.takeIf { it in '가'..'힣' })
    }

    /**
     * Forwarded from [BaseKeyboard.onSelectionUpdate]. Resets the composer for a real user/engine
     * selection change, but not for the echo of an output this router just dispatched itself.
     */
    fun onSelectionUpdate(start: Int, end: Int) {
        val isOwnEcho = preeditEmpty && start == end && consumeExpectedSelectionEnd(end)
        if (preeditEmpty && !isOwnEcho) {
            resetComposer()
        }
        lastSelectionEnd = end
    }

    /** Full reset for a new input session (attach / input-field change). */
    fun reset() {
        resetComposer()
        lastSelectionEnd = null
        preeditEmpty = true
    }

    private fun routeComposerOutputs(
        outputs: List<MobileHangulComposer.Output>
    ): List<RoutedAction> {
        if (preeditEmpty) registerExpectedPath(outputs)
        return listOf(RoutedAction.ComposerOutputs(outputs))
    }

    /** Projects the selection-end position after each atomic step these outputs will cause. */
    private fun registerExpectedPath(outputs: List<MobileHangulComposer.Output>) {
        val base = lastSelectionEnd ?: return
        expectedSelectionEnds.clear()
        var position = base
        outputs.forEach { output ->
            when (output) {
                MobileHangulComposer.Output.Backspace -> {
                    position -= 1
                    expectedSelectionEnds.add(position)
                }
                MobileHangulComposer.Output.Space -> {
                    position += 1
                    expectedSelectionEnds.add(position)
                }
                is MobileHangulComposer.Output.Keys -> repeat(output.value.length) {
                    position += 1
                    expectedSelectionEnds.add(position)
                }
            }
        }
    }

    /** If [end] is on the projected path, consumes it and every step before it. */
    private fun consumeExpectedSelectionEnd(end: Int): Boolean {
        val index = expectedSelectionEnds.indexOf(end)
        if (index < 0) return false
        repeat(index + 1) { expectedSelectionEnds.removeAt(0) }
        return true
    }

    private fun resetComposer() {
        composer.reset()
        expectedSelectionEnds.clear()
    }
}
