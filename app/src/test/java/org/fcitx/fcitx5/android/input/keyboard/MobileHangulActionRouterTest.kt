/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileHangulActionRouterTest {

    private val backspaceAction =
        KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace), KeyStates.Virtual)

    @Test
    fun `K1 a gesture-release DeleteSelectionAction before Backspace must not wipe a pending dot`() {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)

        // ㄱ + ㅣ + ㆍ + ㅇ = 강, then an accidental extra ㆍ tap leaves one dot pending.
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.Jamo('ㄱ')))
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelI))
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelDot))
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.Jamo('ㅇ')))
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelDot))
        assertEquals(1, composer.pendingDotCount())

        // BaseKeyboard's Backspace gesture Up always fires DeleteSelectionAction first, tap or
        // swipe alike, then the click sends the real Backspace (see MobileHangulKeyboard.kt).
        val deleteSelectionRouted = router.route(KeyAction.DeleteSelectionAction(0))
        assertEquals(
            "DeleteSelectionAction must pass straight through without touching the composer",
            listOf(MobileHangulActionRouter.RoutedAction.Forward(KeyAction.DeleteSelectionAction(0))),
            deleteSelectionRouted
        )
        assertEquals(
            "the pending dot must still be there after the ancillary action",
            1,
            composer.pendingDotCount()
        )

        val backspaceRouted = router.route(backspaceAction)
        assertTrue(
            "Backspace must be absorbed locally, not forwarded to the engine",
            backspaceRouted.isEmpty()
        )
        assertEquals(0, composer.pendingDotCount())
    }

    @Test
    fun `MoveSelectionAction from a Backspace swipe also passes through untouched`() {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelDot))
        assertEquals(1, composer.pendingDotCount())

        router.route(KeyAction.MoveSelectionAction(3))

        assertEquals(1, composer.pendingDotCount())
    }

    @Test
    fun `a real Backspace with nothing pending resets the composer and forwards as usual`() {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.Jamo('ㅑ')))

        val routed = router.route(backspaceAction)

        assertEquals(listOf(MobileHangulActionRouter.RoutedAction.Forward(backspaceAction)), routed)
        // The composer was reset, so a following ㅣ must not combine into ㅒ.
        val next = router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelI))
        assertEquals(
            listOf(
                MobileHangulActionRouter.RoutedAction.ComposerOutputs(
                    listOf(MobileHangulComposer.Output.Keys("l"))
                )
            ),
            next
        )
    }

    @Test
    fun `a mobile hangul token press composes and returns composer outputs`() {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)

        val routed = router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.Jamo('ㄱ')))

        assertEquals(
            listOf(
                MobileHangulActionRouter.RoutedAction.ComposerOutputs(
                    listOf(MobileHangulComposer.Output.Keys("r"))
                )
            ),
            routed
        )
    }
}
