/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DynamicPhraseTemplateTest {
    private val now = ZonedDateTime.of(2026, 7, 26, 9, 5, 0, 0, ZoneId.of("Asia/Seoul"))

    @Test
    fun expandsEveryKoreanVariableWithFrozenValues() {
        val result = DynamicPhraseTemplate.expand(
            "{이름}님, {날짜} {시간}\n{전화번호}\n{주소}\n{클립보드}",
            DynamicPhraseValues(
                now = now,
                profile = DynamicPhraseProfile("윤찬", "010-1234-5678", "서울"),
                clipboardText = "확인했습니다"
            )
        )

        assertEquals(
            "윤찬님, 2026년 7월 26일 09:05\n010-1234-5678\n서울\n확인했습니다",
            result.text
        )
        assertTrue(result.canInsert)
        assertEquals(DynamicPhraseVariable.entries.toSet(), result.usedVariables)
    }

    @Test
    fun expandsEnglishAliasesCaseInsensitively() {
        val result = DynamicPhraseTemplate.expand(
            "{NAME} {phone} {ADDRESS} {DATE} {TIME} {CLIPBOARD}",
            DynamicPhraseValues(
                now = now,
                profile = DynamicPhraseProfile("Yun", "010", "Seoul"),
                clipboardText = "ok"
            )
        )

        assertEquals("Yun 010 Seoul 2026년 7월 26일 09:05 ok", result.text)
        assertTrue(result.canInsert)
    }

    @Test
    fun preservesUnknownTokens() {
        val result = DynamicPhraseTemplate.expand(
            "{날짜} {회사} {{이름}}",
            DynamicPhraseValues(now = now, profile = DynamicPhraseProfile(name = "윤찬"))
        )

        assertEquals("2026년 7월 26일 {회사} {윤찬}", result.text)
        assertTrue(result.canInsert)
    }

    @Test
    fun missingPersonalValueKeepsTokenAndDisablesInsert() {
        val result = DynamicPhraseTemplate.expand("안녕 {이름}", DynamicPhraseValues(now = now))

        assertEquals("안녕 {이름}", result.text)
        assertFalse(result.canInsert)
        assertEquals(
            listOf(DynamicPhraseIssue(DynamicPhraseVariable.Name, DynamicPhraseIssueReason.MissingValue)),
            result.issues
        )
    }

    @Test
    fun privateEditorAllowsDateAndTimeButBlocksPersonalAndClipboard() {
        val result = DynamicPhraseTemplate.expand(
            "{날짜} {시간} {이름} {클립보드}",
            DynamicPhraseValues(
                now = now,
                profile = DynamicPhraseProfile(name = "윤찬"),
                clipboardText = "secret",
                privateEditor = true
            )
        )

        assertEquals("2026년 7월 26일 09:05 {이름} {클립보드}", result.text)
        assertEquals(
            listOf(
                DynamicPhraseIssue(DynamicPhraseVariable.Name, DynamicPhraseIssueReason.PrivateEditor),
                DynamicPhraseIssue(DynamicPhraseVariable.Clipboard, DynamicPhraseIssueReason.PrivateEditor)
            ),
            result.issues
        )
    }

    @Test
    fun sensitiveClipboardIsNeverExpanded() {
        val result = DynamicPhraseTemplate.expand(
            "값: {클립보드}",
            DynamicPhraseValues(now = now, clipboardText = "password", clipboardSensitive = true)
        )

        assertEquals("값: {클립보드}", result.text)
        assertEquals(DynamicPhraseIssueReason.SensitiveClipboard, result.issues.single().reason)
        assertFalse(result.canInsert)
    }

    @Test
    fun detectsOnlySupportedTokens() {
        assertTrue(DynamicPhraseTemplate.containsSupportedToken("오늘 {날짜}"))
        assertTrue(DynamicPhraseTemplate.containsSupportedToken("Call {PHONE}"))
        assertFalse(DynamicPhraseTemplate.containsSupportedToken("{회사}"))
        assertFalse(DynamicPhraseTemplate.containsSupportedToken("일반 문구"))
    }
}
