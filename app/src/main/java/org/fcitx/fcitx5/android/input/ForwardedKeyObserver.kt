/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent

/**
 * 우리 커밋 경로(commitTextToEditor)를 거치지 않고 편집기로 직접 전달되는 원시 키 이벤트가
 * 실제로 삽입하는 출력 가능한 문자열을 계산한다. 스페이스·문장부호·영숫자처럼 편집기에
 * 눈에 보이는 글자를 남기는 키만 문자열을 돌려주고, 삭제·이동·수정키 자체·단축키 조합은
 * null을 돌려준다.
 */
object ForwardedKeyObserver {

    private const val SHORTCUT_MODIFIER_MASK =
        KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK

    // Mirrors KeyEvent.isModifierKey(); reimplemented as a constant lookup because the real
    // method is stubbed out (throws) under the plain JVM unit-test android.jar.
    private val MODIFIER_KEY_CODES = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_NUM, KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK
    )

    fun printableText(keyCode: Int, unicodeChar: Int, metaState: Int): String? {
        if (keyCode in MODIFIER_KEY_CODES) return null
        if (metaState and SHORTCUT_MODIFIER_MASK != 0) return null
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return "\n"
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) return null
        if (unicodeChar == 0) return null
        val ch = unicodeChar.toChar()
        if (Character.isISOControl(ch)) return null
        return ch.toString()
    }
}
