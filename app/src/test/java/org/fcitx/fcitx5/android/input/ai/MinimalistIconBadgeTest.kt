/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD Tests verifying that candidate badges use small minimalist icons
 * instead of bulky Korean text labels like '업무/보고' or '일정'.
 */
class MinimalistIconBadgeTest {

    @Test
    fun `category icon mapping should produce clean single icons instead of text labels`() {
        val workIcon = CandidateItemUi.resolveBadgeIcon("업무/보고")
        assertEquals("💼", workIcon)

        val scheduleIcon = CandidateItemUi.resolveBadgeIcon("일정")
        assertEquals("📅", scheduleIcon)

        val delayIcon = CandidateItemUi.resolveBadgeIcon("양해/안심")
        assertEquals("⏳", delayIcon)

        val thanksIcon = CandidateItemUi.resolveBadgeIcon("감사")
        assertEquals("🙏", thanksIcon)

        val proposalIcon = CandidateItemUi.resolveBadgeIcon("제안")
        assertEquals("💡", proposalIcon)

        val aiIcon = CandidateItemUi.resolveBadgeIcon("✨ 맞춤AI")
        assertEquals("✨", aiIcon)

        val myStyleIcon = CandidateItemUi.resolveBadgeIcon("✨ 내스타일")
        assertEquals("✨", myStyleIcon)

        val chatIcon = CandidateItemUi.resolveBadgeIcon("답변")
        assertEquals("💬", chatIcon)
    }

    @Test
    fun `resolved badge icon should NEVER contain Korean letters`() {
        val testLabels = listOf(
            "업무/보고", "일정", "양해/안심", "감사", "제안", "답변", "일상", "핵심구문",
            "✨ 맞춤AI", "✨ 내스타일", "비즈니스", "개발/IT", "존댓말", "친근"
        )

        testLabels.forEach { label ->
            val icon = CandidateItemUi.resolveBadgeIcon(label)
            val hasKorean = icon.any { it in '\uAC00'..'\uD7A3' }
            assertFalse("Badge icon for '$label' must not contain Korean letters, was '$icon'", hasKorean)
            assertTrue("Badge icon must not be blank", icon.isNotBlank())
        }
    }
}
