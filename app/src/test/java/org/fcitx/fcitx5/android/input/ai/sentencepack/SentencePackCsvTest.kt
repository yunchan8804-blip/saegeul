/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePackCsvTest {
    @Test
    fun selectedRowsSupportQuotedFieldsAndNormalizeDuplicates() {
        val csv = "\uFEFFQ,A,label\r\n\"오늘 \"\"회의\"\" 끝나고 연락드릴게요.\",\"네 알겠습니다 다시 기다릴게요.\", 0\r\n\"오늘 \"\"회의\"\" 끝나고 연락드릴게요.\",\"영문 abc 가 포함된 문장입니다.\",0\r\n무시할 질문입니다,무시할 답변입니다,1\r\n"

        val sentences = SentencePackCsv.parseSelected(csv)

        assertEquals(2, sentences.size)
        assertTrue(sentences.all { it.contains(' ') })
    }

    @Test(expected = SentencePackFormatException::class)
    fun malformedQuotedCsvFailsExplicitly() {
        SentencePackCsv.parseSelected("Q,A,label\n\"오늘 회의 끝나고 연락드릴게요.,답변입니다,0")
    }

    @Test(expected = SentencePackFormatException::class)
    fun wrongColumnCountFailsExplicitly() {
        SentencePackCsv.parseSelected("Q,A,label\n오늘 회의 끝나고 연락드릴게요.,0")
    }

    @Test(expected = SentencePackFormatException::class)
    fun textAfterClosingQuoteFailsExplicitly() {
        SentencePackCsv.parseSelected("Q,A,label\n\"오늘 회의 끝나고 연락드릴게요.\"잘못됨,답변입니다,0")
    }

    @Test
    fun rejectedContentNeverEntersSelectedPack() {
        val csv = "Q,A,label\n오늘 회의 끝나고 연락드릴게요.,자살 관련 문장은 포함하지 않습니다.,0\n"

        assertEquals(listOf("오늘 회의 끝나고 연락드릴게요."), SentencePackCsv.parseSelected(csv))
    }
}
