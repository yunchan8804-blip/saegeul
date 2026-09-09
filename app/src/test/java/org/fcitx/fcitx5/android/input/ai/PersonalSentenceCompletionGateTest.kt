/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSentenceCompletionGateTest {

    @Test
    fun acceptsLongerWhitespaceNormalizedCurrentSentencePrefix() {
        assertTrue(PersonalSentenceCompletionGate.isContinuation("오늘  회의 ", "오늘 회의 참석합니다"))
    }

    @Test
    fun usesOnlySentenceAfterLastBoundary() {
        assertTrue(PersonalSentenceCompletionGate.isContinuation("이전 문장. 오늘 회의", "오늘 회의 참석합니다"))
    }

    @Test
    fun rejectsSameSentenceAndNonPrefixMatches() {
        assertFalse(PersonalSentenceCompletionGate.isContinuation("오늘 회의", "오늘 회의"))
        assertFalse(PersonalSentenceCompletionGate.isContinuation("내가 뭘", "오늘 내가 뭘 회의 참석합니다"))
    }
}
