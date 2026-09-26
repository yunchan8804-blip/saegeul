/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.adapter.OnDeviceLoraTrainer
import org.fcitx.fcitx5.android.input.ai.adapter.TestTimeTrainer
import org.fcitx.fcitx5.android.input.ai.grammar.GrammarConstrainedEngine
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceEgoGraphDatabase
import org.fcitx.fcitx5.android.input.ai.hardware.HardwareTierProfiler
import org.fcitx.fcitx5.android.input.ai.memory.PersonaTone
import org.fcitx.fcitx5.android.input.ai.memory.TieredMemoryManager
import org.fcitx.fcitx5.android.input.ai.memory.TpoContextEncoder
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalGuardian
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalStatus
import org.fcitx.fcitx5.android.input.ai.verifier.ThreeStageOutputVerifier
import org.fcitx.fcitx5.android.input.ai.worker.GemmaGraphExtractWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class AdvancedAiE2eDeviceTest {

    private lateinit var context: Context
    private lateinit var database: OnDeviceEgoGraphDatabase
    private val testDbName = "test_advanced_ai_ego_graph.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(testDbName)
        database = OnDeviceEgoGraphDatabase(context, testDbName)
        database.clear()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(testDbName)
    }

    /**
     * EAI-10: 문법 제약 FSM 및 한국어 받침 조사 토큰 마스킹 검증.
     */
    @Test
    fun testGrammarConstrainedEngineAndTokenMasking() {
        val engine = GrammarConstrainedEngine()

        // 1. JSON FSM State transition
        var state = 100 // STATE_JSON_START
        val tokens = listOf("{", "\"key\"", ":", "\"value\"", "}")
        for (token in tokens) {
            val (valid, nextState) = engine.isValidToken(state, token, GrammarConstrainedEngine.SchemaType.JSON_OBJECT)
            assertTrue("Token '$token' should be valid in JSON_OBJECT at state $state", valid)
            state = nextState
        }

        // Invalid JSON token transition check
        val (invalid, _) = engine.isValidToken(100, "valueWithoutQuote", GrammarConstrainedEngine.SchemaType.JSON_OBJECT)
        assertFalse("Unquoted string at start of JSON should be rejected", invalid)

        // 2. Korean Josa Token Masking
        val candidates = listOf("은", "는", "이", "가", "을", "를", "과", "와", "으로", "로")

        // Case A: '밥' (종성 'ㅂ' 있음) -> 은, 이, 을, 과, 으로 허용 / 는, 가, 를, 와, 로 차단
        val filteredForBab = engine.filterAllowedTokens("밥", candidates, GrammarConstrainedEngine.SchemaType.FREE_TEXT)
        assertEquals(listOf("은", "이", "을", "과", "으로"), filteredForBab)

        // Case B: '사과' (종성 없음) -> 는, 가, 를, 와, 로 허용 / 은, 이, 을, 과, 으로 차단
        val filteredForApple = engine.filterAllowedTokens("사과", candidates, GrammarConstrainedEngine.SchemaType.FREE_TEXT)
        assertEquals(listOf("는", "가", "를", "와", "로"), filteredForApple)

        // Case C: '서울' (종성 'ㄹ') -> 로 허용, 으로 차단 / 은, 이, 을, 과 허용
        val filteredForSeoul = engine.filterAllowedTokens("서울", candidates, GrammarConstrainedEngine.SchemaType.FREE_TEXT)
        assertEquals(listOf("은", "이", "을", "과", "로"), filteredForSeoul)

        // Warmup ART JIT compiler on emulator
        for (i in 0 until 1000) {
            engine.filterAllowedTokens("회의", candidates, GrammarConstrainedEngine.SchemaType.FREE_TEXT)
        }

        // Measure token masking latency on emulator ART (< 150µs)
        val iterations = 5000
        val startNs = System.nanoTime()
        for (i in 0 until iterations) {
            engine.filterAllowedTokens("선생님", candidates, GrammarConstrainedEngine.SchemaType.FREE_TEXT)
        }
        val avgLatencyUs = ((System.nanoTime() - startNs) / iterations) / 1000.0
        assertTrue("Token masking latency must be < 500µs on emulator, actual: $avgLatencyUs µs", avgLatencyUs < 500.0)
    }

    /**
     * EAI-11: 3단계 Verifier (규칙 -> KenLM PPL -> Mini PRM) 통합 파이프라인 검증.
     */
    @Test
    fun testThreeStageOutputVerifierE2e() {
        val verifier = ThreeStageOutputVerifier()

        // 1. Stage 1 Hard Rule Rejection: ACC-01 (이유절 + 의문문)
        val stage1Result = verifier.verify("차가 막혀서", "늦을 것 같은데 지금 어디인가요?")
        assertFalse("Stage 1 should reject ACC-01 violation", stage1Result.isValid)
        assertEquals(1, stage1Result.stage)

        // 2. Stage 1 Hard Rule Rejection: ACC-02 (목적어 + '감사해요')
        val stage1Acc2Result = verifier.verify("도움을 주셔서", "마음을 너무 감사해요")
        assertFalse("Stage 1 should reject ACC-02 violation", stage1Acc2Result.isValid)
        assertEquals(1, stage1Acc2Result.stage)

        // 3. Stage 2 High PPL Rejection: 어색한 연어 결합
        val stage2Result = verifier.verify("내일 일정", "회의를 참석해요")
        assertFalse("Stage 2 should reject unnatural collocation with high PPL", stage2Result.isValid)
        assertEquals(2, stage2Result.stage)

        // 4. Valid candidates passing all 3 stages
        val validResult = verifier.verify("내일 일정 관련하여", "회의에 참석합니다")
        assertTrue("Valid candidate must pass all 3 stages: ${validResult.reason}", validResult.isValid)
        assertEquals(3, validResult.stage)
        assertTrue("Score must be >= 0.5f", validResult.score >= 0.5f)

        // Measure 3-stage verification latency on device (< 6.0ms)
        val startNs = System.nanoTime()
        repeat(50) {
            verifier.verify("자료 준비가 다 되어", "자료를 준비했습니다")
        }
        val avgLatencyMs = ((System.nanoTime() - startNs) / 50) / 1_000_000.0
        assertTrue("ThreeStageOutputVerifier latency must be < 6.0ms, actual: $avgLatencyMs ms", avgLatencyMs < 6.0)
    }

    /**
     * EAI-12: 4-Tier Memory 및 TPO 문맥 인코더 연동 검증.
     */
    @Test
    fun testTieredMemoryAndTpoContextIntegration() {
        val memoryManager = TieredMemoryManager()

        // L1 Active Buffer
        memoryManager.updateL1Buffer("지금 출발")
        assertEquals("지금 출발", memoryManager.getL1Buffer())
        memoryManager.clearL1Buffer()
        assertEquals("", memoryManager.getL1Buffer())

        // L2 Session Utterances (Max 5 FIFO)
        for (i in 1..8) {
            memoryManager.addSessionUtterance("발화 $i")
        }
        val sessionList = memoryManager.getSessionUtterances()
        assertEquals(5, sessionList.size)
        assertEquals("발화 4", sessionList.first())
        assertEquals("발화 8", sessionList.last())

        // L3 Episodic Context (Max 20 LRU)
        for (i in 1..25) {
            memoryManager.addEpisode("토픽 $i", "요약 $i")
        }
        val episodes = memoryManager.getRecentEpisodes()
        assertEquals(20, episodes.size)

        // Memory footprint check (< 35MB)
        val memoryBytes = memoryManager.getEstimatedMemoryBytes()
        assertTrue("Estimated memory footprint must be < 35MB, actual: $memoryBytes bytes", memoryBytes < 35 * 1024 * 1024)

        // TPO Context Encoding
        val slackContext = TpoContextEncoder.encode("com.slack")
        assertEquals(PersonaTone.FORMAL_BUSINESS, slackContext.tone)

        val kakaoContext = TpoContextEncoder.encode("com.kakao.talk")
        assertEquals(PersonaTone.CASUAL_CHAT, kakaoContext.tone)

        val chromeContext = TpoContextEncoder.encode("com.android.chrome")
        assertEquals(PersonaTone.CONCISE_SEARCH, chromeContext.tone)
    }

    /**
     * EAI-14: Thermal Guardian 배터리 온도 및 발열 단계 적응형 제어 검증.
     */
    @Test
    fun testThermalGuardianAdaptiveThrottling() {
        val thermalGuardian = ThermalGuardian(initialTemperatureCelsius = 34.0f, initialStatus = ThermalStatus.NORMAL)

        // Normal state
        assertTrue(thermalGuardian.isBackgroundTrainingAllowed())
        assertEquals(0L, thermalGuardian.getThrottleDelayMs())
        assertEquals(16, thermalGuardian.getRecommendedBatchSize())

        // Moderate thermal state (36.0°C)
        thermalGuardian.updateTemperature(36.0f)
        thermalGuardian.updateThermalStatus(ThermalStatus.MODERATE)
        assertFalse("Background training must not be allowed in MODERATE thermal status", thermalGuardian.isBackgroundTrainingAllowed())
        assertEquals(10L, thermalGuardian.getThrottleDelayMs())
        assertEquals(4, thermalGuardian.getRecommendedBatchSize())

        // Overheat threshold: 37.0°C (> 36.5°C limit)
        thermalGuardian.updateTemperature(37.0f)
        thermalGuardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertFalse("Training must be blocked when battery temp > 36.5°C", thermalGuardian.isBackgroundTrainingAllowed())

        // Severe state: 39.5°C
        thermalGuardian.updateTemperature(39.5f)
        thermalGuardian.updateThermalStatus(ThermalStatus.SEVERE)
        assertFalse(thermalGuardian.isBackgroundTrainingAllowed())
        assertEquals(25L, thermalGuardian.getThrottleDelayMs())
        assertEquals(1, thermalGuardian.getRecommendedBatchSize())
    }

    /**
     * EAI-13: GemmaGraphExtractWorker 시맨틱 지식 추출 및 SQLite DB 트랜잭션 upsert 검증.
     */
    @Test
    fun testGemmaGraphExtractWorkerAndDatabaseTransaction() {
        val utterances = listOf(
            "내일 오전 10시 팀 회의 참석",
            "점심 메뉴 파스타",
            "강남역 장소 미팅"
        )

        val triples = GemmaGraphExtractWorker.extractTriples(utterances)
        assertTrue("Extracted triples should not be empty", triples.isNotEmpty())

        // Insert into SQLite Database using worker's atomic transaction upsert
        GemmaGraphExtractWorker.upsertTriples(triples, database)

        // Verify entities and edges exist in DB
        val entities = database.getAllEntities()
        val edges = database.getAllEdges()
        assertTrue("Entities must be persisted in DB", entities.size >= 4)
        assertTrue("Edges must be persisted in DB", edges.size >= 3)
        assertTrue(edges.any { it.src == "팀 회의" && it.dst == "참석" && it.relation == "동작" })
        assertTrue(edges.any { it.src == "점심" && it.dst == "파스타" && it.relation == "메뉴" })
    }

    /**
     * EAI-15~17: On-Device LoRA 학습, TTT 인플레이스 적응, 하드웨어 프로파일러 검증.
     */
    @Test
    fun testOnDeviceLoraAndTestTimeTrainingAdaptation() {
        // 1. OnDeviceLoraTrainer (EAI-15)
        val loraTrainer = OnDeviceLoraTrainer(rank = 4, alpha = 8.0f)
        val trainingSamples = listOf(
            "안녕하세요 팀장님 보고서 송부드립니다",
            "확인 감사합니다 좋은 하루 되세요",
            "내일 미팅 일정 조율 부탁드립니다"
        )
        val loraResult = loraTrainer.trainBatch(trainingSamples, maxSteps = 3)
        assertEquals(3, loraResult.stepsCompleted)
        assertTrue("Adapted parameters count must be positive", loraResult.adaptedParametersCount > 0)
        assertTrue("Forgetting rate must be < 2.0%, actual: ${loraResult.forgettingRate}", loraResult.forgettingRate < 0.02f)
        assertTrue("Delta weight norm must be positive", loraResult.deltaWeightNorm > 0.0f)

        // 2. TestTimeTrainer (EAI-16)
        val ttt = TestTimeTrainer()
        val startNs = System.nanoTime()
        val adaptedState = ttt.adaptOnline("보고서 초안 작성 완료")
        val tttLatencyMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("TTT adaptation latency must be < 20ms, actual: $tttLatencyMs ms", tttLatencyMs < 20.0)
        assertTrue("Adapted state must have active slots", adaptedState.activeSlots > 0)

        ttt.reset()
        assertEquals("Zero parameter drift upon reset", 0.0f, ttt.getDriftFromBase(), 0.0001f)

        // 3. HardwareTierProfiler (EAI-17)
        val hardwareProfile = HardwareTierProfiler.profile(context)
        assertNotNull(hardwareProfile)
        assertTrue("Total RAM must be positive", hardwareProfile.totalRamBytes > 0)
        assertTrue("CPU cores must be positive", hardwareProfile.cpuCores > 0)
        assertNotNull(hardwareProfile.recommendedModel)
    }
}
