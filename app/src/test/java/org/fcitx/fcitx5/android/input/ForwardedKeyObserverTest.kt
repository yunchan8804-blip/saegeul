/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ForwardedKeyObserver: 원시 KeyEvent가 편집기에 실제로 남기는 출력 가능한
 * 문자열 변환. 삭제·이동·수정키 조합은 null을 돌려줘야 한다.
 */
class ForwardedKeyObserverTest {

    @Test
    fun `space key produces a literal space`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_SPACE,
            unicodeChar = ' '.code,
            metaState = 0
        )
        assertEquals(" ", result)
    }

    @Test
    fun `enter key always produces a newline regardless of unicodeChar`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_ENTER,
            unicodeChar = 0,
            metaState = 0
        )
        assertEquals("\n", result)
    }

    @Test
    fun `delete key produces no observable text`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_DEL,
            unicodeChar = 8,
            metaState = 0
        )
        assertNull(result)
    }

    @Test
    fun `forward delete key produces no observable text`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_FORWARD_DEL,
            unicodeChar = 0,
            metaState = 0
        )
        assertNull(result)
    }

    @Test
    fun `ctrl plus a shortcut produces no observable text`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_A,
            unicodeChar = 1,
            metaState = KeyEvent.META_CTRL_ON
        )
        assertNull(result)
    }

    @Test
    fun `plain letter key produces its own character`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_A,
            unicodeChar = 'a'.code,
            metaState = 0
        )
        assertEquals("a", result)
    }

    @Test
    fun `punctuation key produces its own character`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_PERIOD,
            unicodeChar = '.'.code,
            metaState = 0
        )
        assertEquals(".", result)
    }

    @Test
    fun `shift modifier key itself produces no observable text`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
            unicodeChar = 0,
            metaState = KeyEvent.META_SHIFT_ON
        )
        assertNull(result)
    }

    @Test
    fun `zero unicode char with no special key code produces no observable text`() {
        val result = ForwardedKeyObserver.printableText(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            unicodeChar = 0,
            metaState = 0
        )
        assertNull(result)
    }
}
