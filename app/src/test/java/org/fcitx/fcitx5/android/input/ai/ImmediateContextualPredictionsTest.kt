/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateContextualPredictionsTest {

    @Test
    fun `cache lookup requires matching normalized context and scope`() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            "오늘 회의",
            listOf("CONTINUATION\t다른 앱 후보"),
            AiSentenceCompletionPrefetcher.Scope("com.other", 8L)
        )
        prefetcher.putPredictions(
            "오늘 회의",
            listOf("CONTINUATION\t회의록을 공유하겠습니다."),
            AiSentenceCompletionPrefetcher.Scope("com.example", 8L)
        )

        val predictions = collect("오늘  회의", prefetcher = prefetcher, epoch = 8L)

        assertEquals(listOf("회의록을 공유하겠습니다."), predictions.map { it.text })
        assertEquals("오늘  회의", predictions.single().append?.expectedContext)
    }

    @Test
    fun `cached attachment is excluded after trailing whitespace`() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            "회의 ",
            listOf("CONTINUATION_ATTACH\t에 참석해 주세요."),
            scope()
        )

        assertTrue(collect("회의 ", prefetcher = prefetcher).isEmpty())
    }

    @Test
    fun `blank context does not invoke immediate sources`() {
        var lookupCount = 0

        val predictions = collect(
            rawContext = "",
            sentencePackLookup = { _, _ ->
                lookupCount++
                emptyList()
            }
        )

        assertTrue(predictions.isEmpty())
        assertEquals(0, lookupCount)
    }

    @Test
    fun `sentence pack conversion preserves source append and lookup limit`() {
        var receivedContext: String? = null
        var receivedLimit: Int? = null
        val predictions = collect(
            rawContext = "오늘 회의 ",
            sentencePackLookup = { context, limit ->
                receivedContext = context
                receivedLimit = limit
                listOf(
                    SentencePackMatch(
                        suffix = "끝나고 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    )
                )
            },
            limit = 2
        )

        val prediction = predictions.single()
        assertEquals("오늘 회의 ", receivedContext)
        assertEquals(2, receivedLimit)
        assertEquals("sentence_pack", prediction.source)
        assertEquals("기본문장", prediction.badge)
        assertEquals(0.78f, prediction.confidenceScore)
        assertEquals(
            ContextualAppend("오늘 회의 ", "끝나고 공유하겠습니다."),
            prediction.append
        )
    }

    @Test
    fun `generated material keeps provenance and only accepts strong context evidence`() {
        val predictions = collect(
            rawContext = "오늘 회의 ",
            generatedSentenceLookup = { _, _ ->
                listOf(
                    SentencePackMatch(
                        suffix = "회의록을 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    ),
                    SentencePackMatch(
                        suffix = "약속을 정할까요?",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 1,
                        evidence = MatchEvidence.LAST_WORD
                    )
                )
            }
        )

        val prediction = predictions.single()
        assertEquals("회의록을 공유하겠습니다.", prediction.text)
        assertEquals("ondevice_generated", prediction.source)
        assertEquals("기기 AI 재료", prediction.badge)
        assertEquals(0.82f, prediction.confidenceScore)
        assertEquals(
            ContextualAppend("오늘 회의 ", "회의록을 공유하겠습니다."),
            prediction.append
        )
    }

    @Test
    fun `generated material wins sentence pack duplicate by confidence`() {
        val match = SentencePackMatch(
            suffix = "회의록을 공유하겠습니다.",
            joinMode = ContextualAppend.JoinMode.NEXT_WORD,
            matchedTokens = 2,
            evidence = MatchEvidence.PREFIX
        )

        val predictions = collect(
            rawContext = "오늘 회의 ",
            sentencePackLookup = { _, _ -> listOf(match) },
            generatedSentenceLookup = { _, _ -> listOf(match) }
        )

        assertEquals(1, predictions.size)
        assertEquals("ondevice_generated", predictions.single().source)
    }

    @Test
    fun `cached AI takes priority over matching pack text and deduplicates`() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            "오늘 회의 ",
            listOf("CONTINUATION\t끝나고 공유하겠습니다."),
            scope()
        )

        val predictions = collect(
            rawContext = "오늘 회의 ",
            sentencePackLookup = {
                    _, _ -> listOf(
                        SentencePackMatch(
                            suffix = "끝나고 공유하겠습니다.",
                            joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                            matchedTokens = 2
                        )
                    )
            },
            prefetcher = prefetcher
        )

        assertEquals(1, predictions.size)
        assertEquals("llm_cached", predictions.single().source)
        assertEquals(0.980f, predictions.single().confidenceScore)
    }

    @Test
    fun `word and sentence candidates with same text remain distinct`() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            "회의",
            listOf("WORD\t다음", "CONTINUATION\t다음"),
            scope()
        )

        val predictions = collect("회의", prefetcher = prefetcher)

        assertEquals(setOf(false, true), predictions.map { it.isSentenceCompletion }.toSet())
        assertEquals(2, predictions.count { it.text == "다음" })
    }

    @Test
    fun `non-displayable immediate candidates are filtered before deduplication`() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions("회의", listOf("CONTINUATION\tㄱ"), scope())

        assertTrue(collect("회의", prefetcher = prefetcher).isEmpty())
    }

    private fun collect(
        rawContext: String,
        sentencePackLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
        prefetcher: AiSentenceCompletionPrefetcher? = null,
        generatedSentenceLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
        epoch: Long = 0L,
        limit: Int = 4
    ): List<AiPrediction> = ImmediateContextualPredictions.collect(
        input = ImmediateContextualPredictions.Input(
            rawContext = rawContext,
            packageName = "com.example",
            inputSessionEpoch = epoch,
            limit = limit
        ),
        sentencePackLookup = sentencePackLookup,
        prefetcher = prefetcher,
        generatedSentenceLookup = generatedSentenceLookup
    )

    private fun scope() = AiSentenceCompletionPrefetcher.Scope("com.example", 0L)
}
