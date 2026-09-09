/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceRecord
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePackPredictorIntegrationTest {

    @Test
    fun emptyPersonalStoresWithoutProviderSurfaceBundledCompletionAndAppend() {
        val prediction = predictor().predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 ",
            packageName = PACKAGE_NAME,
            limit = 5
        ).first { it.source == "sentence_pack" }

        assertEquals("끝나고 다시 연락드릴게요.", prediction.text)
        assertEquals(0.78f, prediction.confidenceScore, 0.0001f)
        assertTrue(prediction.isSentenceCompletion)
        assertEquals("기본문장", prediction.badge)
        val append = requireNotNull(prediction.append)
        assertEquals("오늘 회의 ", append.expectedContext)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, append.joinMode)
        assertEquals("끝나고 다시 연락드릴게요. ", append.insertionFor("오늘 회의 "))
    }

    @Test
    fun rawContextPreservesTrailingSpacePartialEojeolAndPreviousSentence() {
        val trailing = predictor().sentencePack("", "오늘 회의 ")
        assertEquals("오늘 회의 ", requireNotNull(trailing.append).expectedContext)

        val partial = predictor().sentencePack("끝", "오늘 회의 ")
        val partialAppend = requireNotNull(partial.append)
        assertEquals("오늘 회의 끝", partialAppend.expectedContext)
        assertEquals("나고 다시 연락드릴게요.", partial.text)
        assertEquals(ContextualAppend.JoinMode.ATTACH, partialAppend.joinMode)
        assertEquals("나고 다시 연락드릴게요. ", partialAppend.insertionFor("오늘 회의 끝"))

        val withPreviousSentence = predictor().sentencePack("", "지난 메시지는 보냈어요. 오늘 회의 ")
        assertEquals(
            "지난 메시지는 보냈어요. 오늘 회의 ",
            requireNotNull(withPreviousSentence.append).expectedContext
        )
    }

    @Test
    fun completedLastWordFallbackUsesLowerConfidenceAndPreservesAppendContext() {
        val prediction = predictor().sentencePack("", "내일 회의 ")

        assertEquals("끝나고 다시 연락드릴게요.", prediction.text)
        assertEquals(0.64f, prediction.confidenceScore, 0.0001f)
        val append = requireNotNull(prediction.append)
        assertEquals("내일 회의 ", append.expectedContext)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, append.joinMode)
        assertEquals("끝나고 다시 연락드릴게요. ", append.insertionFor("내일 회의 "))
    }

    @Test
    fun completedLastWordBeforeSpacePreservesCombinedRawContextAndSingleSeparator() {
        val prediction = predictor().sentencePack("회의", "내일 ")

        assertEquals("끝나고 다시 연락드릴게요.", prediction.text)
        assertEquals(0.64f, prediction.confidenceScore, 0.0001f)
        val append = requireNotNull(prediction.append)
        assertEquals("내일 회의", append.expectedContext)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, append.joinMode)
        assertEquals(" 끝나고 다시 연락드릴게요. ", append.insertionFor("내일 회의"))
    }

    @Test
    fun incompleteChatbotCsvLastWordFragmentDoesNotReachSentenceSurface() {
        val predictions = predictor(sentences = listOf("지금 나한테 장난친거")).predict(
            currentStroke = "",
            contextBeforeCursor = "나는 지금 ",
            packageName = PACKAGE_NAME,
            limit = 5
        )

        assertNull(predictions.firstOrNull { it.source == "sentence_pack" })
    }

    @Test
    fun strongUnpunctuatedQuestionStillReachesSentenceSurface() {
        val prediction = predictor(
            sentences = listOf("목요일 회의는 정해진 시간에 진행해도 될까요")
        ).sentencePack("", "목요일 회의는")

        assertEquals("정해진 시간에 진행해도 될까요", prediction.text)
        assertEquals(0.78f, prediction.confidenceScore, 0.0001f)
    }

    @Test
    fun unmatchedContextProducesNoSentencePackCandidate() {
        val predictions = predictor().predict(
            currentStroke = "",
            contextBeforeCursor = "연필은 서랍에 ",
            packageName = PACKAGE_NAME,
            limit = 5
        )

        assertNull(predictions.firstOrNull { it.source == "sentence_pack" })
    }

    @Test
    fun unrelatedPersonalCandidatesRemainBlockedWhileSentencePackContinues() {
        val store = PersonalizedSentenceStore().apply {
            upsert(
                PersonalizedSentenceRecord(
                    sentence = "내일 판교에서 봐요",
                    source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE
                )
            )
        }
        val vault = PersonalSentenceVault(clock = { 1_000_000_000L }).apply {
            record("내일 판교에서 봐요", PACKAGE_NAME)
        }
        val predictions = predictor(personalizedStore = store, personalSentenceVault = vault).predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의 ",
            packageName = PACKAGE_NAME,
            limit = 5
        )

        assertFalse(predictions.any { it.source == "personalized_style" || it.source == "rag_personal" })
        assertNotNull(predictions.firstOrNull { it.source == "sentence_pack" })
    }

    private fun predictor(
        personalizedStore: PersonalizedSentenceStore? = null,
        personalSentenceVault: PersonalSentenceVault? = null,
        sentences: List<String> = BUNDLED_SENTENCES
    ): AiContextualPredictor {
        val index = SentencePackIndex.build(sentences)
        return AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel(clock = { 1_000_000_000L }),
            personalizedStore = personalizedStore,
            personalSentenceVault = personalSentenceVault,
            sentencePackLookup = index::complete
        )
    }

    private fun AiContextualPredictor.sentencePack(stroke: String, context: String) = predict(
        currentStroke = stroke,
        contextBeforeCursor = context,
        packageName = PACKAGE_NAME,
        limit = 5
    ).first { it.source == "sentence_pack" }

    private companion object {
        const val PACKAGE_NAME = "com.example.test"
        val BUNDLED_SENTENCES = listOf(
            "오늘 회의 끝나고 다시 연락드릴게요.",
            "내일 저녁에 잠깐 통화할 수 있을까요?"
        )
    }
}
