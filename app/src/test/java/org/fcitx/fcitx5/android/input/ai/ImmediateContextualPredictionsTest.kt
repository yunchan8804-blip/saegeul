/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateContextualPredictionsTest {

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
    fun `generated material rejects every evidence except a sentence prefix`() {
        val predictions = collect(
            rawContext = "오늘 회의 ",
            generatedSentenceLookup = { _, _ ->
                listOf(
                    SentencePackMatch(
                        suffix = "회의록을 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.CONTEXT_SUFFIX
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

        assertTrue(predictions.isEmpty())
    }

    @Test
    fun `human made sentence pack keeps context suffix evidence`() {
        val predictions = collect(
            rawContext = "앞 문장은 끝났고 오늘 회의 ",
            sentencePackLookup = { _, _ ->
                listOf(
                    SentencePackMatch(
                        suffix = "끝나고 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.CONTEXT_SUFFIX
                    )
                )
            }
        )

        assertEquals("sentence_pack", predictions.single().source)
    }

    @Test
    fun `casual context gets no polite generated material`() {
        var lookups = 0
        val lookup: (String, Int) -> List<SentencePackMatch> = { _, _ ->
            lookups++
            listOf(
                SentencePackMatch(
                    suffix = "회의록을 공유하겠습니다.",
                    joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                    matchedTokens = 2,
                    evidence = MatchEvidence.PREFIX
                )
            )
        }

        val casual = collect(rawContext = "밥 먹었어. 오늘 회의 ", generatedSentenceLookup = lookup)
        val polite = collect(rawContext = "식사하셨어요. 오늘 회의 ", generatedSentenceLookup = lookup)
        val unknown = collect(rawContext = "오늘 회의 ", generatedSentenceLookup = lookup)

        assertEquals(2, lookups)
        assertTrue(casual.isEmpty())
        assertEquals("ondevice_generated", polite.single().source)
        assertEquals("ondevice_generated", unknown.single().source)
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
        assertEquals(0.70f, prediction.confidenceScore)
        assertEquals(
            ContextualAppend("오늘 회의 ", "회의록을 공유하겠습니다."),
            prediction.append
        )
    }

    @Test
    fun `sentence pack wins generated material duplicate by confidence`() {
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
        assertEquals("sentence_pack", predictions.single().source)
    }

    @Test
    fun `browser persona skips generated material lookup entirely`() {
        var lookupCount = 0

        val predictions = collect(
            rawContext = "오늘 회의 ",
            packageName = "com.android.chrome",
            generatedSentenceLookup = { _, _ ->
                lookupCount++
                listOf(
                    SentencePackMatch(
                        suffix = "회의록을 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    )
                )
            }
        )

        assertEquals(0, lookupCount)
        assertTrue(predictions.isEmpty())
    }

    @Test
    fun `commerce persona skips generated material lookup entirely`() {
        var lookupCount = 0

        val predictions = collect(
            rawContext = "오늘 회의 ",
            packageName = "com.coupang.mobile",
            generatedSentenceLookup = { _, _ ->
                lookupCount++
                listOf(
                    SentencePackMatch(
                        suffix = "회의록을 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    )
                )
            }
        )

        assertEquals(0, lookupCount)
        assertTrue(predictions.isEmpty())
    }

    @Test
    fun `messenger persona scores generated material below sentence pack prefix`() {
        val predictions = collect(
            rawContext = "오늘 회의 ",
            packageName = "com.kakao.talk",
            sentencePackLookup = { _, _ ->
                listOf(
                    SentencePackMatch(
                        suffix = "끝나고 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    )
                )
            },
            generatedSentenceLookup = { _, _ ->
                listOf(
                    SentencePackMatch(
                        suffix = "회의록을 공유하겠습니다.",
                        joinMode = ContextualAppend.JoinMode.NEXT_WORD,
                        matchedTokens = 2,
                        evidence = MatchEvidence.PREFIX
                    )
                )
            }
        )

        assertEquals(2, predictions.size)
        assertEquals("sentence_pack", predictions[0].source)
        assertEquals(0.78f, predictions[0].confidenceScore)
        assertEquals("ondevice_generated", predictions[1].source)
        assertEquals(0.70f, predictions[1].confidenceScore)
    }

    @Test
    fun `generated spacing uses the trimmed complete context and retains ASCII edge spaces`() {
        var lookupContext: String? = null

        val predictions = collect(
            rawContext = "  회의자료를 공유합니다  ",
            generatedSpacingLookup = { sentence ->
                lookupContext = sentence
                "회의 자료를 공유합니다"
            }
        )

        val prediction = predictions.single()
        assertEquals("회의자료를 공유합니다", lookupContext)
        assertEquals("  회의 자료를 공유합니다  ", prediction.text)
        assertEquals("ondevice_generated_spacing", prediction.source)
        assertEquals("기기 AI 띄어쓰기", prediction.badge)
        assertEquals(0.84f, prediction.confidenceScore)
        assertEquals(
            ContextualReplacement(
                expectedContext = "  회의자료를 공유합니다  ",
                replacement = "  회의 자료를 공유합니다  "
            ),
            prediction.replacement
        )
        assertNull(prediction.append)
    }

    @Test
    fun `generated spacing excludes an unchanged suggestion`() {
        val predictions = collect(
            rawContext = "회의 자료를 공유합니다",
            generatedSpacingLookup = { "회의 자료를 공유합니다" }
        )

        assertTrue(predictions.isEmpty())
    }

    @Test
    fun `generated spacing lookup receives leading line breaks and tabs unchanged`() {
        val receivedContexts = mutableListOf<String>()

        val predictions = listOf("\n회의자료를 공유합니다", "\t회의자료를 공유합니다")
            .flatMap { rawContext ->
                collect(
                    rawContext = rawContext,
                    generatedSpacingLookup = { sentence ->
                        receivedContexts += sentence
                        null
                    }
                )
            }

        assertEquals(listOf("\n회의자료를 공유합니다", "\t회의자료를 공유합니다"), receivedContexts)
        assertTrue(predictions.isEmpty())
    }

    private fun collect(
        rawContext: String,
        packageName: String = "com.example",
        sentencePackLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
        generatedSentenceLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
        generatedSpacingLookup: ((String) -> String?)? = null,
        epoch: Long = 0L,
        limit: Int = 4
    ): List<AiPrediction> = ImmediateContextualPredictions.collect(
        input = ImmediateContextualPredictions.Input(
            rawContext = rawContext,
            packageName = packageName,
            inputSessionEpoch = epoch,
            limit = limit
        ),
        sentencePackLookup = sentencePackLookup,
        generatedSentenceLookup = generatedSentenceLookup,
        generatedSpacingLookup = generatedSpacingLookup
    )
}
