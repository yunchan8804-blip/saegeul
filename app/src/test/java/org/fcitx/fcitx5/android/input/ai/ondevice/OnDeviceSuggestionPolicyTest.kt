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
    fun `multiline untrusted text cannot add or break prompt lines`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "첫 줄\n둘째\t줄 \"인용\"\\끝\r다음\t글자",
                appCategory = "messenger",
                recentSentences = listOf("최근\n문장")
            )
        )

        assertEquals(
            listOf(
                "앱: 메신저",
                "앞 내용: 첫 줄 둘째 줄 \"인용\"\\끝",
                COMPLETE_INSTRUCTION,
                "쓰는 중인 문장: 다음 글자"
            ),
            prompt.lines()
        )
        assertFalse(prompt.contains("\t"))
        assertFalse(prompt.contains("\r"))
    }

    @Test
    fun `prompt differs by text and app category and ignores mode`() {
        val first = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료", packageName = "com.example.mail", appCategory = "email", mode = OnDeviceSuggestionPolicy.Mode.WORD))
        val sameInputSentence = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료", packageName = "com.example.mail", appCategory = "email", mode = OnDeviceSuggestionPolicy.Mode.SENTENCE))
        val second = OnDeviceSuggestionPolicy.promptFor(input(text = "회의 일정", packageName = "com.example.chat", appCategory = "messenger"))

        assertEquals(first, sameInputSentence)
        assertTrue(first.contains("앱: 메일"))
        assertTrue(first.contains("쓰는 중인 문장: 회의 자료"))
        assertTrue(first.contains(COMPLETE_INSTRUCTION))
        assertTrue(second.contains("앱: 메신저"))
        assertTrue(second.contains("쓰는 중인 문장: 회의 일정"))
        assertFalse(first.contains("회의 일정"))
        assertFalse(first.contains("com.example"))
    }

    @Test
    fun `prompt keeps quotes in the current sentence verbatim as plain text`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "그가 \"내일\"이라고 말했다"))

        assertTrue(prompt.contains("쓰는 중인 문장: 그가 \"내일\"이라고 말했다"))
    }

    @Test
    fun `sentence split puts completed sentences before the sentence being typed`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(text = "내일 회의 시간이 3시로 바뀌었어요. 장소는 ", appCategory = "messenger")
        )

        assertEquals(
            listOf(
                "앱: 메신저",
                "말투: 존댓말",
                "앞 내용: 내일 회의 시간이 3시로 바뀌었어요.",
                COMPLETE_INSTRUCTION,
                "쓰는 중인 문장: 장소는"
            ),
            prompt.lines()
        )
    }

    @Test
    fun `sentence split without a current sentence asks for the next sentence`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "점심 맛있게 드세요. "))

        assertEquals(
            listOf("말투: 존댓말", "앞 내용: 점심 맛있게 드세요.", NEXT_INSTRUCTION),
            prompt.lines()
        )
        assertFalse(prompt.contains("쓰는 중인 문장:"))
        assertFalse(prompt.contains(COMPLETE_INSTRUCTION))
    }

    @Test
    fun `sentence split treats a newline as a boundary and keeps decimals together`() {
        val newline = OnDeviceSuggestionPolicy.promptFor(input(text = "첫째 줄\n둘째 줄 이어서"))
        val decimal = OnDeviceSuggestionPolicy.promptFor(input(text = "배율은 3.5배로 하고"))

        assertTrue(newline.contains("앞 내용: 첫째 줄\n"))
        assertTrue(newline.contains("쓰는 중인 문장: 둘째 줄 이어서"))
        assertFalse(decimal.contains("앞 내용:"))
        assertTrue(decimal.contains("쓰는 중인 문장: 배율은 3.5배로 하고"))
    }

    @Test
    fun `current sentence over 60 characters moves its leading eojeols into the previous text`() {
        val words = (1..14).map { "어절%02d".format(it) }
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = words.joinToString(" ") + " "))

        assertTrue(prompt.contains("앞 내용: 어절01 어절02\n"))
        assertTrue(prompt.endsWith("쓰는 중인 문장: " + words.drop(2).joinToString(" ")))
    }

    @Test
    fun `current sentence over 60 characters without a space boundary is not cut`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "가".repeat(70)))

        assertFalse(prompt.contains("앞 내용:"))
        assertTrue(prompt.endsWith("쓰는 중인 문장: " + "가".repeat(70)))
    }

    @Test
    fun `current sentence without any space stays under the prompt budget and keeps its tail`() {
        val text = "가".repeat(299) + "끝"
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = text))

        assertTrue("length=${prompt.length}", prompt.length <= 190)
        val current = prompt.lines().last()
        assertTrue(current.startsWith("쓰는 중인 문장: "))
        assertTrue(current.endsWith("가가끝"))
        assertTrue(current.length > "쓰는 중인 문장: ".length + 70)
    }

    @Test
    fun `long current sentence with spaces only early on is cut at a word boundary within the budget`() {
        val spaced = (1..10).joinToString(" ") { "어절%02d".format(it) }
        val text = spaced + " " + "나".repeat(90) + "끝"
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = text))

        assertTrue("length=${prompt.length}", prompt.length <= 190)
        assertTrue(prompt.lines().last().endsWith("나끝"))
    }

    @Test
    fun `truncating a long current sentence does not split a surrogate pair`() {
        val pair = "😀"
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = pair.repeat(150)))

        assertTrue("length=${prompt.length}", prompt.length <= 190)
        val current = prompt.lines().last().removePrefix("쓰는 중인 문장: ")
        assertFalse(current.first().isLowSurrogate())
        assertTrue(current.endsWith(pair))
    }

    @Test
    fun `reply that echoes the shown part of a truncated current sentence is reduced to its continuation`() {
        val text = "가".repeat(300)
        val shown = OnDeviceSuggestionPolicy.promptFor(input(text = text)).lines().last()
            .removePrefix("쓰는 중인 문장: ")

        assertEquals(" 이어서 씁니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = text), "$shown 이어서 씁니다."))
    }

    @Test
    fun `tone is polite when completed sentences end politely`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "안녕하세요. 내일 일정 ", appCategory = "messenger"))

        assertTrue(prompt.contains("말투: 존댓말"))
    }

    @Test
    fun `tone is casual when completed sentences end casually`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "야 오늘 뭐해? 나는 ", appCategory = "messenger"))

        assertTrue(prompt.contains("말투: 반말"))
    }

    @Test
    fun `tone counts recent sent sentences and honorific markers in the typed text`() {
        val casual = OnDeviceSuggestionPolicy.promptFor(
            input(text = "회의 자료 ", recentSentences = listOf("응 좋아 그때 보자", "그래 알았어~"))
        )
        val honorific = OnDeviceSuggestionPolicy.promptFor(input(text = "요청하신 자료는 내일까지 정리해서 "))

        assertTrue(casual.contains("말투: 반말"))
        assertTrue(honorific.contains("말투: 존댓말"))
    }

    @Test
    fun `tone tie falls back to polite for work and email and to none otherwise`() {
        val text = "안녕하세요. 뭐해? 나는 "

        assertTrue(OnDeviceSuggestionPolicy.promptFor(input(text = text, appCategory = "work")).contains("말투: 존댓말"))
        assertTrue(OnDeviceSuggestionPolicy.promptFor(input(text = text, appCategory = "email")).contains("말투: 존댓말"))
        assertFalse(OnDeviceSuggestionPolicy.promptFor(input(text = text, appCategory = "messenger")).contains("말투:"))
        assertFalse(OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료 ", appCategory = "messenger")).contains("말투:"))
        assertTrue(OnDeviceSuggestionPolicy.promptFor(input(text = "회의 자료 ", appCategory = "work")).contains("말투: 존댓말"))
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
    fun `sentence parser keeps the first sentence and accepts a hangul ending without a period`() {
        val request = input(text = "내일 회의는", mode = OnDeviceSuggestionPolicy.Mode.SENTENCE)

        assertEquals(" 오전에 시작합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다.  "))
        assertEquals(" 오전에 시작합니다", OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다"))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "  오전에 시작합니다."))
        assertEquals(" 오전에 시작합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작합니다. 다음 일정도 있습니다."))
        assertEquals(" 오전에 시작해요?!", OnDeviceSuggestionPolicy.parseSuffix(request, " 오전에 시작해요?! 그래요"))
    }

    @Test
    fun `sentence parser rejects endings that are neither terminal marks nor hangul syllables`() {
        val request = input(text = "내일 회의는")

        assertEquals("NOT_SINGLE_SENTENCE", OnDeviceSuggestionPolicy.rejectionReason(request, " 오전에 시작하고,"))
        assertEquals("NOT_SINGLE_SENTENCE", OnDeviceSuggestionPolicy.rejectionReason(request, " 오전에 시작해 ㅋㅋ"))
        assertEquals("NOT_SINGLE_SENTENCE", OnDeviceSuggestionPolicy.rejectionReason(request, " 3시에 시작하는 걸로 2"))
    }

    @Test
    fun `parser removes a restated whole sentence of five or more eojeols`() {
        val sentence = "내일 오전 열 시에 회의가 있어서"

        assertEquals(
            "일찍 나가야 해요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = "$sentence "), "$sentence 일찍 나가야 해요.")
        )
        assertEquals(
            " 일찍 나가야 해요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = sentence), "$sentence 일찍 나가야 해요.")
        )
        assertEquals(
            "강남역 3번 출구 앞이에요.",
            OnDeviceSuggestionPolicy.parseSuffix(
                input(text = "내일 회의 시간이 3시로 바뀌었어요. 장소는 "),
                "장소는 강남역 3번 출구 앞이에요."
            )
        )
        assertEquals("ECHO_ONLY", OnDeviceSuggestionPolicy.rejectionReason(input(text = "$sentence "), sentence))
    }

    @Test
    fun `parser removes the restated shown part of a long current sentence`() {
        val words = (1..14).map { "어절%02d".format(it) }
        val shown = words.drop(2).joinToString(" ")

        assertEquals(
            "마무리했어요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = words.joinToString(" ") + " "), "$shown 마무리했어요.")
        )
    }

    @Test
    fun `parser completes a partial eojeol by attaching the rest without a space`() {
        val request = input(text = "비 온다는데 우산 챙겼")

        assertEquals("어.", OnDeviceSuggestionPolicy.parseSuffix(request, "비 온다는데 우산 챙겼어."))
        assertEquals("어?", OnDeviceSuggestionPolicy.parseSuffix(request, "비 온다는데 우산 챙겼어?"))
    }

    @Test
    fun `parser normalizes the spacing between the restated text and the remainder`() {
        assertEquals(" 우산 챙겼어.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "비 온다는데"), "비 온다는데  우산 챙겼어."))
        assertEquals("우산 챙겼어.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "비 온다는데 "), "비 온다는데   우산 챙겼어."))
    }

    @Test
    fun `parser removes quotes and bold marks that wrap the remainder`() {
        val request = input(text = "그가 ")

        assertEquals("내일 온대.", OnDeviceSuggestionPolicy.parseSuffix(request, "그가 \"내일 온대.\""))
        assertEquals("내일 온대.", OnDeviceSuggestionPolicy.parseSuffix(request, "**그가 내일 온대.**"))
        assertEquals("내일 온대.", OnDeviceSuggestionPolicy.parseSuffix(request, "그가 **내일** 온대."))
        assertEquals("내일 온대.", OnDeviceSuggestionPolicy.parseSuffix(request, "그가 “내일 온대.”"))
        assertEquals(" 내일 온대.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "그가"), "그가 '내일 온대.'"))
    }

    @Test
    fun `parser rejects a reply that only repeats the previous sentence`() {
        val request = input(text = "내일 회의 시간이 3시로 바뀌었어요. 장소는 ")

        assertEquals("REPEATS_PREV", OnDeviceSuggestionPolicy.rejectionReason(request, "내일 회의 시간이 3시로 바뀌었어요."))
        assertEquals("REPEATS_PREV", OnDeviceSuggestionPolicy.rejectionReason(request, "바뀌었어요."))
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(request, "강남역 앞이에요."))
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(input(text = "내일 회의는 3시예요. 장소는 "), "3시에요."))
    }

    @Test
    fun `parser rejects a reply that restates the previous sentence with small wording changes`() {
        val request = input(text = "엄마 나 오늘 좀 늦을 것 같아. 저녁은 ")

        assertEquals("REPEATS_PREV", OnDeviceSuggestionPolicy.rejectionReason(request, "저녁은 조금 늦을 것 같아."))
        assertEquals("REPEATS_PREV", OnDeviceSuggestionPolicy.rejectionReason(request, "조금 늦을 것 같아."))
    }

    @Test
    fun `parser keeps a reply that shares only a few words with the previous sentence`() {
        val request = input(text = "내일 회의 시간이 3시로 바뀌었어요. 장소는 ")

        assertNull(OnDeviceSuggestionPolicy.rejectionReason(request, "장소는 회의실 그대로예요."))
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
    fun `parser removes backticks and rejects unsafe stray marks and oversized output`() {
        val request = input(text = "회의 자료를")

        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "` 공유합니다."))
        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, " 공유*합니다."))
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
    fun `parser rejects letters of a writing system absent from the typed text`() {
        assertEquals(
            "FOREIGN_SCRIPT",
            OnDeviceSuggestionPolicy.rejectionReason(input(text = "배포 중에는 "), "배포 중에는 서비스 접속이 chậm해질 수 있습니다.")
        )
        assertEquals(
            "FOREIGN_SCRIPT",
            OnDeviceSuggestionPolicy.rejectionReason(input(text = "올 한 해도 "), "올 한 해도 좋은 일များ 가득하길!")
        )
        assertEquals(
            "FOREIGN_SCRIPT",
            OnDeviceSuggestionPolicy.rejectionReason(input(text = "배포 중에는 "), "배포 중에는 이용ে 불편함이 없도록 하겠습니다.")
        )
        assertEquals(
            "FOREIGN_SCRIPT",
            OnDeviceSuggestionPolicy.rejectionReason(input(text = "배포 때문에 "), "slight한 지연이 있어요.")
        )
    }

    @Test
    fun `parser keeps a writing system that the typed text already uses and ignores digits`() {
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(input(text = "PR 리뷰 끝나면 "), "PR 머지할게요."))
        assertEquals("3시에 봬요.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "내일 "), "3시에 봬요."))
    }

    @Test
    fun `invalid input is rejected and sensitive fields are redacted`() {
        val emptyPackage = input(text = "회의", packageName = "")
        val unsafeText = input(text = "회의\u200B")
        val oversizedText = input(text = "가".repeat(2049))
        val invalidPackage = input(text = "회의", packageName = "com.example-app")

        assertTrue(OnDeviceSuggestionPolicy.promptFor(emptyPackage).contains("쓰는 중인 문장: 회의"))
        assertFails { OnDeviceSuggestionPolicy.promptFor(unsafeText) }
        assertFails { OnDeviceSuggestionPolicy.promptFor(oversizedText) }
        assertFails { OnDeviceSuggestionPolicy.promptFor(invalidPackage) }
        assertFalse(emptyPackage.toString().contains("회의"))
        assertFalse(emptyPackage.toString().contains("com.example"))
        assertTrue(emptyPackage.toString().contains("<redacted>"))
    }

    @Test
    fun `prompt includes every section in line order when all are populated`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "내일 뵙겠습니다. 안녕",
                packageName = "com.kakao.talk",
                inputType = 1,
                imeAction = 2,
                appCategory = "messenger",
                recentSentences = listOf("확인했습니다.", "감사합니다.")
            )
        )

        val expected = listOf(
            "앱: 메신저",
            "말투: 존댓말",
            "앞 내용: 내일 뵙겠습니다.",
            COMPLETE_INSTRUCTION,
            "쓰는 중인 문장: 안녕"
        ).joinToString("\n")

        assertEquals(expected, prompt)
    }

    @Test
    fun `prompt omits empty optional sections line by line`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "안녕", packageName = "com.example.app"))

        assertEquals(listOf(COMPLETE_INSTRUCTION, "쓰는 중인 문장: 안녕").joinToString("\n"), prompt)
        assertFalse(prompt.contains("앱:"))
        assertFalse(prompt.contains("말투:"))
        assertFalse(prompt.contains("앞 내용:"))
    }

    @Test
    fun `blank optional sections are omitted`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(text = "안녕", recentSentences = listOf(" ", "\n"))
        )

        assertEquals(listOf(COMPLETE_INSTRUCTION, "쓰는 중인 문장: 안녕").joinToString("\n"), prompt)
    }

    @Test
    fun `unknown app category omits the app line`() {
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "안녕", appCategory = "does_not_exist"))

        assertFalse(prompt.contains("앱:"))
    }

    @Test
    fun `prompt never includes the removed input field and similar sentence lines or recent sentences`() {
        val long = "가나다라마바사아자차카타파하".repeat(4)
        val inputs = listOf(
            input(text = "안녕하세여", appCategory = "messenger"),
            input(text = "안녕하세요", appCategory = "messenger", recentSentences = List(5) { index -> "$index$long" }),
            input(text = "회의 자료 공유 부탁합니다. 장소는 ", appCategory = "work")
        )

        inputs.forEach { request ->
            val prompt = OnDeviceSuggestionPolicy.promptFor(request)
            assertFalse(prompt.contains("입력창"))
            assertFalse(prompt.contains("비슷한 글"))
            assertFalse(prompt.contains("최근 보낸 글"))
            request.recentSentences.forEach { assertFalse(prompt.contains(it.take(10))) }
        }
    }

    @Test
    fun `prompt never exceeds the character budget even with every section populated`() {
        val long = "가나다라마바사아자차카타파하 ".repeat(20)
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "$long. $long. 오늘 회의 자료를 미리 정리해서 공유드리려고 하는데 ",
                appCategory = "work",
                recentSentences = listOf(long, long, long)
            )
        )

        assertTrue("length=${prompt.length}", prompt.length <= 190)
        assertTrue(prompt.endsWith("쓰는 중인 문장: 오늘 회의 자료를 미리 정리해서 공유드리려고 하는데"))
        assertTrue(prompt.contains("말투: 존댓말"))
    }

    @Test
    fun `app and tone lines are kept and the previous text takes the rest of the budget`() {
        val current = "가나다라 ".repeat(12).trim()
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(
                text = "회의 자료 공유 부탁합니다. ".repeat(6) + current + " ",
                appCategory = "work",
                recentSentences = listOf("최근 문장1", "최근 문장2")
            )
        )

        val lines = prompt.lines()
        assertTrue("length=${prompt.length}", prompt.length <= 190)
        assertTrue(lines.contains("말투: 존댓말"))
        assertTrue(lines.contains("앱: 업무 메신저"))
        val prev = lines.single { it.startsWith("앞 내용: ") }
        assertTrue(prev.startsWith("앞 내용: …"))
        assertTrue(prev.length - "앞 내용: …".length >= 10)
    }

    @Test
    fun `recent sentences shape the tone line but their text never reaches the prompt`() {
        val recent = listOf("다음 주 화요일 저녁 7시 강남역 어때?", "응 좋아 그때 보자")
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(text = "혹시 장소 바뀌면 ", appCategory = "messenger", recentSentences = recent)
        )

        assertEquals(
            listOf("앱: 메신저", "말투: 반말", COMPLETE_INSTRUCTION, "쓰는 중인 문장: 혹시 장소 바뀌면"),
            prompt.lines()
        )
        recent.forEach { assertFalse(prompt.contains(it)) }
        assertFalse(prompt.contains("최근 보낸 글"))
    }

    @Test
    fun `previous text is dropped when it cannot be cut at a space into 10 or more characters`() {
        val current = "가나다라 ".repeat(12).trim()
        val prompt = OnDeviceSuggestionPolicy.promptFor(
            input(text = "${"가".repeat(100)}. $current ", appCategory = "work")
        )

        assertFalse(prompt.contains("앞 내용:"))
        assertTrue(prompt.contains("말투: 존댓말"))
    }

    @Test
    fun `long previous text is cut at a space with an ellipsis and fills the budget left`() {
        val words = (1..40).joinToString(" ") { "문장%02d".format(it) }
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "$words. 안녕"))

        val prev = prompt.lines().single { it.startsWith("앞 내용: ") }
        val shownPrev = prev.length - "앞 내용: ".length
        assertTrue(prev.startsWith("앞 내용: …문장"))
        assertTrue(prev.endsWith("문장40."))
        assertTrue("length=${prompt.length}", prompt.length <= 190)
        // The tail is cut at a space, so at most one word (and its separator) of the budget is left over.
        assertTrue("length=${prompt.length}", prompt.length >= 190 - "문장00 ".length)
        assertTrue("shownPrev=$shownPrev", shownPrev > 80)
    }

    @Test
    fun `previous text shown is longer than the old 80 character cap for the same input`() {
        val words = (1..40).joinToString(" ") { "단어%02d".format(it) }
        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "$words. 오늘 회의는 ", appCategory = "messenger"))

        val prev = prompt.lines().single { it.startsWith("앞 내용: ") }.removePrefix("앞 내용: …")
        assertTrue("prev=${prev.length}", prev.length > 80)
        assertTrue("length=${prompt.length}", prompt.length <= 190)
    }

    @Test
    fun `toString redacts sentence contents but shows list sizes`() {
        val value = input(
            text = "안녕",
            recentSentences = listOf("최근 문장1", "최근 문장2")
        ).toString()

        assertFalse(value.contains("안녕"))
        assertFalse(value.contains("최근 문장1"))
        assertTrue(value.contains("recentSentences=<redacted:2>"))
    }

    private val COMPLETE_INSTRUCTION = "쓰는 중인 문장을 앞 내용에 맞게 완성한 문장 하나만 쓰세요. 앞 내용은 되풀이하지 마세요."
    private val NEXT_INSTRUCTION = "앞 내용에 자연스럽게 이어질 다음 문장 하나만 쓰세요. 앞 내용은 되풀이하지 마세요."

    @Test
    fun `a violation in the typed text does not reject a sound suffix but one in the suffix does`() {
        val typed = input(text = "공부하했어요. 내일 회의 자료는 ")

        val sound = OnDeviceSuggestionPolicy.parseForStorage(typed, "내일 회의 자료는 오후에 공유드릴게요.")
        assertEquals("오후에 공유드릴게요.", sound.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.SENTENCE, sound.mode)
        assertNull(sound.sentenceRejection)

        val broken = OnDeviceSuggestionPolicy.parseForStorage(typed, "내일 회의 자료는 오후에 준비하했어요.")
        assertEquals("GATE_UNGRAMMATICAL", broken.sentenceRejection)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, broken.mode)
    }

    @Test
    fun `parser rejects pictographs absent from the typed text`() {
        assertEquals(
            "FOREIGN_SCRIPT",
            OnDeviceSuggestionPolicy.rejectionReason(
                input(text = "배송 조회를 해 보니 "),
                "배송 조회를 해 보니 현재 상품이 발🚚 중입니다."
            )
        )
        assertEquals(
            "다음에 보자 😊",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = "좋아 😊 "), "좋아 😊 다음에 보자 😊")
        )
    }

    @Test
    fun `parser uses only the first non-blank line of a multi-line reply`() {
        val request = input(text = "회의 자료를")

        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 공유합니다.\n그리고 다음 줄입니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "\n\n 공유합니다.\r\n둘째 줄입니다."))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, "  \n 공유합니다.\n"))
        assertEquals(" 공유합니다.", OnDeviceSuggestionPolicy.parseSuffix(request, " 공유합니다.\n둘째\u0000줄"))
        assertEquals(
            " 공유합니다.",
            OnDeviceSuggestionPolicy.parseSuffix(
                input(text = "회의 자료를", mode = OnDeviceSuggestionPolicy.Mode.WORD),
                " 공유합니다.\n둘째 줄"
            )
        )
    }

    @Test
    fun `parser still rejects other control characters in a multi-line reply`() {
        val request = input(text = "회의 자료를")

        assertEquals("UNSAFE_CHAR", OnDeviceSuggestionPolicy.rejectionReason(request, " 공유\u0000합니다.\n둘째 줄"))
        assertEquals("UNSAFE_CHAR", OnDeviceSuggestionPolicy.rejectionReason(request, " 공유합니다.\r둘째 줄"))
        assertEquals("UNSAFE_CHAR", OnDeviceSuggestionPolicy.rejectionReason(request, "\n 공유\t합니다.\n둘째 줄"))
        assertEquals("EMPTY", OnDeviceSuggestionPolicy.rejectionReason(request, "\n\n"))
    }

    @Test
    fun `parser removes a restatement that only differs in spacing`() {
        val request = input(text = "회의록 정리해서 공유드릴게")

        assertEquals("요.", OnDeviceSuggestionPolicy.parseSuffix(request, "회의록 정리해서 공유 드릴게요."))
        assertEquals("요.", OnDeviceSuggestionPolicy.parseSuffix(request, " 회의록 정리해서 공유 드릴게요."))
        assertEquals(
            "요.",
            OnDeviceSuggestionPolicy.parseSuffix(
                input(text = "회의록 정리해서 공유드릴게", mode = OnDeviceSuggestionPolicy.Mode.WORD),
                "회의록 정리해서 공유 드릴게요."
            )
        )
        assertEquals("ECHO_ONLY", OnDeviceSuggestionPolicy.rejectionReason(request, "회의록 정리해서 공유 드릴게"))
        assertEquals(
            "에 시작해요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = "내일 오전 회의는 3시"), "오전 회의는 3 시에 시작해요.")
        )
        assertEquals(
            " 공유 드릴게요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의록 정리해서"), "회의록 정리해서 공유 드릴게요.")
        )
    }

    @Test
    fun `parser keeps a single syllable tail from being taken as a spacing echo`() {
        assertEquals(" 나 갑니다.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "지금 나"), " 나 갑니다."))
        assertEquals(
            "래요.",
            OnDeviceSuggestionPolicy.parseSuffix(input(text = "그"), "그래요.")
        )
    }

    @Test
    fun `parser rejects a reply that repeats the sentence being typed`() {
        val request = input(text = "회의는 모레 오전 아침에 시작해요")

        assertEquals("REPEATS_PREV", OnDeviceSuggestionPolicy.rejectionReason(request, " 그러면 아침에 시작해요."))
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(request, " 점심은 같이 먹어요."))
    }

    @Test
    fun `parser rejects a reply that restates the typed sentence with a different start as an append`() {
        val request = input(text = "회의는 아침에 시작해요 그리고")

        assertEquals("RESTATES_TYPED", OnDeviceSuggestionPolicy.rejectionReason(request, " 참고로 아침에 시작해요 그리고."))
    }

    @Test
    fun `parser rejects a sentence with two or fewer Hangul syllables`() {
        val request = input(text = "알겠습니다. ")

        assertEquals("TOO_SHORT", OnDeviceSuggestionPolicy.rejectionReason(request, "네~"))
        assertEquals("TOO_SHORT", OnDeviceSuggestionPolicy.rejectionReason(request, "응."))
        assertEquals("TOO_SHORT", OnDeviceSuggestionPolicy.rejectionReason(request, "그래."))
        assertEquals("TOO_SHORT", OnDeviceSuggestionPolicy.rejectionReason(request, "네~ 내일 봬요."))
        assertEquals("TOO_SHORT", OnDeviceSuggestionPolicy.rejectionReason(input(text = "좋아요"), "."))
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(request, "그래요."))
        assertNull(OnDeviceSuggestionPolicy.rejectionReason(request, "내일 봬요."))
        assertEquals(
            "네~",
            OnDeviceSuggestionPolicy.parseSuffix(
                input(text = "알겠습니다. ", mode = OnDeviceSuggestionPolicy.Mode.WORD),
                "네~"
            )
        )
    }

    @Test
    fun `a short ending that completes a longer typed sentence is not too short`() {
        assertEquals("요.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "회의록을 공유드릴게"), "요."))
        assertEquals("요.", OnDeviceSuggestionPolicy.parseSuffix(input(text = "그래"), "요."))
    }

    @Test
    fun `isCasualTone shares the tone detection of the prompt`() {
        assertTrue(OnDeviceSuggestionPolicy.isCasualTone("밥 먹었어. 오늘 뭐 해"))
        assertTrue(OnDeviceSuggestionPolicy.isCasualTone("오늘 뭐 해", recentSentences = listOf("밥 먹었어.", "집에 가자")))
        assertFalse(OnDeviceSuggestionPolicy.isCasualTone("식사하셨어요. 오늘 뭐 하세요"))
        assertFalse(OnDeviceSuggestionPolicy.isCasualTone("오늘 회의"))

        val prompt = OnDeviceSuggestionPolicy.promptFor(input(text = "밥 먹었어. 오늘 뭐 해"))
        assertTrue(prompt.contains("말투: 반말"))
    }

    @Test
    fun `parseForStorage stores a sentence that passes the quality gate`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(input(text = "내일 회의는"), " 오전에 시작합니다.")

        assertEquals(" 오전에 시작합니다.", stored.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.SENTENCE, stored.mode)
        assertNull(stored.sentenceRejection)
        assertNull(stored.wordRejection)
    }

    @Test
    fun `parseForStorage records a word when the sentence cannot be used`() {
        val notSentence = OnDeviceSuggestionPolicy.parseForStorage(input(text = "내일"), " 오전에 시작하고,")
        assertEquals(" 오전에", notSentence.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, notSentence.mode)
        assertEquals("NOT_SINGLE_SENTENCE", notSentence.sentenceRejection)

        val tooShort = OnDeviceSuggestionPolicy.parseForStorage(input(text = "알겠습니다. "), "네~")
        assertEquals("네~", tooShort.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, tooShort.mode)
        assertEquals("TOO_SHORT", tooShort.sentenceRejection)

        val gate = OnDeviceSuggestionPolicy.parseForStorage(input(text = "회의는"), " 오늘 할수있어요.")
        assertEquals(" 오늘", gate.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, gate.mode)
        assertEquals("GATE_SPACING", gate.sentenceRejection)
    }

    @Test
    fun `parseForStorage stores nothing when the gate rejects both forms`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(input(text = "내일 이 일을"), " 할수있어요.")

        assertNull(stored.suffix)
        assertNull(stored.mode)
        assertEquals("GATE_SPACING", stored.sentenceRejection)
        assertEquals("GATE_SPACING", stored.wordRejection)
    }

    @Test
    fun `parseForStorage stores a short casual sentence after a context that contains a celebration verb`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(
            input(text = "생일 축하해! 올 한 해도 "),
            "올 한 해도 잘 보내!"
        )

        assertEquals("잘 보내!", stored.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.SENTENCE, stored.mode)
        assertNull(stored.sentenceRejection)
        assertNull(stored.wordRejection)
    }

    @Test
    fun `parseForStorage does not keep a one syllable head of a rejected sentence as a word`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(input(text = "내일 이 일을 "), "잘 할수있어요.")

        assertNull(stored.suffix)
        assertNull(stored.mode)
        assertEquals("GATE_SPACING", stored.sentenceRejection)
        assertEquals("WORD_TOO_SHORT", stored.wordRejection)

        val spaced = OnDeviceSuggestionPolicy.parseForStorage(input(text = "내일 이 일을"), " 꼭 할수있어요.")
        assertNull(spaced.suffix)
        assertEquals("WORD_TOO_SHORT", spaced.wordRejection)
    }

    @Test
    fun `parseForStorage keeps a one syllable ending that attaches to the typed word`() {
        val particle = OnDeviceSuggestionPolicy.parseForStorage(input(text = "회의 자료"), "를 할수있어요.")

        assertEquals("를", particle.suffix)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, particle.mode)
        assertEquals("GATE_SPACING", particle.sentenceRejection)
    }

    @Test
    fun `a typo restatement is stored as a replacement of the typed sentence`() {
        val garbled = OnDeviceSuggestionPolicy.parseForStorage(
            input(text = "오늘 회으ㅣ 몇시에"),
            "오늘 회의 몇 시에 시작해요?"
        )
        assertEquals("오늘 회의 몇 시에 시작해요?", garbled.suffix)
        assertEquals("오늘 회으ㅣ 몇시에", garbled.replaces)
        assertEquals(OnDeviceSuggestionPolicy.Mode.SENTENCE, garbled.mode)

        val ending = OnDeviceSuggestionPolicy.parseForStorage(input(text = "안녕하세여"), "안녕하세요.")
        assertEquals("안녕하세요.", ending.suffix)
        assertEquals("안녕하세여", ending.replaces)
    }

    @Test
    fun `a replacement keeps only the first sentence and the whole typed sentence including its trailing space`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(
            input(text = "지난번 애기하신 자료 "),
            "지난번 얘기하신 자료 보내드릴게요. 감사합니다."
        )

        assertEquals("지난번 얘기하신 자료 보내드릴게요.", stored.suffix)
        assertEquals("지난번 애기하신 자료 ", stored.replaces)
    }

    @Test
    fun `a restatement is never an append suffix`() {
        val request = input(text = "안녕하세여")
        val word = input(text = "안녕하세여", mode = OnDeviceSuggestionPolicy.Mode.WORD)

        assertNull(OnDeviceSuggestionPolicy.parseSuffix(request, "안녕하세요."))
        assertEquals("RESTATES_TYPED", OnDeviceSuggestionPolicy.rejectionReason(request, "안녕하세요."))
        assertEquals("RESTATES_TYPED", OnDeviceSuggestionPolicy.rejectionReason(word, "안녕하세요."))
    }

    @Test
    fun `an unrelated sentence is still appended and an exact echo still strips the typed part`() {
        val unrelated = OnDeviceSuggestionPolicy.parseForStorage(input(text = "안녕하세여"), "내일 회의 일정을 공유할게요.")
        assertEquals("내일 회의 일정을 공유할게요.", unrelated.suffix)
        assertNull(unrelated.replaces)

        val echoed = OnDeviceSuggestionPolicy.parseForStorage(input(text = "회의 자료를"), "회의 자료를 공유합니다.")
        assertEquals(" 공유합니다.", echoed.suffix)
        assertNull(echoed.replaces)
    }

    @Test
    fun `a restatement that fails the sentence checks is rejected without a word fallback`() {
        val tooShort = OnDeviceSuggestionPolicy.parseForStorage(input(text = "네에"), "네요.")
        assertNull(tooShort.suffix)
        assertEquals("TOO_SHORT", tooShort.sentenceRejection)
        assertEquals("RESTATES_TYPED", tooShort.wordRejection)

        val noEnd = OnDeviceSuggestionPolicy.parseForStorage(input(text = "안녕하세여"), "안녕하세요,")
        assertNull(noEnd.suffix)
        assertEquals("NOT_SINGLE_SENTENCE", noEnd.sentenceRejection)
    }

    @Test
    fun `no replacement is considered for the next sentence or a single syllable`() {
        val next = OnDeviceSuggestionPolicy.parseForStorage(input(text = "안녕하세요. "), "안녕하세요 반갑습니다.")
        assertNull(next.replaces)

        val single = OnDeviceSuggestionPolicy.parseForStorage(input(text = "네"), "네 알겠습니다.")
        assertNull(single.replaces)
    }

    @Test
    fun `a replacement that fails the quality gate is not stored`() {
        val stored = OnDeviceSuggestionPolicy.parseForStorage(input(text = "이거 할수잇어요"), "이거 할수있어요.")

        assertNull(stored.suffix)
        assertEquals("GATE_SPACING", stored.sentenceRejection)
    }

    private fun input(
        text: String,
        packageName: String = "com.example.app",
        inputType: Int = 0,
        imeAction: Int = 0,
        mode: OnDeviceSuggestionPolicy.Mode = OnDeviceSuggestionPolicy.Mode.SENTENCE,
        appCategory: String = "general",
        recentSentences: List<String> = emptyList()
    ) = OnDeviceSuggestionPolicy.Input(
        textBeforeCursor = text,
        packageName = packageName,
        inputType = inputType,
        imeAction = imeAction,
        mode = mode,
        appCategory = appCategory,
        recentSentences = recentSentences
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
