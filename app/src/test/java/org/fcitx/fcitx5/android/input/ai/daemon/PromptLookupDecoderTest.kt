/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.daemon

import org.fcitx.fcitx5.android.input.ai.daemon.speculative.PromptLookupDecoder
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLookupDecoderTest {

    @Test
    fun testBasicNgramDraftExtraction() {
        val referenceContext = "새글 키보드는 대한민국에서 가장 빠르고 정확한 입력기입니다."
        val prompt = "새글 키보드는 대한민국에서 가장 빠르고"

        val result = PromptLookupDecoder.decode(
            prompt = prompt,
            referenceContext = referenceContext,
            maxTokens = 4,
            ngramSize = 2
        )

        assertTrue("Expected non-empty draft", result.isNotEmpty())
        assertTrue("Expected draft to contain '정확한', but was: '$result'", result.contains("정확한"))
        assertTrue("Draft must be grammatically sound", KoreanSyntaxRuleFilter.isGrammaticallySound(result, prompt))
    }

    @Test
    fun testJosaCorrectionInDraft() {
        // In the context, '사과' is mistakenly followed by '을' instead of '를'
        val referenceContext = "어제 사과을 맛있게 먹었습니다."
        val prompt = "어제 사과"

        val result = PromptLookupDecoder.decode(
            prompt = prompt,
            referenceContext = referenceContext,
            maxTokens = 3,
            ngramSize = 2
        )

        assertTrue("Expected non-empty draft", result.isNotEmpty())
        // Josa mismatch should be corrected from '을' to '를'
        assertFalse("Draft should not contain incorrect josa '을'", result.startsWith("을"))
        assertTrue("Draft must be grammatically sound", KoreanSyntaxRuleFilter.isGrammaticallySound(result, prompt))
    }

    @Test
    fun testNoMatchFallback() {
        val referenceContext = "인공지능 키보드 엔진 연구 개발"
        val prompt = "전혀 다른 외계어 쿼리 12345"

        val result = PromptLookupDecoder.decode(
            prompt = prompt,
            referenceContext = referenceContext,
            maxTokens = 5,
            ngramSize = 2
        )

        assertEquals("", result)
    }

    @Test
    fun testMaxTokensConstraint() {
        val referenceContext = "하나 둘 셋 넷 다섯 여섯 일곱 여덟 아홉 열"
        val prompt = "하나 둘"

        val result = PromptLookupDecoder.decode(
            prompt = prompt,
            referenceContext = referenceContext,
            maxTokens = 3,
            ngramSize = 1
        )

        assertTrue("Expected non-empty draft", result.isNotEmpty())
        val words = result.trim().split(Regex("\\s+"))
        assertTrue("Draft tokens count (${words.size}) must be <= maxTokens (3)", words.size <= 3)
    }
}
