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
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalGuardian
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/**
 * Red Team Round 3 Extreme Adversarial Device Instrumentation Test.
 *
 * Exhaustive adversarial attack scenarios against on-device AI and engine components:
 * - RED-PHONO-HARD: Emoji, consonant abbreviations, rare coda, symbol/currency attachment destruction.
 * - RED-SYNTAX-HARD: Subordinate causal connective precision discrimination & accusative-intransitive discord.
 * - RED-CONCURRENCY-BLAST: Multi-threaded 8-worker L1 cache data race & stress bombardment.
 * - RED-GRAPH-CYCLE-BLAST: Self-loop, triangular cyclic graph, and 100-isolated-node PPR divergence test.
 * - RED-EXTREME-THERMAL-OVERHEAT: 45.0°C CRITICAL thermal throttling & dynamic recovery regulation.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamAdversarialDeviceTest {

    // =========================================================================
    // a. RED-PHONO-HARD: 이모지, 자음축약어, 희귀 받침, 숫자/기호 종성 결합 파괴 공격
    // =========================================================================
    @Test
    fun testRedPhonoHard_AdversarialEmojiJamoAndRareJongseongAttack() {
        // 1. 희귀 및 된소리 종성 결합 공격 검증
        // 솥(ㅌ받침: 25) -> '으로'(O) vs '로'(X)
        assertTrue("솥 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("솥", "으로"))
        assertFalse("솥 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("솥", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("솥", JosaKind.EURO_RO))
        assertEquals("솥으로", KoreanJosaBitmaskEngine.attachJosa("솥", JosaKind.EURO_RO))

        // 꽃(ㅊ받침: 23) -> '을'(O) vs '를'(X)
        assertTrue("꽃 뒤에는 '을'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("꽃", "을"))
        assertFalse("꽃 뒤에는 '를'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("꽃", "를"))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("꽃", JosaKind.EUL_REUL))
        assertEquals("꽃을", KoreanJosaBitmaskEngine.attachJosa("꽃", JosaKind.EUL_REUL))

        // 빛(ㅊ받침: 23) -> '으로'(O) vs '빛로'(X)
        assertTrue("빛 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("빛", "으로"))
        assertFalse("빛 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("빛", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("빛", JosaKind.EURO_RO))
        assertEquals("빛으로", KoreanJosaBitmaskEngine.attachJosa("빛", JosaKind.EURO_RO))

        // 2. 기호 및 금액 종성 결합 공격 검증 ('원'은 ㄴ받침: 4)
        // 100원 -> '은'(O), 1000원 -> '은'(O), 1000000원 -> '으로'(O) vs '1000000원로'(X)
        assertTrue("100원 뒤에는 '은'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("100원", "은"))
        assertFalse("100원 뒤에는 '는'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("100원", "는"))
        assertTrue("1000원 뒤에는 '은'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("1000원", "은"))
        assertFalse("1000원 뒤에는 '는'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("1000원", "는"))
        assertTrue("1000000원 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("1000000원", "으로"))
        assertFalse("1000000원 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("1000000원", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("1000000원", JosaKind.EURO_RO))
        assertEquals("1000000원으로", KoreanJosaBitmaskEngine.attachJosa("1000000원", JosaKind.EURO_RO))

        // 희귀 종성 및 금액 문맥 오결합 자동 교정 검증
        val rareWrongSentence = "솥로 밥을 짓고 꽃를 보며 빛로 걸어갔다"
        val rareCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(rareWrongSentence)
        assertEquals("솥으로 밥을 짓고 꽃을 보며 빛으로 걸어갔다", rareCorrected)

        val moneyWrongSentence = "100원는 적지만 1000원는 크고 1000000원로 정산했다"
        val moneyCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(moneyWrongSentence)
        assertEquals("100원은 적지만 1000원은 크고 1000000원으로 정산했다", moneyCorrected)

        // 3. 이모지 뒤 조사 결합 공격: 👍는(O) vs 👍은(X), ❤️를(O) vs ❤️을(X), 🎉로(O) vs 🎉으로(X)
        // [레드팀 취약점 탐지]: 이모지(Surrogate pair / Unicode symbol) 결합 시의 조사 교정 검증
        val emojiWrong = "👍은 최고이고 ❤️을 보내며 🎉으로 축하합니다"
        val emojiCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(emojiWrong)
        assertEquals("👍는 최고이고 ❤️를 보내며 🎉로 축하합니다", emojiCorrected)

        // 4. 자음 축약어 결합 공격: ㅋㅋ는(O) vs ㅋㅋ은(X), ㅇㅈ을(O, 지읒 받침), ㄹㅇ으로(O, 이응 받침), ㄱㄹ로(O, 리을 받침)
        // [레드팀 취약점 탐지]: 호환 자모(ㅋ, ㅈ, ㅇ, ㄹ) 종성 음운 해석 및 자동 교정 검증
        val jamoWrong = "ㅋㅋ은 웃기고 ㅇㅈ를 외치며 ㄹㅇ로 진짜고 ㄱㄹ으로 간다"
        val jamoCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(jamoWrong)
        assertEquals("ㅋㅋ는 웃기고 ㅇㅈ을 외치며 ㄹㅇ으로 진짜고 ㄱㄹ로 간다", jamoCorrected)
    }

    // =========================================================================
    // b. RED-SYNTAX-HARD: 복합 접속어미의 정밀 구문 판별 공격
    // =========================================================================
    @Test
    fun testRedSyntaxHard_ComplexConnectiveAndSyntacticSoundnessAttack() {
        // 1. -어서/아서/여서 vs -으니까/니까 정밀 구문 판별 공격
        // Rule: -어서/아서/여서는 청유/의문/명령 결합 불가(비문 차단), -으니까/니까는 청유/의문/명령 완전 허용(정문 통과)

        // Case 1: "비가 오니까 우산 챙기세요" (정문 -> 통과)
        assertTrue(
            "'-니까' 뒤 명령문('챙기세요')은 문법적으로 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("비가 오니까 우산 챙기세요")
        )

        // Case 2: "비가 와서 우산 챙기세요" (비문 -> 차단)
        assertFalse(
            "'-아서/어서' 뒤 명령문('챙기세요')은 구문상 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("비가 와서 우산 챙기세요")
        )

        // Case 3: "시간이 없으니까 빨리 가자" (정문 -> 통과)
        assertTrue(
            "'-니까' 뒤 청유문('가자')은 문법적으로 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("시간이 없으니까 빨리 가자")
        )

        // Case 4: "시간이 없어서 빨리 가자" (비문 -> 차단)
        assertFalse(
            "'-아서/어서' 뒤 청유문('가자')은 구문상 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("시간이 없어서 빨리 가자")
        )

        // 2. 부사격 조사 '으로' vs 목적격 조사 '을/를' + 감사/고마움 술어 결합 공격
        // Case 5: "감사한 마음으로 인사드립니다" (정문 -> 통과)
        assertTrue(
            "부사격 조사 '으로' 결합 인사/감사는 문법적으로 완전한 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("감사한 마음으로 인사드립니다")
        )

        // Case 6: "감사한 마음을 너무나 고마워요" (비문 -> 차단)
        assertFalse(
            "목적격 조사 '을/를' 뒤 자동사/형용사 술어('고마워요') 직접 결합은 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("감사한 마음을 너무나 고마워요")
        )
    }

    // =========================================================================
    // c. RED-CONCURRENCY-BLAST: 멀티스레드 동시성 데이터 레이스 공격
    // =========================================================================
    @Test(timeout = 30000)
    fun testRedConcurrencyBlast_MultiThreadDataRaceAttack() {
        val cache = OnDeviceL1GraphCache()
        val threadCount = 8
        val iterationsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val exceptions = ConcurrentLinkedQueue<Throwable>()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until iterationsPerThread) {
                        val entityId = "node_${t}_${i % 50}"
                        val targetId = "node_${(t + 1) % threadCount}_${(i + 1) % 50}"

                        // 1. Concurrent Put Entity
                        val entity = EntityInfo(
                            id = entityId,
                            label = "Label_$entityId",
                            category = "Category_${i % 5}",
                            weight = 1.0f + (i % 10) * 0.1f,
                            lastSeenEpoch = System.currentTimeMillis()
                        )
                        cache.putEntity(entity)

                        // 2. Concurrent Put Edge
                        val edge = EdgeInfo(
                            src = entityId,
                            dst = targetId,
                            relation = "REL_${i % 3}",
                            weight = 0.5f + (i % 4) * 0.2f,
                            frequency = i,
                            lastUpdatedEpoch = System.currentTimeMillis()
                        )
                        cache.putEdge(edge)

                        // 3. Concurrent Read 1-hop
                        val neighbors = cache.get1Hop(entityId)
                        assertNotNull("Neighbors should not be null", neighbors)

                        // 4. Concurrent Read Entity
                        val retrieved = cache.getEntity(entityId)
                        assertNotNull("Entity should be retrieved", retrieved)

                        // 5. Periodic full-read scan (ConcurrentModificationException 트리거 유도)
                        if (i % 100 == 0) {
                            val all = cache.getAllEntities()
                            assertTrue("All entities collection should be populated", all.isNotEmpty())
                        }
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 폭격 개시 동시 방아쇠 트리거
        startLatch.countDown()
        val finishedInTime = doneLatch.await(20, TimeUnit.SECONDS)
        executor.shutdown()

        // Deadlock 0건 검증
        assertTrue("Deadlock detected! Threads failed to complete within 20s", finishedInTime)

        // ConcurrentModificationException 및 동시성 예외 0건 검증
        if (exceptions.isNotEmpty()) {
            val first = exceptions.peek()
            throw AssertionError("Concurrent modification or data race error: ${first?.message}", first)
        }
        assertTrue("Exceptions list must be strictly empty", exceptions.isEmpty())

        // 최종 상태 무결성 검증
        val finalEntities = cache.getAllEntities()
        assertTrue("L1 cache must retain written entities", finalEntities.isNotEmpty())
        for (t in 0 until threadCount) {
            val sampleNode = "node_${t}_0"
            assertNotNull("Sample node $sampleNode must exist", cache.getEntity(sampleNode))
            val sampleEdges = cache.get1Hop(sampleNode)
            assertTrue("Sample node $sampleNode must have neighbor edges", sampleEdges.isNotEmpty())
        }
    }

    // =========================================================================
    // d. RED-GRAPH-CYCLE-BLAST: 그래프 자기순환 및 다중 루프 PPR 발산 공격
    // =========================================================================
    @Test
    fun testRedGraphCycleBlast_PprDivergenceAndLoopAttack() {
        val cache = OnDeviceL1GraphCache()

        // 1. 노드 생성
        val entityA = EntityInfo("A", "Node A", "Core")
        val entityB = EntityInfo("B", "Node B", "Core")
        val entityC = EntityInfo("C", "Node C", "Core")
        cache.putEntity(entityA)
        cache.putEntity(entityB)
        cache.putEntity(entityC)

        // 2. Self-loop ($A -> A$) 주입
        cache.putEdge(EdgeInfo("A", "A", "self_loop", weight = 1.0f))

        // 3. 3각 순환 루프 ($A -> B -> C -> A$) 주입
        cache.putEdge(EdgeInfo("A", "B", "cycle_ab", weight = 1.0f))
        cache.putEdge(EdgeInfo("B", "C", "cycle_bc", weight = 1.0f))
        cache.putEdge(EdgeInfo("C", "A", "cycle_ca", weight = 1.0f))

        // 4. 고립 노드(Dangling / Isolated) 100개 주입 (연결된 엣지 없음)
        for (i in 1..100) {
            val isolatedId = "isolated_$i"
            cache.putEntity(EntityInfo(isolatedId, "Isolated Node $i", "Isolated"))
        }

        val pprEngine = HippoRagPprEngine(
            graphCache = cache,
            dampingFactor = 0.85f,
            maxIterations = 3
        )

        // Warmup (JIT hotspot 최적화)
        repeat(5) {
            pprEngine.computePpr(setOf("A"), maxResults = 10, excludeSeeds = false)
        }

        // 실행 시간 및 결과 측정
        val results: List<Pair<String, Float>>
        val elapsedNanos = measureNanoTime {
            results = pprEngine.computePpr(
                seedEntities = setOf("A"),
                maxResults = 20,
                excludeSeeds = false
            )
        }
        val elapsedMs = elapsedNanos / 1_000_000.0

        // 1. 실행 시간 < 2.0ms 수렴 검증
        assertTrue(
            "HippoRAG PPR execution must complete in < 2.0ms even with cycles and 100 isolated nodes. Actual: ${elapsedMs}ms",
            elapsedMs < 2.0
        )

        // 2. 결과 유효성 및 무한 루프 탈출 검증
        assertTrue("PPR results must return non-empty ranked list", results.isNotEmpty())

        // 3. 무한 루프, NaN, 음수 스코어, Infinity 발생 0건 검증
        for ((nodeId, score) in results) {
            assertFalse("PPR score for node '$nodeId' must not be NaN", score.isNaN())
            assertFalse("PPR score for node '$nodeId' must not be Infinite", score.isInfinite())
            assertTrue("PPR score for node '$nodeId' must be strictly non-negative: $score", score >= 0f)
        }

        // 4. 스코어 내림차순 정렬 검증
        for (i in 0 until results.size - 1) {
            val current = results[i].second
            val next = results[i + 1].second
            assertTrue("Scores must be sorted in descending order: $current >= $next", current >= next)
        }
    }

    // =========================================================================
    // e. RED-EXTREME-THERMAL-OVERHEAT: 극한 과열 45°C 및 CRITICAL 발열 공격
    // =========================================================================
    @Test
    fun testRedExtremeThermalOverheat_CriticalThrottlingAttack() {
        val guardian = ThermalGuardian(
            initialTemperatureCelsius = 30.0f,
            initialStatus = ThermalStatus.NORMAL
        )

        // 1. 정상 상태 baseline 확인 (30.0°C, NORMAL)
        assertTrue("30.0°C and NORMAL status must allow background training", guardian.isBackgroundTrainingAllowed())
        assertEquals("Normal throttle delay must be 0ms", 0L, guardian.getThrottleDelayMs())
        assertEquals("Normal recommended batch size must be 16", 16, guardian.getRecommendedBatchSize())

        // 2. 극한 과열 45.0°C 및 ThermalStatus.CRITICAL 주입 공격
        guardian.updateTemperature(45.0f)
        guardian.updateThermalStatus(ThermalStatus.CRITICAL)

        assertEquals(45.0f, guardian.currentTemperature, 0.001f)
        assertEquals(ThermalStatus.CRITICAL, guardian.currentThermalStatus)

        // 3. 극한 과열 제어 규칙 검증
        // (1) isBackgroundTrainingAllowed() == false
        assertFalse(
            "45.0°C and CRITICAL status must strictly forbid background training",
            guardian.isBackgroundTrainingAllowed()
        )
        // (2) getThrottleDelayMs() == 100L
        assertEquals(
            "CRITICAL thermal status must set throttle delay to 100ms",
            100L,
            guardian.getThrottleDelayMs()
        )
        // (3) getRecommendedBatchSize() == 0
        assertEquals(
            "CRITICAL thermal status must suspend batch computation (recommendedBatchSize == 0)",
            0,
            guardian.getRecommendedBatchSize()
        )

        // 4. 정상 상태(30.0°C, NORMAL) 복귀 시 즉각 회복 검증
        guardian.updateTemperature(30.0f)
        guardian.updateThermalStatus(ThermalStatus.NORMAL)

        assertEquals(30.0f, guardian.currentTemperature, 0.001f)
        assertEquals(ThermalStatus.NORMAL, guardian.currentThermalStatus)
        assertTrue(
            "Recovered 30.0°C and NORMAL status must immediately restore background training permission",
            guardian.isBackgroundTrainingAllowed()
        )
        assertEquals("Recovered throttle delay must return to 0ms", 0L, guardian.getThrottleDelayMs())
        assertEquals("Recovered batch size must return to 16", 16, guardian.getRecommendedBatchSize())

        // 5. 중간 단계(MODERATE, SEVERE) 단계별 적응 제어 검증
        guardian.updateThermalStatus(ThermalStatus.MODERATE)
        assertEquals("MODERATE throttle delay must be 10ms", 10L, guardian.getThrottleDelayMs())
        assertEquals("MODERATE batch size must be 4", 4, guardian.getRecommendedBatchSize())

        guardian.updateThermalStatus(ThermalStatus.SEVERE)
        assertEquals("SEVERE throttle delay must be 25ms", 25L, guardian.getThrottleDelayMs())
        assertEquals("SEVERE batch size must be 1", 1, guardian.getRecommendedBatchSize())
    }
}
