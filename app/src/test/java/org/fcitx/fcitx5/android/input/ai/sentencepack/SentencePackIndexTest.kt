/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePackIndexTest {
    private val index = SentencePackIndex.build(
        listOf(
            "오늘 회의 끝나고 다시 연락드릴게요.",
            "오늘 회의 끝나면 바로 공유할게요.",
            "내일 점심 시간에 다시 이야기해요."
        )
    )

    @Test
    fun fullPrefixUsesWholeWordContinuationAtWhitespaceBoundary() {
        val result = index.complete("오늘 회의 ", 4)

        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, result.first().joinMode)
        assertEquals(2, result.first().matchedTokens)
        assertTrue(result.all { it.suffix.startsWith("끝나") })
    }

    @Test
    fun incompleteWordUsesAttachContinuation() {
        val result = index.complete("오늘 회의 끝", 4)

        assertEquals(ContextualAppend.JoinMode.ATTACH, result.first().joinMode)
        assertTrue(result.first().suffix.startsWith("나"))
    }

    @Test
    fun contextTailFallsBackToLongestTwoWordSuffix() {
        val result = index.complete("앞 문장은 끝났고 오늘 회의 ", 4)

        assertTrue(result.isNotEmpty())
        assertEquals(2, result.first().matchedTokens)
        assertEquals(MatchEvidence.CONTEXT_SUFFIX, result.first().evidence)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, result.first().joinMode)
    }

    @Test
    fun completedLastWordFallsBackWithoutTrailingWhitespace() {
        val lastWordIndex = SentencePackIndex.build(
            listOf("오늘 회의는 정해진 시간에 진행해도 될까요?")
        )
        val result = lastWordIndex.complete("회의는", 4)

        assertTrue(result.isNotEmpty())
        assertEquals(MatchEvidence.LAST_WORD, result.first().evidence)
        assertEquals(1, result.first().matchedTokens)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, result.first().joinMode)
        assertTrue(result.all { it.suffix.split(' ').size <= 4 })
    }

    @Test
    fun incompleteOrExcludedLastWordDoesNotFallBack() {
        val lastWordIndex = SentencePackIndex.build(
            listOf("오늘 회의는 정해진 시간에 진행해도 될까요?", "오늘 정말 좋은 소식이네요.")
        )

        assertTrue(lastWordIndex.complete("회의ㄴ", 4).isEmpty())
        assertTrue(lastWordIndex.complete("회의", 4).isEmpty())
        assertTrue(lastWordIndex.complete("정말 ", 4).isEmpty())
        val requestIndex = SentencePackIndex.build(listOf("부탁 하나만 들어줄 수 있어?"))
        assertTrue(requestIndex.complete("확인 부탁 ", 4).isEmpty())
    }

    @Test
    fun lastWordFallbackSkipsLongSuffixInsteadOfTruncatingIt() {
        val lastWordIndex = SentencePackIndex.build(
            listOf(
                "지금 가는 길인데 길이 조금 막혀요.",
                "지금 확인해서 바로 처리하겠습니다."
            )
        )

        val result = lastWordIndex.complete("나는 지금 ", 4)

        assertEquals(1, result.size)
        assertEquals("확인해서 바로 처리하겠습니다.", result.first().suffix)
        assertEquals(MatchEvidence.LAST_WORD, result.first().evidence)
        assertTrue(result.first().suffix.split(' ').size <= 4)
    }

    @Test
    fun lastWordFallbackRejectsIncompleteChatbotCsvFragmentButKeepsTerminalSentence() {
        val csvFragmentIndex = SentencePackIndex.build(
            listOf(
                "지금 나한테 장난친거",
                "시간 가는 줄 모르겠어요."
            )
        )

        assertTrue(csvFragmentIndex.complete("나는 지금 ", 4).isEmpty())

        val terminal = csvFragmentIndex.complete("내일 시간 ", 4)
        assertEquals(listOf("가는 줄 모르겠어요."), terminal.map(SentencePackMatch::suffix))
        assertEquals(MatchEvidence.LAST_WORD, terminal.first().evidence)
    }

    @Test
    fun strongPrefixKeepsUnpunctuatedNaturalQuestion() {
        val questionIndex = SentencePackIndex.build(
            listOf("목요일 회의는 정해진 시간에 진행해도 될까요")
        )

        val result = questionIndex.complete("목요일 회의는", 4)

        assertEquals(listOf("정해진 시간에 진행해도 될까요"), result.map(SentencePackMatch::suffix))
        assertEquals(MatchEvidence.PREFIX, result.first().evidence)
    }

    @Test
    fun lastWordFallbackKeepsExactEojeolBoundary() {
        val boundaryIndex = SentencePackIndex.build(listOf("오늘 회의는 정해진 시간에 진행해도 될까요?"))

        assertTrue(boundaryIndex.complete("회의", 4).isEmpty())
        assertTrue(boundaryIndex.complete("회의 ", 4).isEmpty())
        assertTrue(boundaryIndex.complete("회의는", 4).isNotEmpty())
    }

    @Test
    fun completedEojeolWithoutTrailingWhitespaceUsesBoundedNextWordFallback() {
        val result = index.complete("내일 회의", 4)

        assertEquals("끝나고 다시 연락드릴게요.", result.first().suffix)
        assertEquals(MatchEvidence.LAST_WORD, result.first().evidence)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, result.first().joinMode)
        assertTrue(result.all { it.suffix.split(' ').size <= 4 })
        assertEquals(MatchEvidence.PREFIX, index.complete("오늘 회의", 4).first().evidence)
        assertTrue(index.complete("오늘 회의 끝났어요.", 4).isEmpty())
        assertTrue(index.complete("내일 회의ㄴ", 4).isEmpty())
    }

    @Test
    fun basicAssetFixtureCoversRepresentativeChatContexts() {
        val basicAssetIndex = SentencePackIndex.build(
            listOf(
                "오늘 회의 끝나고 다시 연락드릴게요.",
                "오늘 저녁에는 집에서 쉬고 싶어요.",
                "저녁 약속 장소를 다시 알려주세요.",
                "회의 시작 전에 자료를 공유하겠습니다.",
                "자료를 검토한 뒤 의견을 드리겠습니다.",
                "지금 확인해서 바로 처리하겠습니다."
            )
        )
        val contexts = linkedMapOf(
            "내일 회의 " to MatchEvidence.LAST_WORD,
            "회의 자료를 " to MatchEvidence.LAST_WORD,
            "나는 지금 " to MatchEvidence.LAST_WORD,
            "친구야 오늘 " to MatchEvidence.LAST_WORD,
            "오늘 저녁 " to MatchEvidence.LAST_WORD
        )

        contexts.forEach { (context, evidence) ->
            val result = basicAssetIndex.complete(context, 4)

            assertTrue(context, result.isNotEmpty())
            assertEquals(context, evidence, result.first().evidence)
        }
    }

    @Test
    fun capturedMultilineContextUsesTheLastSentenceOnly() {
        val result = index.complete("앞 문장이에요.\n오늘 회의 ", 4)

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.suffix.startsWith("끝나") })
    }

    @Test
    fun terminalOrUnsafeContextReturnsNothing() {
        assertTrue(index.complete("오늘 회의 끝났어요.", 4).isEmpty())
        assertTrue(index.complete("오늘 \u1100", 4).isEmpty())
        assertTrue(index.complete("오늘 회의", 0).isEmpty())
    }

    @Test
    fun duplicateSuffixesAreRemovedAndLimitIsBounded() {
        val duplicateIndex = SentencePackIndex.build(
            listOf("오늘 바쁜 회의 끝나고 연락드릴게요.", "내일 바쁜 회의 끝나고 연락드릴게요.")
        )

        assertEquals(1, duplicateIndex.complete("바쁜 회의 ", 4).size)
        assertEquals(1, index.complete("오늘 ", 1).size)
    }

    @Test
    fun mergedPacksKeepOnlyStrongestEvidenceAndDeduplicateBySuffixAndJoinMode() {
        val weakBuiltin = listOf(
            SentencePackMatch("정해진 시간에 진행해도 될까요?", ContextualAppend.JoinMode.NEXT_WORD, 1, MatchEvidence.LAST_WORD),
            SentencePackMatch("같이 준비해요.", ContextualAppend.JoinMode.NEXT_WORD, 1, MatchEvidence.LAST_WORD)
        )
        val strongInstalled = listOf(
            SentencePackMatch("정해진 시간에 진행해도 될까요?", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.CONTEXT_SUFFIX),
            SentencePackMatch("바로 공유할게요.", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.CONTEXT_SUFFIX),
            SentencePackMatch("이어갈게요.", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.PREFIX)
        )

        val result = mergeSentencePackMatches(weakBuiltin, strongInstalled, 2)

        assertEquals(listOf("이어갈게요."), result.map(SentencePackMatch::suffix))
        assertEquals(MatchEvidence.PREFIX, result.first().evidence)
        assertEquals(1, result.size)
    }

    @Test
    fun mergedPacksExcludeWeakerFallbackAndKeepEqualStrengthMatchesInStableOrder() {
        val strongBuiltin = listOf(
            SentencePackMatch("회의를 시작할까요?", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.CONTEXT_SUFFIX),
            SentencePackMatch("회의를 시작할까요?", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.CONTEXT_SUFFIX)
        )
        val equalInstalled = listOf(
            SentencePackMatch("자료를 먼저 볼까요?", ContextualAppend.JoinMode.NEXT_WORD, 2, MatchEvidence.CONTEXT_SUFFIX),
            SentencePackMatch("약속을 미뤄도 될까요?", ContextualAppend.JoinMode.NEXT_WORD, 1, MatchEvidence.LAST_WORD)
        )

        val result = mergeSentencePackMatches(strongBuiltin, equalInstalled, 2)

        assertEquals(listOf("회의를 시작할까요?", "자료를 먼저 볼까요?"), result.map(SentencePackMatch::suffix))
        assertTrue(result.none { it.evidence == MatchEvidence.LAST_WORD })
    }
}
