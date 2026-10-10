/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlin.text.CharCategory.CONTROL
import kotlin.text.CharCategory.FORMAT

/**
 * 오타를 고쳐 다시 쓴 자동 추천(REPLACE)을 누른 순간에 편집기 글과 맞춰 보는 순수 판정.
 * 저장해 둔 원문이 커서 앞 글의 끝과 같을 때만 그 범위를 바꾼다. 다르면(그 사이 더 쳤거나 지웠으면) 낡은 후보다.
 */
internal object AutomaticSuggestionReplacement {

    class Range(val start: Int, val end: Int)

    /** [textBeforeCursor]가 아직 [replaces]로 끝나면 바꿀 범위, 아니면 null. */
    fun rangeToReplace(textBeforeCursor: String, replaces: String): Range? {
        if (replaces.isEmpty() || !textBeforeCursor.endsWith(replaces)) return null
        return Range(textBeforeCursor.length - replaces.length, textBeforeCursor.length)
    }

    fun isReplacementSafe(replacement: String): Boolean =
        replacement.isNotBlank() && replacement.none { it.category == CONTROL || it.category == FORMAT }
}
