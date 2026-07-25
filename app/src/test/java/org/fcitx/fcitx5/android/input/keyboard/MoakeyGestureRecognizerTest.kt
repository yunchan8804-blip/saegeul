/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.MoakeyGestureRecognizer.Zone.*
import org.junit.Assert.assertEquals
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
}
