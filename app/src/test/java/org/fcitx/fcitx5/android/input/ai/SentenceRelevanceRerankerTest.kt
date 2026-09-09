/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SentenceRelevanceReranker.
 * Covers topical-overlap boosting, personal n-gram bridge boosting/penalizing,
 * the no-new-content hard cut, null-ngram safety, and the blank-context passthrough.
 */
class SentenceRelevanceRerankerTest {

    private val packageName = "com.example.test"

    @Test
    fun onTopicCandidateOutranksOffTopicCandidateAtEqualBaseScore() {
        val context = "회의 자료 준비"
        val onTopic = AiPrediction(
            text = "회의 자료 준비 다 됐습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )
        val offTopic = AiPrediction(
            text = "저녁 메뉴는 뭘로 할까요",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(offTopic, onTopic),
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 5
        )

        assertEquals(2, result.size)
        assertEquals(onTopic.text, result.first().text)
        assertTrue(result.first().confidenceScore > result.last().confidenceScore)
    }

    @Test
    fun ngramBridgeBoostsCandidateMatchingLearnedNextWordOverUnsupportedAlternative() {
        val ngram = PersonalNgramModel()
        ngram.learn("회의 다음주에 진행하겠습니다", packageName)

        val context = "회의 "
        val supported = AiPrediction(
            text = "회의 다음주에 하겠습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "input_continuation"
        )
        val unsupported = AiPrediction(
            text = "회의 오늘 하겠습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "input_continuation"
        )

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(unsupported, supported),
            contextBeforeCursor = context,
            ngram = ngram,
            packageName = packageName,
            limit = 5
        )

        assertEquals(2, result.size)
        assertEquals(supported.text, result.first().text)
        assertTrue(result.first().confidenceScore > 0.5f)
        assertTrue(result.last().confidenceScore < 0.5f)
    }

    @Test
    fun candidateThatAddsNoNewTextIsDropped() {
        val context = "회의 참석하겠습니다"
        val samePrefix = AiPrediction(
            text = "회의 참석",
            confidenceScore = 0.9f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )
        val identical = AiPrediction(
            text = context,
            confidenceScore = 0.9f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )
        val genuine = AiPrediction(
            text = "회의 참석하겠습니다 감사합니다",
            confidenceScore = 0.4f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(samePrefix, identical, genuine),
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 5
        )

        assertEquals(1, result.size)
        assertEquals(genuine.text, result.first().text)
    }

    @Test
    fun nullNgramSkipsBridgeFactorWithoutCrashing() {
        val context = "회의 "
        val a = AiPrediction(
            text = "회의 다음주에 하겠습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "input_continuation"
        )
        val b = AiPrediction(
            text = "회의 오늘 하겠습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "input_continuation"
        )

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(a, b),
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 5
        )

        assertEquals(2, result.size)
        result.forEach { assertEquals(0.5f, it.confidenceScore, 0.001f) }
    }

    @Test
    fun blankContextReturnsInputOrderedByConfidenceWithoutRescoring() {
        val low = AiPrediction(text = "낮은 점수 문장", confidenceScore = 0.2f, isSentenceCompletion = true, source = "rag_personal")
        val high = AiPrediction(text = "높은 점수 문장", confidenceScore = 0.9f, isSentenceCompletion = true, source = "rag_personal")
        val mid = AiPrediction(text = "중간 점수 문장", confidenceScore = 0.5f, isSentenceCompletion = true, source = "rag_personal")

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(low, high, mid),
            contextBeforeCursor = "",
            ngram = null,
            packageName = packageName,
            limit = 5
        )

        assertEquals(listOf(high.text, mid.text, low.text), result.map { it.text })
        assertEquals(high.confidenceScore, result[0].confidenceScore, 0.001f)
        assertEquals(mid.confidenceScore, result[1].confidenceScore, 0.001f)
        assertEquals(low.confidenceScore, result[2].confidenceScore, 0.001f)
    }

    @Test
    fun returnedCandidateCountNeverExceedsLimit() {
        val context = "오늘 일정 확인"
        val candidates = (0 until 10).map { i ->
            AiPrediction(
                text = "오늘 일정 확인 $i 완료했습니다",
                confidenceScore = 0.1f * (i + 1),
                isSentenceCompletion = true,
                source = "rag_personal"
            )
        }

        val result = SentenceRelevanceReranker.rerank(
            sentences = candidates,
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 3
        )

        assertTrue(result.size <= 3)
    }

    @Test
    fun graphStoreProximityBoostsCandidateConnectedToContextInGraph() {
        val graphStore = PersonalGraphStore(storeFile = null)
        graphStore.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node(id = "발표", tags = emptyList(), weight = 1.0f),
                PersonalGraphStore.Node(id = "보고", tags = emptyList(), weight = 1.0f)
            ),
            edges = listOf(
                PersonalGraphStore.Edge(a = "발표", b = "보고", weight = 1.0f)
            ),
            topics = emptyList(),
            builtMs = 0L
        )

        val context = "발표"
        val candidate = AiPrediction(
            text = "보고 드리겠습니다",
            confidenceScore = 0.5f,
            isSentenceCompletion = true,
            source = "rag_personal"
        )

        val withoutGraph = SentenceRelevanceReranker.rerank(
            sentences = listOf(candidate),
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 5,
            graphStore = null
        )
        val withGraph = SentenceRelevanceReranker.rerank(
            sentences = listOf(candidate),
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 5,
            graphStore = graphStore
        )

        assertEquals(1, withoutGraph.size)
        assertEquals(1, withGraph.size)
        assertTrue(withGraph.first().confidenceScore > withoutGraph.first().confidenceScore)
    }

    @Test
    fun typedAppendUsesItsFullSuffixWhenItSharesTheContextPrefix() {
        val context = "나는"
        val append = ContextualAppend(context, "나중에 연락할게")
        val candidate = AiPrediction(
            text = append.suffix,
            confidenceScore = 0.8f,
            isSentenceCompletion = true,
            source = "llm_cached",
            append = append
        )
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { ngram.learn("나는 나중에 연락할게", packageName) }

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(candidate),
            contextBeforeCursor = context,
            ngram = ngram,
            packageName = packageName,
            limit = 5
        )

        assertEquals(1, result.size)
        assertEquals(append, result.single().append)
        assertEquals(" 나중에 연락할게 ", result.single().append!!.insertionFor(context))
        assertEquals(0.999f, result.single().confidenceScore, 0.001f)
    }

    @Test
    fun attachmentAppendDoesNotUseNextWordBridgeScoring() {
        val context = "회의"
        val append = ContextualAppend(
            context,
            "에 참석해 주세요.",
            ContextualAppend.JoinMode.ATTACH
        )
        val candidate = AiPrediction(
            text = append.suffix,
            confidenceScore = 0.8f,
            isSentenceCompletion = true,
            source = "llm_cached",
            append = append
        )
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { ngram.learn("회의 자료", packageName) }

        val result = SentenceRelevanceReranker.rerank(
            sentences = listOf(candidate),
            contextBeforeCursor = context,
            ngram = ngram,
            packageName = packageName,
            limit = 5
        )

        assertEquals(1, result.size)
        assertEquals(append, result.single().append)
        assertEquals(0.8f, result.single().confidenceScore, 0.001f)
    }
}
