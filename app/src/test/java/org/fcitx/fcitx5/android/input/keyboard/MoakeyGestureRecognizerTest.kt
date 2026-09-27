/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.MoakeyGestureRecognizer.Zone.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoakeyGestureRecognizerTest {
    private val recognizer = MoakeyGestureRecognizer()

    @Test fun `cardinal and diagonal gestures cover six basic vowels`() {
        assertEquals('ㅏ', recognizer.resolve(listOf(Right)))
        assertEquals('ㅓ', recognizer.resolve(listOf(Left)))
        assertEquals('ㅗ', recognizer.resolve(listOf(Up)))
        assertEquals('ㅜ', recognizer.resolve(listOf(Down)))
        assertEquals('ㅣ', recognizer.resolve(listOf(UpperDiagonal)))
        assertEquals('ㅡ', recognizer.resolve(listOf(LowerDiagonal)))
    }

    @Test fun `return and turn gestures cover derived vowels`() {
        assertEquals('ㅑ', recognizer.resolve(listOf(Right, Center, Right)))
        assertEquals('ㅕ', recognizer.resolve(listOf(Left, Center, Left)))
        assertEquals('ㅛ', recognizer.resolve(listOf(Up, Center, Up)))
        assertEquals('ㅠ', recognizer.resolve(listOf(Down, Center, Down)))
        assertEquals('ㅐ', recognizer.resolve(listOf(Right, Up)))
        assertEquals('ㅔ', recognizer.resolve(listOf(Left, Down)))
        assertEquals('ㅚ', recognizer.resolve(listOf(Up, Right)))
        assertEquals('ㅟ', recognizer.resolve(listOf(Down, Right)))
        assertEquals('ㅘ', recognizer.resolve(listOf(Up, Center, Right)))
        assertEquals('ㅙ', recognizer.resolve(listOf(Up, Center, Right, Up)))
        assertEquals('ㅝ', recognizer.resolve(listOf(Down, Center, Left)))
        assertEquals('ㅞ', recognizer.resolve(listOf(Down, Center, Left, Down)))
        assertEquals('ㅢ', recognizer.resolve(listOf(LowerDiagonal, Center)))
    }

    @Test fun `standalone vowel key matches one hand Moakey`() {
        val standalone = MoakeyGestureRecognizer(standaloneVowelKey = true)
        assertEquals('ㅣ', standalone.resolve(listOf(Right)))
        assertEquals('ㅡ', standalone.resolve(listOf(Left)))
    }

    @Test fun `K9 standalone vowel key swipes carry chunjiin VowelI and VowelEu meaning`() {
        val standalone = MoakeyGestureRecognizer(standaloneVowelKey = true)
        assertEquals(MobileHangulComposer.Token.VowelI, gesture(standalone, 100f, 0f))
        assertEquals(MobileHangulComposer.Token.VowelEu, gesture(standalone, -100f, 0f))
    }

    @Test fun `K9 the regular (non-standalone) vowel keys still emit plain jamo`() {
        val token = gesture(recognizer, 100f, 0f)
        assertEquals(MobileHangulComposer.Token.Jamo('ㅏ'), token)
    }

    @Test fun `K16 a larger threshold needs a longer swipe to register a direction`() {
        val loose = MoakeyGestureRecognizer(threshold = 56f)
        // A 40px move clears the default 28px threshold but not a 56px (dp-scaled) one.
        assertNull(gesture(loose, 40f, 0f))
        assertEquals(MobileHangulComposer.Token.Jamo('ㅏ'), gesture(loose, 100f, 0f))
    }

    private fun gesture(
        recognizer: MoakeyGestureRecognizer,
        dx: Float,
        dy: Float
    ): MobileHangulComposer.Token? {
        recognizer.onEvent(CustomGestureView.Event(CustomGestureView.GestureType.Down, false, 0f, 0f, 0, 0, 0, 0))
        recognizer.onEvent(CustomGestureView.Event(CustomGestureView.GestureType.Move, false, dx, dy, 0, 0, 0, 0))
        return recognizer.onEvent(
            CustomGestureView.Event(CustomGestureView.GestureType.Up, false, dx, dy, 0, 0, 0, 0)
        )
    }
}
