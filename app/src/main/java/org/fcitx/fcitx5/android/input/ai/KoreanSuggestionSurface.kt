/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/** 추천 출력 텍스트의 표면 검증. */
object KoreanSuggestionSurface {

    private val allowedLaughterJamo = setOf('ㅋ', 'ㅎ', 'ㅠ', 'ㅜ')

    fun isDisplayable(text: String): Boolean = text.isNotBlank() && text.none { character ->
        character in '\u3131'..'\u3163' && character !in allowedLaughterJamo
    }
}
