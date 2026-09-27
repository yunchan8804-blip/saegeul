/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K7 (design.md, 사용자 승인 2026-09-26): 펼친 화면에서 분할이 꺼져 있고, 안내를 아직 닫지
 * 않았고, 툴바가 idle일 때만 분할 키보드 안내를 보여준다.
 */
class SplitPromptPolicyTest {

    @Test
    fun `shows when expanded, split off, prompt not done, and toolbar idle`() {
        assertTrue(
            SplitPromptPolicy.shouldShow(
                expandedProfile = true,
                splitEnabled = false,
                promptDone = false,
                toolbarIdle = true
            )
        )
    }

    @Test
    fun `hides when not expanded profile`() {
        assertFalse(
            SplitPromptPolicy.shouldShow(
                expandedProfile = false,
                splitEnabled = false,
                promptDone = false,
                toolbarIdle = true
            )
        )
    }

    @Test
    fun `hides when split already enabled`() {
        assertFalse(
            SplitPromptPolicy.shouldShow(
                expandedProfile = true,
                splitEnabled = true,
                promptDone = false,
                toolbarIdle = true
            )
        )
    }

    @Test
    fun `hides once the prompt was already dismissed`() {
        assertFalse(
            SplitPromptPolicy.shouldShow(
                expandedProfile = true,
                splitEnabled = false,
                promptDone = true,
                toolbarIdle = true
            )
        )
    }

    @Test
    fun `hides while the toolbar is not idle`() {
        assertFalse(
            SplitPromptPolicy.shouldShow(
                expandedProfile = true,
                splitEnabled = false,
                promptDone = false,
                toolbarIdle = false
            )
        )
    }
}
