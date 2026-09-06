/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests for Full Contextual Semantic Next-Sentence Prediction System.
 */
class KoreanSemanticSentencePredictorTest {

    private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
    private lateinit var prefetcher: AiSentenceCompletionPrefetcher
    private lateinit var contextualPredictor: AiContextualPredictor

    @Before
    fun setUp() {
        semanticPredictor = KoreanSemanticSentencePredictor()
        prefetcher = AiSentenceCompletionPrefetcher(clientProvider = null)
        contextualPredictor = AiContextualPredictor(
            lexicon = PersonalizedLexiconModel(maxCapacity = 1000),
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = semanticPredictor,
            prefetcher = prefetcher
        )
    }

    @Test
    fun testSchedulingIntentPredictionsHonorificAndInformal() {
        // Honorific scheduling context
        val honorificContext = "내일 오후 3시에 회의가 있는데 시간 어떠신가요?"
        val honorificResults = semanticPredictor.predictNextSentences(honorificContext)
        assertTrue("Scheduling predictions must not be empty", honorificResults.isNotEmpty())
        assertEquals(ContextualIntent.Scheduling, honorificResults[0].intent)
        assertEquals(KoreanTone.Honorific, honorificResults[0].tone)
        assertTrue(
            honorificResults.any {
                it.text.contains("시간") || it.text.contains("뵙겠습니다") || it.text.contains("말씀드리겠습니다") || it.text.contains("회의")
            }
        )

        // Informal scheduling context
        val informalContext = "내일 몇 시에 볼까? 이번 주말에 시간 돼? ㅋㅋ"
        val informalResults = semanticPredictor.predictNextSentences(informalContext)
        assertTrue("Informal scheduling predictions must not be empty", informalResults.isNotEmpty())
        assertEquals(ContextualIntent.Scheduling, informalResults[0].intent)
        assertEquals(KoreanTone.Informal, informalResults[0].tone)
        assertTrue(
            informalResults.any {
                it.text.contains("보자") || it.text.contains("편해") || it.text.contains("만날까")
            }
        )
    }

    @Test
    fun testWorkProgressAndTechIntentPredictions() {
        // Business & Tech work progress context
        val workContext = "서버 배포 완료했고 머지 요청드렸습니다. 검토 부탁드립니다."
        val workResults = semanticPredictor.predictNextSentences(workContext)
        assertTrue(workResults.isNotEmpty())
        assertEquals(ContextualIntent.WorkProgress, workResults[0].intent)
        assertTrue(
            workResults.any {
                it.text.contains("피드백") || it.text.contains("모니터링") || it.text.contains("승인") || it.text.contains("배포")
            }
        )

        // Informal developer context
        val informalWork = "코드 수정해서 올렸어, 배포 확인해봐!"
        val informalWorkResults = semanticPredictor.predictNextSentences(informalWork)
        assertTrue(informalWorkResults.isNotEmpty())
        assertEquals(ContextualIntent.WorkProgress, informalWorkResults[0].intent)
        assertEquals(KoreanTone.Informal, informalWorkResults[0].tone)
        assertTrue(
            informalWorkResults.any {
                it.text.contains("머지할게") || it.text.contains("작동 중") || it.text.contains("확인해줘")
            }
        )
    }

    @Test
    fun testInquiryAndGratitudeIntentPredictions() {
        // Inquiry
        val inquiryContext = "자료 검토 진행 상황 확인 가능할까요?"
        val inquiryResults = semanticPredictor.predictNextSentences(inquiryContext)
        assertTrue(inquiryResults.isNotEmpty())
        assertEquals(ContextualIntent.Inquiry, inquiryResults[0].intent)
        assertTrue(
            inquiryResults.any {
                it.text.contains("확인 중") || it.text.contains("회신") || it.text.contains("공유")
            }
        )

        // Gratitude
        val gratitudeContext = "도와주셔서 정말 감사합니다, 덕분에 잘 끝났어요!"
        val gratitudeResults = semanticPredictor.predictNextSentences(gratitudeContext)
        assertTrue(gratitudeResults.isNotEmpty())
        assertEquals(ContextualIntent.Gratitude, gratitudeResults[0].intent)
        assertTrue(
            gratitudeResults.any {
                it.text.contains("기쁩니다") || it.text.contains("별말씀을요") || it.text.contains("수고 많으셨습니다")
            }
        )
    }

    @Test
    fun testConnectiveClausePredictions() {
        // Ending with '~는데'
        val connectiveContext = "지금 지하철 타고 이동 중인데"
        val results = semanticPredictor.predictNextSentences(connectiveContext)
        assertTrue(results.isNotEmpty())
        assertEquals(ContextualIntent.ConnectiveClause, results[0].intent)
        assertTrue(
            results.any {
                it.text.contains("연락드리겠습니다") || it.text.contains("기다려주시면") || it.text.contains("말씀드리겠습니다")
            }
        )
    }

