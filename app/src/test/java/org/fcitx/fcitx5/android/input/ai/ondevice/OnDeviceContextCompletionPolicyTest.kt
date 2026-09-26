/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceContextCompletionPolicyTest {

    @Test
    fun `unfinished context prompt preserves the exact original text`() {
        val context = "회의 자료를 오늘 안에 "

        val prompt = OnDeviceContextCompletionPolicy.promptFor(context)

        assertTrue(OnDeviceContextCompletionPolicy.isIncompleteContext(context))
        assertTrue(prompt.endsWith(context))
        assertTrue(prompt.contains("원문 전체와 추가한 접미부만"))
    }

    @Test
    fun `completed context and leading whitespace are rejected`() {
        assertFalse(OnDeviceContextCompletionPolicy.isIncompleteContext("보고서를 보냈어요. "))
        assertFalse(OnDeviceContextCompletionPolicy.isIncompleteContext(" 오늘 회의는"))
        assertFalse(OnDeviceContextCompletionPolicy.isIncompleteContext("  \t"))
        assertFalse(OnDeviceContextCompletionPolicy.isIncompleteContext("가".repeat(2049)))
        assertTrue(OnDeviceContextCompletionPolicy.isIncompleteContext("가".repeat(2048)))
    }

    @Test
    fun `parser preserves a leading suffix space for exact concatenation`() {
        val context = "비가 올 것 같아서"
        val suffix = " 우산을 챙겼어요."

        assertEquals(suffix, OnDeviceContextCompletionPolicy.parseCompletion(context, context + suffix))
        assertEquals(suffix, OnDeviceContextCompletionPolicy.parseCompletion(context, "\n$context$suffix\n"))
    }

    @Test
    fun `parser rejects altered repeated empty and complete responses`() {
        val context = "주말에 도서관에 가서"

        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "주말에 도서관에서 책을 읽었어요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, context + context + " 책을 읽었어요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, context))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "$context 책을 읽었어요"))
    }

    @Test
    fun `parser rejects wrappers line breaks and overlong suffixes`() {
        val context = "내일 아침에는"

        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "\"$context 일찍 출발할게요.\""))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "$context\n 일찍 출발할게요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, context + " " + "가".repeat(121) + "."))
    }

    @Test
    fun `parser rejects control format and unbounded raw input`() {
        val context = "회의가 끝나면"

        assertFalse(OnDeviceContextCompletionPolicy.isIncompleteContext("회의\u200B가 끝나면"))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "$context\u0000 알려주세요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "$context\u200F 알려주세요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, "$context\n 알려주세요."))
        assertNull(OnDeviceContextCompletionPolicy.parseCompletion(context, " ".repeat(4097)))
    }

    @Test
    fun `policy result string does not reveal a completion`() {
        val result = OnDeviceContextCompletionResult(" 비공개 응답입니다.", 3L, 11L, true)

        assertFalse(result.toString().contains(result.suffix))
    }
}
