/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * K7 (design.md, 사용자 승인 2026-09-26): 펼친 화면(Expanded 프로필)에서 분할 키보드가 꺼진 채
 * 키보드가 뜨면, idle 툴바 줄(48dp) 자리에 분할 키보드 안내를 한 번만 보여준다. 어느 버튼이든
 * 누르면 다시 보이지 않는다.
 *
 * Pure decision logic (no Android dependency) so it is unit-testable without Robolectric. See
 * [org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent].
 */
internal object SplitPromptPolicy {

    /**
     * @param expandedProfile 현재 키보드 화면 프로필이 Expanded인지
     * @param splitEnabled `split_keyboard_expanded` 설정값
     * @param promptDone `split_expanded_prompt_done` 설정값
     * @param toolbarIdle 툴바가 idle 상태(입력 전, 후보 없음, 툴바 접힘)인지
     */
    fun shouldShow(
        expandedProfile: Boolean,
        splitEnabled: Boolean,
        promptDone: Boolean,
        toolbarIdle: Boolean
    ): Boolean = expandedProfile && !splitEnabled && !promptDone && toolbarIdle
}