    @Test
    fun testDynamicEntityExtractionAndSlotFillingSynthesis() {
        val complexContext = "내일 판교에서 3시에 회의하기로 했는데"
        val entities = semanticPredictor.extractEntities(complexContext)
        assertTrue("Should extract 판교 as place", entities.places.contains("판교"))
        assertTrue("Should extract 3시 as time", entities.times.contains("3시"))
        assertTrue("Should extract 내일 as time", entities.times.contains("내일"))
        assertTrue("Should extract 회의 as topic", entities.topics.contains("회의"))
        assertFalse(entities.isNegativeOrDeclining)

        val predictions = semanticPredictor.predictNextSentences(complexContext, limit = 5)
        assertTrue(predictions.isNotEmpty())
        assertTrue(
            "Synthesized slot-filled candidate should be present",
            predictions.any { it.text.contains("판교") || it.text.contains("회의") || it.badge.contains("맞춤AI") }
        )
    }

    @Test
    fun testNuancePolarityConsistencyDecliningGuard() {
        val decliningContext = "죄송하지만 이번 주말에는 다른 일정이 있어서 참석이 어려울 것 같습니다."
        val entities = semanticPredictor.extractEntities(decliningContext)
        assertTrue("Should detect negative/declining polarity", entities.isNegativeOrDeclining)

        val predictions = semanticPredictor.predictNextSentences(decliningContext, limit = 5)
        assertTrue(predictions.isNotEmpty())
        // Should recommend polite refusal / alternatives
        assertTrue(
            predictions.any {
                it.text.contains("다음") || it.text.contains("양해") || it.text.contains("조율")
            }
        )
        // Should NOT recommend positive confirmation
        assertFalse(predictions.any { it.text.contains("그때 뵙겠습니다") })
        assertFalse(predictions.any { it.text.contains("그렇게 진행하시죠") })
    }

    @Test
    fun testToneInferenceAccuracy() {
        assertEquals(KoreanTone.Honorific, semanticPredictor.inferTone("안녕하세요, 확인 부탁드립니다."))
        assertEquals(KoreanTone.Informal, semanticPredictor.inferTone("안녕! 오늘 밥 뭐 먹었어? ㅋㅋ"))
        assertEquals(KoreanTone.Technical, semanticPredictor.inferTone("배포 및 빌드 파이프라인 이슈"))
    }

