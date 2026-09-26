/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.graph.EdgeInfo
import org.fcitx.fcitx5.android.input.ai.graph.EntityInfo
import org.fcitx.fcitx5.android.input.ai.graph.HippoRagPprEngine
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceL1GraphCache
import org.fcitx.fcitx5.android.input.ai.memory.PersonaTone
import org.fcitx.fcitx5.android.input.ai.memory.TieredMemoryManager
import org.fcitx.fcitx5.android.input.ai.memory.TpoContextEncoder
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.RuleResult
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.ViolationType
import org.fcitx.fcitx5.android.input.ai.verifier.ThreeStageOutputVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * Red Team Round 5 Extreme Deep Adversarial Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-DEEP-01: 7 Irregular verbal conjugations & ending decomposition destruction.
 * 2. RED-DEEP-02: Complex auxiliary particle (josa) chains & coda resolution ambiguity.
 * 3. RED-DEEP-03: Three-Stage Verifier sub-6.0ms high-stress latency & stage rejection.
 * 4. RED-DEEP-04: Ultra-rapid TPO context switching & 4-tier memory real-time session isolation.
 * 5. RED-DEEP-05: HippoRAG Multi-Seed PPR query storm (500 nodes, 1,500 edges, 100 rounds).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound5DeepAdversarialDeviceTest {

    // =========================================================================
    // 1. RED-DEEP-01: 불규칙 용언 활용 & 어미 결합 파괴
    // =========================================================================
    @Test
    fun testRedDeep01_IrregularConjugationAndEndingDestruction() {
        // --- (1) 7대 불규칙 활용형 올바른 형태(O) vs 비문법적 형태(X) 쌍 검증 ---
        val irregularPairs = listOf(
            // ㄷ 불규칙: 걷다 -> 걸어서 / 걷어서, 듣다 -> 들으니 / 듣으니
            Pair("걸어서", "걷어서"),
            Pair("들으니", "듣으니"),
            // ㅂ 불규칙: 돕다 -> 도와서 / 돕아서, 춥다 -> 추워서 / 춥어서, 아름답다 -> 아름다워서 / 아름답아서
            Pair("도와서", "돕아서"),
            Pair("추워서", "춥어서"),
            Pair("아름다워서", "아름답아서"),
            // ㅅ 불규칙: 짓다 -> 지어서 / 짓어서, 낫다 -> 나아서 / 낫아서
            Pair("지어서", "짓어서"),
            Pair("나아서", "낫아서"),
            // 르 불규칙: 흐르다 -> 흘러서 / 흐러서, 빠르다 -> 빨라서 / 빠라서
            Pair("흘러서", "흐러서"),
            Pair("빨라서", "빠라서"),
            // ㅎ 불규칙: 하얗다 -> 하얘서 / 하얗아서, 파랗다 -> 파래서 / 파랗아서
            Pair("하얘서", "하얗아서"),
            Pair("파래서", "파랗아서"),
            // 우 불규칙: 푸다 -> 퍼서 / 푸어서
            Pair("퍼서", "푸어서"),
            // 여 불규칙: 하다 -> 해서 / 하어서
            Pair("해서", "하어서")
        )

        for ((validForm, invalidForm) in irregularPairs) {
            // 올바른 활용형은 비문으로 판별되지 않아야 함
            assertFalse(
                "올바른 활용형 '$validForm'은 비문으로 판정되어서는 안 됩니다.",
                KoreanMorphologicalEndingAnalyzer.isInvalidIrregularConjugation(validForm)
            )
            // 잘못된 활용형은 비문으로 감지되어야 함
            assertTrue(
                "비문법적 활용형 '$invalidForm'은 비문으로 감지되어야 합니다.",
                KoreanMorphologicalEndingAnalyzer.isInvalidIrregularConjugation(invalidForm)
            )
            // 비문형은 extractEnding 시 null을 반환해야 함
            assertNull(
                "비문법적 활용형 '$invalidForm'은 어미가 정상 추출되어서는 안 됩니다 (null 반환 필요).",
                KoreanMorphologicalEndingAnalyzer.extractEnding(invalidForm)
            )
        }

        // --- (2) extractEnding() 종결/접속 어미 정확 추출 검증 ---
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("걸어서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("도와서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("지어서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("흘러서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("하얘서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("추워서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("빨라서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("퍼서"))
        assertEquals("여서", KoreanMorphologicalEndingAnalyzer.extractEnding("해서"))
        assertEquals("으니", KoreanMorphologicalEndingAnalyzer.extractEnding("들으니"))

        // 존칭 결합형 검증
        assertEquals("아서요", KoreanMorphologicalEndingAnalyzer.extractEnding("도와서요"))
        assertEquals("어서요", KoreanMorphologicalEndingAnalyzer.extractEnding("흘러서요"))
        assertEquals("아서요", KoreanMorphologicalEndingAnalyzer.extractEnding("하얘서요"))

        // --- (3) KoreanSyntaxRuleFilter.check() 이유절-결과 호응(ACC-01) 정상 판단 검증 ---
        val syntaxFilter = KoreanSyntaxRuleFilter()

        // ACC-01 위반 (불규칙 이유절 뒤 명령/청유/의문문 결합)
        val acc01Violations = listOf(
            "물이 넘쳐흘러서 지금 출발하세요",
            "날씨가 너무 추워서 외투를 입으세요",
            "빨리 도와서 끝내자",
            "얼굴이 하얘서 병원에 가보세요",
            "시간이 빨라서 서두르십시오",
            "물을 퍼서 마시지 마세요",
            "회의가 늦어져서 지금 출발하세요"
        )

        for (sentence in acc01Violations) {
            val result = syntaxFilter.check(sentence)
            assertTrue(
                "문장 '$sentence'는 ACC-01 위반으로 기각되어야 합니다.",
                result is RuleResult.Invalid && result.violationType == ViolationType.ACC_01_CAUSAL_SUBORDINATION
            )
        }

        // 정상 문장 (불규칙 이유절 뒤 평서문 결합 -> 통과)
        val acc01ValidSentences = listOf(
            "물이 흘러서 바다로 갑니다",
            "날씨가 추워서 외투를 입었습니다",
            "도와주셔서 진심으로 감사합니다",
            "얼굴이 하얘서 건강해 보입니다",
            "시간이 빨라서 먼저 일어났습니다"
        )

        for (sentence in acc01ValidSentences) {
            val result = syntaxFilter.check(sentence)
            assertTrue(
                "정상 문장 '$sentence'는 통과(Valid)되어야 합니다. Actual: $result",
                result is RuleResult.Valid
            )
        }
    }

    // =========================================================================
    // 2. RED-DEEP-02: 복합 보조사 체인 결합 & 종성 모호성
    // =========================================================================
    @Test
    fun testRedDeep02_ComplexAuxiliaryJosaChainAndCodaAmbiguity() {
        // --- (1) 체언 + 격조사/보조사 연쇄 뒤의 조사 판별 ---
        // '서'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("학교에서", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("집에서", JosaKind.EUN_NEUN))
        // '터'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("마을로부터", JosaKind.EUN_NEUN))
        // '게'는 받침 없음 -> "를"
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("선생님에게", JosaKind.EUL_REUL))
        // '로'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("값으로", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("닭으로", JosaKind.EUN_NEUN))

        // isValidAttachment 검증
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("학교에서", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("학교에서", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("마을로부터", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("마을로부터", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("선생님에게", "를"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("선생님에게", "을"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("값으로", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("값으로", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("닭으로", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("닭으로", "은"))

        // --- (2) correctJosaMismatch() 불일치 감지 및 자동 교정 검증 ---
        assertEquals("학교에서는", KoreanJosaBitmaskEngine.correctJosaMismatch("학교에서은"))
        assertEquals("마을로부터는", KoreanJosaBitmaskEngine.correctJosaMismatch("마을로부터은"))
        assertEquals("선생님에게를", KoreanJosaBitmaskEngine.correctJosaMismatch("선생님에게을"))
        assertEquals("값으로는", KoreanJosaBitmaskEngine.correctJosaMismatch("값으로은"))
        assertEquals("닭으로는", KoreanJosaBitmaskEngine.correctJosaMismatch("닭으로은"))

        // hasJosaMismatch 검증
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("학교에서은"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("학교에서는"))
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("마을로부터은"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("마을로부터는"))
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("선생님에게을"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("선생님에게를"))

        // --- (3) 복합 조사 '으로부터 / 로부터' 확장 체인 검증 ---
        assertEquals("로부터", KoreanJosaBitmaskEngine.selectJosa("마을", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("집", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("로부터", KoreanJosaBitmaskEngine.selectJosa("학교", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("값", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("닭", JosaKind.EUROBUTEOR_ROBUTEOR))

        assertEquals("마을로부터", KoreanJosaBitmaskEngine.correctJosaMismatch("마을으로부터"))
        assertEquals("집으로부터", KoreanJosaBitmaskEngine.correctJosaMismatch("집로부터"))
    }

    // =========================================================================
    // 3. RED-DEEP-03: 3단계 Verifier 서브 6.0ms 판별 스트레스
    // =========================================================================
    @Test
    fun testRedDeep03_ThreeStageVerifierSub6msStress() {
        val verifier = ThreeStageOutputVerifier()

        // 정문 5종
        val validSentences = listOf(
            "내일 오전 회의에 참석하겠습니다",
            "자료를 꼼꼼하게 검토했습니다",
            "지금 바로 사무실로 출발하겠습니다",
            "도움 주셔서 진심으로 감사합니다",
            "오늘 점심 맛있게 드세요"
        )

        // 비문 5종 (기각 사유 및 스테이지 명시)
        val invalidSentences = listOf(
            Triple("회의가 늦어져서 지금 출발하세요", 1, "ACC-01"),
            Triple("따뜻한 은혜를 너무나 고마워요", 1, "ACC-02"),
            Triple("내가 뭘 보고서를 작성합니다", 1, "ACC-03"),
            Triple("안녕하십니까 밥 먹었어?", 1, "ACC-04"),
            Triple("회의를 참석해요", 2, "High PPL")
        )

        // JIT 웜업
        for (sentence in validSentences) {
            verifier.verify("", sentence)
        }
        for ((sentence, _, _) in invalidSentences) {
            verifier.verify("", sentence)
        }

        // --- 전수 정밀 측정 및 판정 단언 ---
        // 1) 정문 5종 단언 및 지연시간 측정
        for (sentence in validSentences) {
            val elapsedNanos = measureNanoTime {
                val result = verifier.verify("", sentence)
                assertTrue("정문 '$sentence'는 유효(isValid=true)해야 합니다.", result.isValid)
                assertTrue(
                    "정문 '$sentence' 점수는 >= 0.5f 여야 합니다. Actual: ${result.score}",
                    result.score >= 0.5f
                )
            }
            val elapsedMs = elapsedNanos / 1_000_000.0
            assertTrue(
                "정문 '$sentence' 검증 시간은 < 6.0ms 여야 합니다. Actual: ${elapsedMs}ms",
                elapsedMs < 6.0
            )
        }

        // 2) 비문 5종 단언 및 지연시간 측정
        for ((sentence, expectedStage, reasonCode) in invalidSentences) {
            val elapsedNanos = measureNanoTime {
                val result = verifier.verify("", sentence)
                assertFalse("비문 '$sentence'는 기각(isValid=false)되어야 합니다.", result.isValid)
                assertEquals(
                    "비문 '$sentence' ($reasonCode)는 Stage $expectedStage 에서 기각되어야 합니다.",
                    expectedStage,
                    result.stage
                )
            }
            val elapsedMs = elapsedNanos / 1_000_000.0
            assertTrue(
                "비문 '$sentence' 검증 시간은 < 6.0ms 여야 합니다. Actual: ${elapsedMs}ms",
                elapsedMs < 6.0
            )
        }
    }

    // =========================================================================
    // 4. RED-DEEP-04: 초고속 TPO 문맥 전환 & 4-Tier Memory 실시간 세션 격리
    // =========================================================================
    @Test
    fun testRedDeep04_RapidTpoContextSwitchAndMemorySessionIsolation() {
        val tpoEncoder = TpoContextEncoder()
        val memory = TieredMemoryManager()

        // 50회 연속 고속 전환 루프 (Slack -> KakaoTalk -> Search)
        repeat(50) { iteration ->
            // Step A: Slack (FORMAL_BUSINESS)
            val slackContext = tpoEncoder.encode("com.slack")
            assertEquals(PersonaTone.FORMAL_BUSINESS, slackContext.tone)

            memory.updateL1Buffer("오전 회의 안건 관련하여 ")
            memory.addSessionUtterance("2분기 실적 보고서 검토 완료했습니다 (회차: $iteration)")

            val slackUtterances = memory.getSessionUtterances()
            assertTrue(
                "Slack 세션 발화가 L2에 기록되어야 합니다.",
                slackUtterances.any { it.contains("2분기 실적 보고서") }
            )

            // 세션 전환: L1 버퍼 플러시 및 L2 세션 분리
            memory.clearL1Buffer()
            memory.clearSession()
            assertEquals("L1 버퍼는 비워져야 합니다.", "", memory.getL1Buffer())
            assertTrue("L2 세션은 격리 초기화되어야 합니다.", memory.getSessionUtterances().isEmpty())

            // Step B: KakaoTalk (CASUAL_CHAT)
            val kakaoContext = tpoEncoder.encode("com.kakao.talk")
            assertEquals(PersonaTone.CASUAL_CHAT, kakaoContext.tone)

            // 슬랙 업무 발화가 카카오톡 세션으로 교차 누출되지 않음을 단언
            val kakaoInitialUtterances = memory.getSessionUtterances()
            assertFalse(
                "Slack 업무 발화가 Kakao 세션으로 교차 누출되어서는 안 됩니다.",
                kakaoInitialUtterances.any { it.contains("실적 보고서") }
            )

            memory.updateL1Buffer("오늘 저녁 ")
            memory.addSessionUtterance("치킨 먹으러 갈래? (회차: $iteration)")

            val kakaoUtterances = memory.getSessionUtterances()
            assertTrue(
                "Kakao 발화가 L2에 기록되어야 합니다.",
                kakaoUtterances.any { it.contains("치킨 먹으러 갈래") }
            )

            // 세션 전환
            memory.clearL1Buffer()
            memory.clearSession()

            // Step C: Google Search (CONCISE_SEARCH)
            val searchContext = tpoEncoder.encode("com.google.android.googlequicksearchbox")
            assertEquals(PersonaTone.CONCISE_SEARCH, searchContext.tone)

            // 슬랙 및 카카오톡 발화가 검색 세션으로 교차 누출되지 않음을 단언
            val searchInitialUtterances = memory.getSessionUtterances()
            assertFalse(
                "Slack/Kakao 발화가 검색 세션으로 교차 누출되어서는 안 됩니다.",
                searchInitialUtterances.any { it.contains("실적 보고서") || it.contains("치킨") }
            )

            memory.updateL1Buffer("강남역 맛집")
            memory.addSessionUtterance("서울 강남구 현재 기온 및 날씨 (회차: $iteration)")

            // 세션 전환
            memory.clearL1Buffer()
            memory.clearSession()
        }

        // 50회 순환 완료 후 메모리 예산(35MB) 안전성 검증
        val finalMemoryBytes = memory.getEstimatedMemoryBytes()
        assertTrue(
            "50회 TPO 세션 순환 후에도 메모리는 35MB 한도를 엄격히 준수해야 합니다. Actual: ${finalMemoryBytes / 1024}KB",
            finalMemoryBytes < 35L * 1024 * 1024
        )
    }

    // =========================================================================
    // 5. RED-DEEP-05: HippoRAG Multi-Seed PPR 질의 폭풍
    // =========================================================================
    @Test(timeout = 30000)
    fun testRedDeep05_HippoRagMultiSeedPprStorm() {
        val graphCache = OnDeviceL1GraphCache()

        // 1) 500개 노드 생성
        for (i in 0 until 500) {
            graphCache.putEntity(
                EntityInfo(
                    id = "entity_$i",
                    label = "엔티티_$i",
                    category = if (i % 3 == 0) "CONCEPT" else if (i % 3 == 1) "PERSON" else "LOCATION",
                    weight = 1.0f
                )
            )
        }

        // 2) 1,500개 엣지 생성 (50개 국소 에고 서브그래프 클러스터 x 30개 엣지 = 1,500개)
        for (c in 0 until 50) {
            val baseNode = c * 10
            for (e in 0 until 30) {
                val src = "entity_${baseNode + (e % 10)}"
                val dst = "entity_${baseNode + ((e * 3 + 1) % 10)}"
                graphCache.putEdge(
                    EdgeInfo(
                        src = src,
                        dst = dst,
                        relation = "CONNECTED",
                        weight = 1.0f
                    )
                )
            }
        }

        // 3) 10개의 분산된 Seed 엔티티 구성
        val seedEntities = (0..9).map { "entity_${it * 50}" }.toSet()
        assertEquals("10개의 분산 시드 엔티티가 준비되어야 합니다.", 10, seedEntities.size)

        val pprEngine = HippoRagPprEngine(
            graphCache = graphCache,
            dampingFactor = 0.85f,
            maxIterations = 3
        )

        // 웜업 30회 (ART JIT 최적화 컴파일 완료 보장)
        repeat(30) {
            pprEngine.computePpr(seedEntities, maxResults = 10, excludeSeeds = true)
        }

        // 4) 100회 연속 고속 PPR 쿼리 스트레스 테스트
        val durationsNanos = LongArray(100)
        for (round in 0 until 100) {
            val start = System.nanoTime()
            val results = pprEngine.computePpr(seedEntities, maxResults = 10, excludeSeeds = true)
            durationsNanos[round] = System.nanoTime() - start

            // 결과 검증: 결과가 비어있지 않아야 함
            assertTrue("PPR 결과가 비어있지 않아야 합니다 (회차: $round).", results.isNotEmpty())
            // 내림차순 정렬 검증
            for (k in 0 until results.size - 1) {
                assertTrue(
                    "PPR 결과는 점수 내림차순이어야 합니다.",
                    results[k].second >= results[k + 1].second
                )
            }
            // 시드 엔티티는 결과에 포함되지 않아야 함 (excludeSeeds=true)
            for ((entityId, _) in results) {
                assertFalse("시드 엔티티는 결과에서 제외되어야 합니다.", seedEntities.contains(entityId))
            }
        }

        // 5) 수렴 지연시간 단언
        val avgMs = durationsNanos.average() / 1_000_000.0
        val maxMs = (durationsNanos.maxOrNull() ?: 0L) / 1_000_000.0

        println("HippoRAG 100-Round Multi-Seed PPR Latency - Avg: %.3f ms, Max: %.3f ms".format(avgMs, maxMs))

        assertTrue(
            "10-Seed PPR 평균 질의 응답 시간은 < 5.0ms 여야 합니다. Actual: ${avgMs}ms",
            avgMs < 5.0
        )
        assertTrue(
            "10-Seed PPR 최대 질의 응답 시간은 < 10.0ms 여야 합니다. Actual: ${maxMs}ms",
            maxMs < 10.0
        )
    }
}
