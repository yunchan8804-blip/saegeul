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

class OnDeviceSuggestionPolicyTest {

    @Test
    fun `prompt JSON escapes multiline untrusted input without changing it`() {
        val input = input(
            text = "첫 줄\n\t\"인용\"\\끝\r다음",
            packageName = "com.example.notes"
        )

        val prompt = OnDeviceSuggestionPolicy.promptFor(input)

        assertTrue(prompt.contains("\\n\\t\\\"인용\\\"\\\\끝\\r다음"))
        assertFalse(prompt.contains("첫 줄\n\t\"인용\"\\끝\r다음"))
    }

    @Test
    fun `prompt reflects metadata and text changes`() {
        val first = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료", packageName = "com.example.mail", inputType = 1, imeAction = 2, mode = OnDeviceSuggestionPolicy.Mode.WORD))
        val sameInputSentence = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료", packageName = "com.example.mail", inputType = 1, imeAction = 2, mode = OnDeviceSuggestionPolicy.Mode.SENTENCE))
        val second = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 일정", packageName = "com.example.chat", inputType = 3, imeAction = 4))

        assertEquals(first, sameInputSentence)
        assertTrue(first.contains("\"app\":\"com.example.mail\""))
        assertTrue(first.contains("\"inputType\":1"))
        assertTrue(first.contains("\"imeAction\":2"))
        assertTrue(first.contains("현재 입력: \"회의 자료\""))
        assertTrue(first.contains("현재 입력 다음에 자연스럽게 이어져 문장을 끝맺는 짧은 한국어 글을 예측하세요."))
        assertFalse(first.contains("\"mode\""))
        assertTrue(second.contains("\"app\":\"com.example.chat\""))
        assertTrue(second.contains("\"inputType\":3"))
        assertTrue(second.contains("\"imeAction\":4"))
        assertTrue(second.contains("현재 입력: \"회의 일정\""))
        assertTrue(second.contains("현재 입력 다음에 자연스럽게 이어져 문장을 끝맺는 짧은 한국어 글을 예측하세요."))
        assertFalse(first.contains("회의 일정"))
    }