    @Test
    fun testZeroLatencyPerformanceBenchmark() {
        val context = "오늘 회의에서 나온 안건 정리해서 공유드렸으니 확인 부탁드립니다."
        val startTime = System.nanoTime()
        for (i in 0 until 5000) {
            val results = semanticPredictor.predictNextSentences(context)
            assertNotNull(results)
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        assertTrue(
            "5000 semantic predictions must complete in under 1500ms (took ${elapsedMs}ms)",
            elapsedMs < 1500.0
        )
    }

    @Test
    fun testAiSentenceCompletionPrefetcherCaching() {
        val testContext = "내일 오전 10시에 회의 가능하신가요?"
        val syntheticPredictions = listOf(
            "네, 10시에 참석 가능합니다.",
            "일정 확인 후 바로 말씀드리겠습니다.",
            "그때 뵙겠습니다!"
        )

        prefetcher.putPredictions(testContext, syntheticPredictions)
        val cached = prefetcher.getCachedPredictions(testContext)
        assertNotNull(cached)
        assertEquals(3, cached!!.size)
        assertEquals("네, 10시에 참석 가능합니다.", cached[0])
    }

    @Test
    fun testAiContextualPredictorIntegrationWithSemanticEngine() {
        val context = "오늘 날씨가 너무 좋은데 주말에 같이 식사할까요?"
        val predictions = contextualPredictor.predict(
            currentStroke = "",
            contextBeforeCursor = context,
            packageName = "com.kakao.talk",
            limit = 4
        )

        assertTrue("Contextual predictions must not be empty", predictions.isNotEmpty())
        assertTrue(
            "Should propose full sentence completions based on content",
            predictions.any { it.isSentenceCompletion && (it.text.contains("식사") || it.text.contains("시간") || it.text.contains("좋은")) }
        )
    }

    @Test
    fun testIntentAndToneRecencyPriority() {
        // Multi-sentence context where history had work/meeting keywords, but recent sentence is gratitude in informal tone
        val mixedContext = "회의록 정리해서 공유드렸습니다. 오늘 도와줘서 정말 고마워"
        val tone = semanticPredictor.inferTone(mixedContext)
        val intent = semanticPredictor.inferIntent(mixedContext)

        assertEquals("Recent informal marker should outweigh past honorific tone", KoreanTone.Informal, tone)
        assertEquals("Recent gratitude segment should take priority over earlier meeting keyword", ContextualIntent.Gratitude, intent)

        val results = semanticPredictor.predictNextSentences(mixedContext)
        assertTrue(results.isNotEmpty())
        assertEquals(ContextualIntent.Gratitude, results[0].intent)
        assertEquals(KoreanTone.Informal, results[0].tone)

        // Mixed context with earlier gratitude, but recent sentence is scheduling question
        val schedulingShift = "정말 감사했습니다. 내일 3시에 판교에서 볼까?"
        val shiftTone = semanticPredictor.inferTone(schedulingShift)
        val shiftIntent = semanticPredictor.inferIntent(schedulingShift)

        assertEquals(KoreanTone.Informal, shiftTone)
        assertEquals(ContextualIntent.Scheduling, shiftIntent)

        val shiftResults = semanticPredictor.predictNextSentences(schedulingShift)
        assertTrue(shiftResults.isNotEmpty())
        assertEquals(ContextualIntent.Scheduling, shiftResults[0].intent)
    }

    @Test
    fun testContextDeduplicationAndContinuationProgression() {
        val completedGratitudeContext = "ㅎ 고마워 별거 아냐, 언제든 편하게 물어봐!"
        val intent = semanticPredictor.inferIntent(completedGratitudeContext)
        val tone = semanticPredictor.inferTone(completedGratitudeContext)

        // "언제든" should NOT trigger Scheduling, and gratitude response should transition to Farewell/Closing
        assertTrue("언제든 should not be classified as Scheduling", intent != ContextualIntent.Scheduling)
        assertEquals("Tone should remain Informal", KoreanTone.Informal, tone)

        val predictions = semanticPredictor.predictNextSentences(completedGratitudeContext, limit = 5)
        assertTrue("Predictions should not be empty", predictions.isNotEmpty())

        // Critical: Should NOT propose the exact sentence that was just committed/typed
        assertFalse(
            "Must NOT recommend a sentence already present in context",
            predictions.any { it.text.contains("별거 아냐") }
        )

        // Should propose conversational continuation
        assertTrue(
            "Should recommend continuation sentences (farewell or secondary gratitude)",
            predictions.any {
                it.text.contains("들어가") || it.text.contains("다음에") || it.text.contains("다행") || it.text.contains("고생")
            }
        )
    }

    @Test
    fun testConnectiveClauseTrafficAndWorkContext() {
        val trafficContextFormal = "차가 너무 막혀서"
        val trafficPredictionsFormal = semanticPredictor.predictNextSentences(trafficContextFormal, limit = 4)
        assertTrue(trafficPredictionsFormal.isNotEmpty())
        assertTrue(
            "Traffic delay clause should suggest apology / delay notice",
            trafficPredictionsFormal.any { it.text.contains("늦") || it.text.contains("지연") || it.text.contains("죄송") }
        )

        val trafficContextInformal = "차가 너무 막혀서 조금 늦을 것 같아"
        val trafficPredictionsInformal = semanticPredictor.predictNextSentences(trafficContextInformal, limit = 4)
        assertTrue(
            "Informal delay should suggest natural friendly arrival heads-up",
            trafficPredictionsInformal.any { it.text.contains("미안") || it.text.contains("도착") || it.text.contains("갈게") || it.text.contains("있어") }
        )

        val workContext = "회의가 길어져서"
        val workPredictions = semanticPredictor.predictNextSentences(workContext, limit = 4)
        assertTrue(
            "Work meeting delay should suggest post-meeting sharing / reporting",
            workPredictions.any { it.text.contains("종료") || it.text.contains("공유") || it.text.contains("회신") || it.text.contains("보고") }
        )

        val enrouteContext = "지금 가는 중인데"
        val enroutePredictions = semanticPredictor.predictNextSentences(enrouteContext, limit = 4)
        assertTrue(
            "En-route context should suggest arrival notification",
            enroutePredictions.any { it.text.contains("도착") || it.text.contains("연락") }
        )
    }

    @Test
    fun testTimeAndPlaceSlotFillingAndTypingProgression() {
        // "내일 판교" context without topic -> MUST synthesize tailored predictions containing both "내일" and "판교"
        val context = "내일 판교"
        val predictions = semanticPredictor.predictNextSentences(context, limit = 4)
        assertTrue("Predictions for '내일 판교' must not be empty", predictions.isNotEmpty())
        assertTrue(
            "Must contain tailored Time + Place prediction with 판교 and 뵙겠습니다/보자",
            predictions.any { it.text.contains("판교") && (it.text.contains("뵙겠습니다") || it.text.contains("보자") || it.text.contains("몇 시")) }
        )
        assertEquals(ContextualIntent.Scheduling, predictions[0].intent)

        // Typing stroke progression: "내일 판교 " with currentStroke "몇"
        val strokePredictions = semanticPredictor.predictNextSentences(
            contextBeforeCursor = "내일 판교 ",
            currentStroke = "몇",
            limit = 4
        )
        assertTrue("Predictions with stroke '몇' must not be empty", strokePredictions.isNotEmpty())
        assertTrue(
            "Must prioritize candidate matching stroke '몇'",
            strokePredictions.any { it.text.contains("몇 시") }
        )

        // "오늘 저녁 판교"
        val compoundTimePredictions = semanticPredictor.predictNextSentences("오늘 저녁 판교", limit = 4)
        assertTrue(
            "Must synthesize compound time '오늘 저녁' with '판교'",
            compoundTimePredictions.any { it.text.contains("오늘 저녁") && it.text.contains("판교") }
        )
    }

    @Test
    fun testAgreementIntentPredictions() {
        val honorificAgreement = "말씀해주신 제안에 전적으로 동의합니다."
        val honorificResults = semanticPredictor.predictNextSentences(honorificAgreement)
        assertTrue(honorificResults.isNotEmpty())
        assertEquals(ContextualIntent.Agreement, honorificResults[0].intent)
        assertEquals(KoreanTone.Honorific, honorificResults[0].tone)
        assertTrue(honorificResults.any { it.text.contains("진행하겠습니다") || it.text.contains("추진하시죠") || it.badge == "동의/확인" })

        val informalAgreement = "나도 그 생각에 동의해. 오케이 좋아!"
        val informalResults = semanticPredictor.predictNextSentences(informalAgreement)
        assertTrue(informalResults.isNotEmpty())
        assertEquals(ContextualIntent.Agreement, informalResults[0].intent)
        assertEquals(KoreanTone.Informal, informalResults[0].tone)
        assertTrue(informalResults.any { it.text.contains("진행하자") || it.text.contains("동의해") || it.text.contains("진행할게") })
    }

    @Test
    fun testCheeringIntentPredictions() {
        val honorificCheering = "이번 프로젝트 성공과 승진 진심으로 축하드립니다!"
        val results = semanticPredictor.predictNextSentences(honorificCheering)
        assertTrue(results.isNotEmpty())
        assertEquals(ContextualIntent.Cheering, results[0].intent)
        assertTrue(results.any { it.text.contains("축하") || it.text.contains("응원") || it.badge == "응원/축하" })

        val informalCheering = "시험 합격했다며! 완전 축하해 파이팅!"
        val informalResults = semanticPredictor.predictNextSentences(informalCheering)
        assertTrue(informalResults.isNotEmpty())
        assertEquals(ContextualIntent.Cheering, informalResults[0].intent)
        assertEquals(KoreanTone.Informal, informalResults[0].tone)
        assertTrue(informalResults.any { it.text.contains("축하") || it.text.contains("대단") || it.text.contains("파이팅") })
    }

    @Test
    fun testStatusUpdateIntentPredictions() {
        val honorificStatus = "지금 회사 로비에 도착했습니다."
        val results = semanticPredictor.predictNextSentences(honorificStatus)
        assertTrue(results.isNotEmpty())
        assertEquals(ContextualIntent.StatusUpdate, results[0].intent)
        assertTrue(results.any { it.text.contains("도착") || it.text.contains("이동") || it.badge == "현황/보고" })
    }

    @Test
    fun testRequestIntentPredictions() {
        val requestContext = "시간 되실 때 기획안 검토 부탁드립니다."
        val results = semanticPredictor.predictNextSentences(requestContext)
        assertTrue(results.isNotEmpty())
        assertEquals(ContextualIntent.Request, results[0].intent)
        assertTrue(results.any { it.text.contains("검토") || it.text.contains("공유") || it.badge == "정중요청" })
    }

    @Test
    fun testTimeBasedGreetings() {
        val morningCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.TUESDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
        }
        val morningGreetings = semanticPredictor.getTimeBasedGreetings(isInformal = false, calendar = morningCal)
        assertTrue(morningGreetings.any { it.contains("좋은 아침") || it.contains("활기찬 하루") })

        val lunchCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.WEDNESDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 12)
        }
        val lunchGreetings = semanticPredictor.getTimeBasedGreetings(isInformal = true, calendar = lunchCal)
        assertTrue(lunchGreetings.any { it.contains("점심") || it.contains("맛점") })

        val weekendCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SATURDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 14)
        }
        val weekendGreetings = semanticPredictor.getTimeBasedGreetings(isInformal = false, calendar = weekendCal)
        assertTrue(weekendGreetings.any { it.contains("주말") })
    }
}
