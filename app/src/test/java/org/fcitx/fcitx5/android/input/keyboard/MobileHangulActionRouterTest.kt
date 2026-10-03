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

    // K2: a symbol cycle's own commit must not be mistaken for the user moving the cursor.

    private val dotComma = MobileHangulComposer.Token.SymbolCycle("dotComma", listOf('.', ','))

    /** Feeds a router's own outputs into a plain text buffer, notifying it of each edit's result,
     * the same way [MobileHangulKeyboard.dispatch] commits straight to the real editor. */
    private class FakeEditor(private val router: MobileHangulActionRouter) {
        val text = StringBuilder()
        var cursor = 0
            private set

        fun apply(outputs: List<MobileHangulComposer.Output>) {
            outputs.forEach { output ->
                when (output) {
                    MobileHangulComposer.Output.Backspace -> {
                        text.deleteCharAt(cursor - 1)
                        cursor -= 1
                        notifySelectionUpdate()
                    }
                    MobileHangulComposer.Output.Space -> {
                        text.insert(cursor, ' ')
                        cursor += 1
                        notifySelectionUpdate()
                    }
                    is MobileHangulComposer.Output.Keys -> output.value.forEach { c ->
                        text.insert(cursor, c)
                        cursor += 1
                        notifySelectionUpdate()
                    }
                }
            }
        }

        fun moveCursorByUser(start: Int, end: Int) {
            cursor = end
            router.onSelectionUpdate(start, end)
        }

        private fun notifySelectionUpdate() = router.onSelectionUpdate(cursor, cursor)
    }

    private fun routerWithEditor(): Pair<MobileHangulActionRouter, FakeEditor> {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)
        val editor = FakeEditor(router)
        router.onPreeditEmptyStateUpdate(true)
        // The IME always learns the field's starting selection before any key is pressed.
        editor.moveCursorByUser(0, 0)
        return router to editor
    }

    @Test
    fun `two quick taps of a dot-comma cycle key land the cycle's second symbol, not a repeat`() {
        val (router, editor) = routerWithEditor()

        editor.apply(
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        )
        assertEquals(".", editor.text.toString())

        editor.apply(
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        )

        assertEquals(
            "the own selection echo from the first tap's commit must not reset the cycle",
            ",",
            editor.text.toString()
        )
    }

    @Test
    fun `an intermediate and a final selection echo from the same output batch both stay quiet`() {
        val (router, editor) = routerWithEditor()
        editor.apply(
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        )
        // The second tap's Backspace (intermediate) then Keys(",") (final) each notify the router
        // separately, exactly like two separate onUpdateSelection calls from the real editor.
        val secondTapOutputs =
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        assertEquals(
            listOf(MobileHangulComposer.Output.Backspace, MobileHangulComposer.Output.Keys(",")),
            secondTapOutputs
        )

        editor.apply(secondTapOutputs)

        assertEquals(",", editor.text.toString())
    }

    @Test
    fun `a user cursor move after the first tap resets the cycle so the next tap starts over`() {
        val (router, editor) = routerWithEditor()
        editor.apply(
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        )
        assertEquals(".", editor.text.toString())

        editor.moveCursorByUser(5, 5)

        val secondTapOutputs =
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs

        assertEquals(
            "after a real cursor move the cycle must restart at its first symbol",
            listOf(MobileHangulComposer.Output.Keys(".")),
            secondTapOutputs
        )
    }

    @Test
    fun `a non-collapsed selection after the first tap resets the cycle`() {
        val (router, editor) = routerWithEditor()
        editor.apply(
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs
        )

        router.onSelectionUpdate(0, 3)

        val secondTapOutputs =
            (router.route(KeyAction.MobileHangulAction(dotComma)).single()
                    as MobileHangulActionRouter.RoutedAction.ComposerOutputs).outputs

        assertEquals(
            "a range selection is never the cycle's own collapsed-cursor echo",
            listOf(MobileHangulComposer.Output.Keys(".")),
            secondTapOutputs
        )
    }

    @Test
    fun `a selection change while composing a syllable still leaves the composer untouched`() {
        val composer = MobileHangulComposer()
        val router = MobileHangulActionRouter(composer)
        router.onSelectionUpdate(0, 0)
        router.onPreeditEmptyStateUpdate(false)
        router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelDot))
        assertEquals(1, composer.pendingDotCount())

        router.onSelectionUpdate(7, 7)

        assertEquals(
            "a selection change while preedit is non-empty must not reset the composer",
            1,
            composer.pendingDotCount()
        )
    }

    @Test
    fun `the mic key only forwards, so the syllable being composed is left for dictation to confirm`() {
        KeyAction.VoiceInputAction.Phase.entries.forEach { phase ->
            val composer = MobileHangulComposer()
            val router = MobileHangulActionRouter(composer)
            router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.Jamo('ㄱ')))
            router.route(KeyAction.MobileHangulAction(MobileHangulComposer.Token.VowelDot))
            assertEquals(1, composer.pendingDotCount())
            val mic = KeyAction.VoiceInputAction(phase)

            val routed = router.route(mic)

            assertEquals(
                "no composer output, so nothing is erased or rewritten in the editor",
                listOf(MobileHangulActionRouter.RoutedAction.Forward(mic)),
                routed
            )
            assertEquals("only the composer's own unsent ㆍ bookkeeping is cleared", 0, composer.pendingDotCount())
        }
    }
}