    @Test
    fun `prompt escapes quote bearing input as data`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "그가 \"내일\"이라고 말했다"))

        assertTrue(prompt.contains("\\\"내일\\\""))
        assertFalse(prompt.contains("현재 입력: \"그가 \"내일\""))
    }

    @Test
    fun `word parser projects the first eojeol and preserves attachment`() {
        assertEquals(" 일정", OnDeviceSuggestionPolicy.parseSuffix(input(text = "내일", mode = OnDeviceSuggestionPolicy.Mode.WORD), " 일정  "))
        assertEquals("입니다", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의", mode = OnDeviceSuggestionPolicy.Mode.WORD), "입니다"))
        assertEquals("입니다", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의 ", mode = OnDeviceSuggestionPolicy.Mode.WORD), "입니다"))
        assertEquals(" 다음", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의", mode = OnDeviceSuggestionPolicy.Mode.WORD), " 다음 일정입니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의 ", mode = OnDeviceSuggestionPolicy.Mode.WORD), " 일정"))
    }

    @Test
    fun `parser rejects repeated context endings and accepts new partial completion`() {
        val wordRequest = input(text = "내일 회의 준비", mode = OnDeviceSuggestionPolicy.Mode.WORD)
        val partialRequest = input(text = "보고서를 작성하", mode = OnDeviceSuggestionPolicy.Mode.WORD)

        assertNull(OnDeviceSuggestionPolicy.parseSuffix(wordRequest, "회의 준비"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(wordRequest, "준비"))
        assertEquals(" 시작", OnDeviceSuggestionPolicy.parseSuffix(wordRequest, " 시작"))
        assertEquals("겠습니다", OnDeviceSuggestionPolicy.parseSuffix(partialRequest, "겠습니다"))
        assertEquals("를", OnDeviceSuggestionPolicy.parseSuffix(input(text = "자료", mode = OnDeviceSuggestionPolicy.Mode.WORD), "를 공유합니다."))
    }

    @Test
    fun `sentence parser requires one terminal sentence`() {
        val request = input(text = "내일 회의는", mode = OnDeviceSuggestionPolicy.Mode.SENTENCE)

        assertEquals(" 오전에 시작합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다.  "))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "  오전에 시작합니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다. 다음 일정도 있습니다."))
    }

    @Test
    fun `parser strips echoed context and keeps only the continuation`() {
        val request = input(text = "회의 자료를")

        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "회의 자료를 공유합니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 회의 자료를 공유합니다."))
        assertEquals("공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의 자료를 "), "회의 자료를 공유합니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "내일 오전 회의 자료를"), "회의 자료를 공유합니다."))
        assertEquals("는 3시에 시작합니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "내일 오전 회의"), "회의는 3시에 시작합니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의 자료를", mode = OnDeviceSuggestionPolicy.Mode.WORD), "자료를 공유합니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "회의 자료를"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " 자료를 "))
        assertEquals(" 나 갑니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "지금 나"), " 나 갑니다."))
    }

    @Test
    fun `parser rejects copied source wrappers unsafe and oversized output`() {
        val request = input(text = "회의 자료를")

        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "` 공유합니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " 공유\u0000합니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의", mode = OnDeviceSuggestionPolicy.Mode.WORD), " 일정\u0000무시"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " " + "가".repeat(121) + "."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "가".repeat(4097)))
    }

    @Test
    fun `parser strips reply decorations and cleans the underlying candidate`() {
        val request = input(text = "그 순간")
        val expected = "방 안이 환해졌다."

        assertEquals(expected, OnDeviceSuggestionPolicy.parseSuffix(request, "NEXT: " + expected))
        assertEquals(expected, OnDeviceSuggestionPolicy.parseSuffix(request, "이어쓰기: " + expected))
        assertEquals(expected, OnDeviceSuggestionPolicy.parseSuffix(request, "[NEXT] " + expected))
        assertEquals(expected, OnDeviceSuggestionPolicy.parseSuffix(request, "<NEXT>" + expected))
        assertEquals(expected, OnDeviceSuggestionPolicy.parseSuffix(request, "- **" + expected + "**"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "NEXT"))

        val sentenceRequest = input(text = "내일 회의는", mode = OnDeviceSuggestionPolicy.Mode.SENTENCE)
        assertEquals(
            " 오전에 시작합니다.",
            OnDeviceSuggestionPolicy.parseSuffix(sentenceRequest, " 오전에 시작합니다.")
        )
    }

    @Test
    fun `parser cleans legacy label bullet and quote wrapped replies instead of rejecting`() {
        val request = input(text = "회의 자료를")

        assertEquals("공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "답변: 공유합니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "\" 공유합니다.\""))
        assertEquals("공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "* 공유합니다."))
    }

    @Test
    fun `invalid input is rejected and sensitive fields are redacted`() {
        val emptyPackage = input(text = "회의", packageName = "")
        val unsafeText = input(text = "회의\u200B")
        val oversizedText = input(text = "가".repeat(2049))
        val invalidPackage = input(text = "회의", packageName = "com.example-app")

        assertTrue(OnDeviceSuggestionPolicy.promptFor(emptyPackage).contains("\"app\":\"\""))
        assertFails { OnDeviceSuggestionPolicy.promptFor(unsafeText) }
        assertFails { OnDeviceSuggestionPolicy.promptFor(oversizedText) }
        assertFails { OnDeviceSuggestionPolicy.promptFor(invalidPackage) }
        assertFalse(emptyPackage.toString().contains("회의"))
        assertFalse(emptyPackage.toString().contains("com.example"))
        assertTrue(emptyPackage.toString().contains("<redacted>"))
    }

    @Test
    fun `prompt includes every optional section verbatim when all are populated`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "안녕",
                packageName = "com.kakao.talk",
                inputType = 1,
                imeAction = 2,
                appCategory = "messenger",
                fieldHint = "받는 사람",
                recentSentences = listOf("최근 문장1", "최근 문장2"),
                styleExamples = listOf("스타일 문장1")
            )
        )

        val expected = listOf(
            "{\"app\":\"com.kakao.talk\",\"appType\":\"메신저\",\"inputType\":1,\"imeAction\":2}",
            "[입력창 안내문] \"받는 사람\"",
            "[이 사용자가 이 앱에서 최근 보낸 문장]",
            "- \"최근 문장1\"",
            "- \"최근 문장2\"",
            "[이 사용자가 평소 쓴 비슷한 문장 — 말투와 표현만 참고하고 그대로 베끼지 마세요]",
            "- \"스타일 문장1\"",
            "현재 입력 다음에 자연스럽게 이어져 문장을 끝맺는 짧은 한국어 글을 예측하세요. 문장 하나만 완성하고 마침표나 물음표로 끝내세요.",
            "앞 문맥과 위 문장들의 흐름, 이 사용자의 말투를 따르세요. 이미 입력한 글은 되풀이하지 말고 새로 추가할 글자만 출력하세요. 설명이나 서식은 쓰지 마세요.",
            "원문이 공백으로 끝나면 추가 공백 없이, 그렇지 않고 새 어절을 시작하면 공백 하나로 시작하세요. 붙는 조사·어미는 공백 없이 쓰세요.",
            "현재 입력: \"안녕\"",
            "이어쓰기:"
        ).joinToString("\n")

        assertEquals(expected, prompt)
    }

    @Test
    fun `prompt omits empty optional sections line by line`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "안녕", packageName = "com.example.app"))

        val expected = listOf(
            "{\"app\":\"com.example.app\",\"appType\":\"일반\",\"inputType\":0,\"imeAction\":0}",
            "현재 입력 다음에 자연스럽게 이어져 문장을 끝맺는 짧은 한국어 글을 예측하세요. 문장 하나만 완성하고 마침표나 물음표로 끝내세요.",
            "앞 문맥과 위 문장들의 흐름, 이 사용자의 말투를 따르세요. 이미 입력한 글은 되풀이하지 말고 새로 추가할 글자만 출력하세요. 설명이나 서식은 쓰지 마세요.",
            "원문이 공백으로 끝나면 추가 공백 없이, 그렇지 않고 새 어절을 시작하면 공백 하나로 시작하세요. 붙는 조사·어미는 공백 없이 쓰세요.",
            "현재 입력: \"안녕\"",
            "이어쓰기:"
        ).joinToString("\n")

        assertEquals(expected, prompt)
        assertFalse(prompt.contains("입력창 안내문"))
        assertFalse(prompt.contains("최근 보낸 문장"))
        assertFalse(prompt.contains("평소 쓴 비슷한 문장"))
    }

    @Test
    fun `unknown app category falls back to the general label`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "안녕", appCategory = "does_not_exist"))

        assertTrue(prompt.contains("\"appType\":\"일반\""))
    }

    @Test
    fun `prompt escapes quotes and newlines inside field hint and sentence sections`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "안녕",
                fieldHint = "\"받는\n사람\"",
                recentSentences = listOf("최근 \"문장\""),
                styleExamples = listOf("여러\n줄 문장")
            )
        )

        assertTrue(prompt.contains("[입력창 안내문] \"\\\"받는\\n사람\\\"\""))
        assertTrue(prompt.contains("- \"최근 \\\"문장\\\"\""))
        assertTrue(prompt.contains("- \"여러\\n줄 문장\""))
        assertFalse(prompt.contains("\"받는\n사람\""))
    }

    @Test
    fun `promptFor defensively caps sentence lists to 3 items of 80 characters each`() {
        val longSentence = "가".repeat(120)
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "안녕",
                recentSentences = List(5) { index -> "$longSentence$index" }
            )
        )

        val bulletLines = prompt.lines().filter { it.startsWith("- \"") }
        assertEquals(3, bulletLines.size)
        bulletLines.forEach { line ->
            // "- \"" + content + "\"" -> content length is line length minus the 4 wrapper characters.
            assertTrue(line.length - 4 <= 80)
        }
    }

    @Test
    fun `toString redacts field hint and sentence contents but shows list sizes`() {
        val value = input(
            text = "안녕",
            fieldHint = "비밀 힌트",
            recentSentences = listOf("최근 문장1", "최근 문장2"),
            styleExamples = listOf("스타일 문장1")
        ).toString()

        assertFalse(value.contains("비밀 힌트"))
        assertFalse(value.contains("최근 문장1"))
        assertFalse(value.contains("스타일 문장1"))
        assertTrue(value.contains("recentSentences=<redacted:2>"))
        assertTrue(value.contains("styleExamples=<redacted:1>"))
        assertTrue(value.contains("fieldHint=<redacted>"))
    }

    private fun input(
        text: String,
        packageName: String = "com.example.app",
        inputType: Int = 0,
        imeAction: Int = 0,
        mode: OnDeviceSuggestionPolicy.Mode = OnDeviceSuggestionPolicy.Mode.SENTENCE,
        appCategory: String = "general",
        fieldHint: String? = null,
        recentSentences: List<String> = emptyList(),
        styleExamples: List<String> = emptyList()
    ) = OnDeviceSuggestionPolicy.Input(
        textBeforeCursor = text,
        packageName = packageName,
        inputType = inputType,
        imeAction = imeAction,
        mode = mode,
        appCategory = appCategory,
        fieldHint = fieldHint,
        recentSentences = recentSentences,
        styleExamples = styleExamples
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
