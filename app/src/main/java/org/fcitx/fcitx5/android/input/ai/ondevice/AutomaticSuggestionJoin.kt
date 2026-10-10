/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * 자동 추천 접미부를 쓰는 중인 글 뒤에 이어 붙이는 방식을 정하는 순수 판정.
 * 접미부가 `,` `.` `?` `!` `…` `~` `)` `]` 같은 문장부호로 시작하면 앞 어절에 붙어야 하므로,
 * 입력 끝에 남은 공백은 지우고 붙인다(`해 보니 ` + `, 현재는` → `해 보니, 현재는`).
 */
internal object AutomaticSuggestionJoin {

    private const val ATTACHING_MARKS = ",.?!…~)]"

    /** 커서 앞에서 지워야 할 공백 수. 지울 것이 없으면 0이다. */
    fun trailingSpacesToRemove(textBeforeCursor: String, suffix: String): Int {
        val first = suffix.firstOrNull() ?: return 0
        if (first !in ATTACHING_MARKS) return 0
        return textBeforeCursor.length - textBeforeCursor.trimEnd(' ').length
    }

    /** [suffix]를 적용한 뒤의 글. */
    fun joined(textBeforeCursor: String, suffix: String): String {
        val spaces = trailingSpacesToRemove(textBeforeCursor, suffix)
        return textBeforeCursor.dropLast(spaces) + suffix
    }
}
