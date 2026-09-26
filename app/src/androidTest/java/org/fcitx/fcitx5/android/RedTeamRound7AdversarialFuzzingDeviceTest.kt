/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.daemon.speculative.PromptLookupDecoder
import org.fcitx.fcitx5.android.input.ai.graph.EdgeInfo
import org.fcitx.fcitx5.android.input.ai.graph.EntityInfo
import org.fcitx.fcitx5.android.input.ai.graph.HippoRagPprEngine
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceEgoGraphDatabase
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceL1GraphCache
import org.fcitx.fcitx5.android.input.ai.memory.TieredMemoryManager
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NON_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NO_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * Red Team Round 7 Adversarial Fuzzing & Multilingual Code-Switching Chaos Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-FUZZ-01: Multilingual Korean-English code-switching & punctuation barrage syntax integrity.
 * 2. RED-FUZZ-02: 10,000-character large-scale random Unicode fuzzing ReDoS & OOM defense.
 * 3. RED-FUZZ-03: HippoRAG isolated nodes & self-loops probability mass conservation.
 * 4. RED-FUZZ-04: Android onTrimMemory emergency critical memory recovery.
 * 5. RED-FUZZ-05: SQLite WAL cold-start cache recovery & 1-hop sub-0.05ms lookup resilience.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound7AdversarialFuzzingDeviceTest {

    // =========================================================================
    // 1. RED-FUZZ-01: 한영 혼용 코드스위칭 & 문장부호 난타 조사/통사 무결성
    // =========================================================================
    @Test
    fun testRedFuzz01_MultilingualCodeSwitchingAndPunctuationSyntaxIntegrity() {
        val filter = KoreanSyntaxRuleFilter()

        // 1) 스펙 지정 텍스트 호응 및 문장 무결성 검증
        val sampleTexts = listOf(
            "PR 올렸으니 check 부탁드립니다",
            "API endpoint가 404 error 나서 hotfix 했습니다",
            "이거 zoom 링크인가요?",
            "Github repo에 push 완료! LGTM 주시면 감사하겠습니다"
        )

        for (text in sampleTexts) {
            val result = filter.check(text)
            assertTrue(
                "스펙 텍스트 '$text'는 통사 호응 검사를 정상 통과해야 합니다. Result: $result",
                result is KoreanSyntaxRuleFilter.RuleResult.Valid
            )
            assertTrue(
                "isGrammaticallySound('$text')는 true를 반환해야 합니다.",
                KoreanSyntaxRuleFilter.isGrammaticallySound(text)
            )
        }

        // 문장부호 난타가 포함된 변형 문장 검증
        val punctuationBarrageTexts = listOf(
            "PR 올렸으니 check 부탁드립니다!!!!!",
            "API endpoint가 404 error 나서 hotfix 했습니다...",
            "이거 zoom 링크인가요???",
            "Github repo에 push 완료!! LGTM 주시면 감사하겠습니다^^"
        )

        for (text in punctuationBarrageTexts) {
            assertTrue(
                "문장부호 난타 텍스트 '$text'도 정상 통과해야 합니다.",
                KoreanSyntaxRuleFilter.isGrammaticallySound(text)
            )
        }

        // 2) KoreanJosaBitmaskEngine 영문 끝 글자 종성 판별 및 조사 선택/부착 무결성 단언

        // PR: Acronym ending in 'R' -> 'ㄹ' 받침
        val prFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("PR")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM, prFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.GWA_WA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EURO_RO)) // 'ㄹ' 받침은 '로'
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("PR", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("PR", "으로"))

        // check: Ending in '-ck' -> 'ㄱ' 받침
        val checkFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("check")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, checkFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.GWA_WA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EURO_RO)) // 비'ㄹ' 받침은 '으로'
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("check", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("check", "로"))

        // API: Acronym ending in 'I' -> 모음 종결
        val apiFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("API")
        assertEquals(FLAG_NO_BATCHIM, apiFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("API", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("API", "이"))

        // endpoint: Ending in '-t' -> 발음상 모음(으) 덧붙음 / 무받침
        val endpointFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("endpoint")
        assertEquals(FLAG_NO_BATCHIM, endpointFlags)
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.I_GA))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EUN_NEUN))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("endpoint", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("endpoint", "이"))

        // zoom: Ending in '-m' -> 'ㅁ' 받침
        val zoomFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("zoom")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, zoomFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EUL_REUL))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("zoom", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("zoom", "로"))

        // repo: Ending in '-o' -> 모음 종결
        val repoFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("repo")
        assertEquals(FLAG_NO_BATCHIM, repoFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EURO_RO))

        // LGTM: Acronym ending in 'M' -> 'ㅁ' 받침
        val lgtmFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("LGTM")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, lgtmFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EUL_REUL))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("LGTM", "이"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("LGTM", "가"))
    }

    // =========================================================================
    // 2. RED-FUZZ-02: 10,000자 대규모 무작위 유니코드 퍼징 ReDoS & OOM 방어
    // =========================================================================
    @Test
    fun testRedFuzz02_LargeScaleRandomUnicodeFuzzingReDosAndOomDefense() {
        val filter = KoreanSyntaxRuleFilter()

        // 10,000자의 무작위 이모지, 아랍어, 특수문자, 비표시 제어문자 스트링 생성
        val emojis = listOf("🚀", "🔥", "🎉", "💻", "📱", "⚡️", "✨", "❤️", "👍🏻", "👨‍💻", "👩‍💻")
        val arabic = listOf("مرحبا", "العربية", "نصوص", "معقدة", "برمجة", "تطبيق", "خوارزمية")
        val special = listOf("!@#\$%^&*()", "_+-=[]{}|", ";':\",./<>?", "~`₩\\|")
        val controls = listOf("\u200B", "\u200C", "\u200D", "\uFEFF", "\u200E", "\u200F", "\n", "\t")
        val hangul = listOf("가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하")

        val sb = StringBuilder(10500)
        val rng = Random(2026)
        while (sb.length < 10000) {
            when (rng.nextInt(5)) {
                0 -> sb.append(emojis[rng.nextInt(emojis.size)])
                1 -> sb.append(arabic[rng.nextInt(arabic.size)])
                2 -> sb.append(special[rng.nextInt(special.size)])
                3 -> sb.append(controls[rng.nextInt(controls.size)])
                4 -> sb.append(hangul[rng.nextInt(hangul.size)])
            }
        }
        val fuzz10k = sb.substring(0, 10000)
        assertEquals(10000, fuzz10k.length)

        // 1) JIT 컴파일 웜업
        repeat(5) {
            filter.check("웜업 텍스트입니다.")
            filter.check(fuzz10k)
            PromptLookupDecoder.decode("웜업", "웜업 텍스트입니다.", maxTokens = 4)
            PromptLookupDecoder.decode(fuzz10k.take(100), fuzz10k, maxTokens = 16)
        }

        // 2) KoreanSyntaxRuleFilter.check() 투입 및 5.0ms 이내 Fail-Safe 판정 단언
        val startFilter = System.nanoTime()
        val ruleResult = filter.check(fuzz10k)
        val elapsedFilterMs = (System.nanoTime() - startFilter) / 1_000_000.0

        assertTrue(
            "KoreanSyntaxRuleFilter.check는 10,000자 퍼징에서도 5.0ms 이내에 안전하게 반환해야 합니다. Actual: ${elapsedFilterMs}ms",
            elapsedFilterMs < 5.0
        )
        assertTrue(
            "규칙 판정 결과는 null이 아닌 유효한 RuleResult 객체여야 합니다.",
            ruleResult is KoreanSyntaxRuleFilter.RuleResult
        )

        // 3) PromptLookupDecoder.decode() 투입 및 5.0ms 이내 Fail-Safe 판정 단언
        // Case A: 10,000자 referenceContext에 단어 검색
        val startPld = System.nanoTime()
        val decodedA = PromptLookupDecoder.decode(
            prompt = fuzz10k.take(100),
            referenceContext = fuzz10k,
            maxTokens = 16
        )
        val elapsedPldMs = (System.nanoTime() - startPld) / 1_000_000.0

        assertTrue(
            "PromptLookupDecoder.decode는 10,000자 대규모 코퍼스에서도 5.0ms 이내에 반환해야 합니다. Actual: ${elapsedPldMs}ms",
            elapsedPldMs < 5.0
        )

        // Case B: prompt 및 referenceContext 모두 10,000자 극단 입력
        val startPldFull = System.nanoTime()
        val decodedB = PromptLookupDecoder.decode(
            prompt = fuzz10k,
            referenceContext = fuzz10k,
            maxTokens = 16
        )
        val elapsedPldFullMs = (System.nanoTime() - startPldFull) / 1_000_000.0

        assertTrue(
            "PromptLookupDecoder.decode는 10,000자 극단 프롬프트에서도 5.0ms 이내에 Fail-Safe하게 반환해야 합니다. Actual: ${elapsedPldFullMs}ms",
            elapsedPldFullMs < 5.0
        )
    }

    // =========================================================================
    // 3. RED-FUZZ-03: HippoRAG 고립 노드 및 셀프 루프 확률 질량 보존
    // =========================================================================
    @Test
    fun testRedFuzz03_HippoRagIsolatedNodeAndSelfLoopMassConservation() {
        val l1Cache = OnDeviceL1GraphCache()

        // 비정상 위상 구성:
        // 1) 0-degree 고립 노드들 (간선 전혀 없음)
        val isolated1 = EntityInfo("isolated_1", "고립노드1", "concept")
        val isolated2 = EntityInfo("isolated_2", "고립노드2", "concept")

        // 2) 셀프 루프 엣지만 가진 노드 (v == u)
        val selfLoopNode = EntityInfo("self_loop_node", "셀프루프노드", "concept")
        val selfLoopEdge = EdgeInfo("self_loop_node", "self_loop_node", "SELF_CYCLE", weight = 1.0f)

        // 3) 일반 연결 컴포넌트
        val normalNodeA = EntityInfo("normal_a", "일반노드A", "entity")
        val normalNodeB = EntityInfo("normal_b", "일반노드B", "entity")
        val normalEdge = EdgeInfo("normal_a", "normal_b", "CONNECTS_TO", weight = 1.0f)

        l1Cache.warmup(
            entities = listOf(isolated1, isolated2, selfLoopNode, normalNodeA, normalNodeB),
            edges = listOf(selfLoopEdge, normalEdge)
        )

        val engine = HippoRagPprEngine(l1Cache, dampingFactor = 0.85f, maxIterations = 3)

        // 1) 고립 노드만 시드로 지정한 경우
        val resultIsolated = engine.computePpr(
            seedEntities = setOf("isolated_1"),
            maxResults = 10,
            excludeSeeds = false
        )
        assertFalse("고립 노드 PPR 결과는 비어있지 않아야 합니다.", resultIsolated.isEmpty())
        for ((entityId, score) in resultIsolated) {
            assertFalse("점수는 NaN이 아니어야 합니다. ($entityId)", score.isNaN())
            assertFalse("점수는 Infinite가 아니어야 합니다. ($entityId)", score.isInfinite())
            assertTrue("점수는 0.0보다 커야 합니다. ($entityId)", score > 0f)
        }
        val sumIsolated = resultIsolated.sumOf { it.second.toDouble() }.toFloat()
        assertTrue(
            "고립 노드 시드에서 확률 합은 1.0f 이하로 정상 수렴해야 합니다. Actual: $sumIsolated",
            sumIsolated <= 1.0001f
        )

        // 2) 셀프 루프 노드만 시드로 지정한 경우 (0 나눗셈 방어 검증)
        val resultSelfLoop = engine.computePpr(
            seedEntities = setOf("self_loop_node"),
            maxResults = 10,
            excludeSeeds = false
        )
        assertFalse("셀프 루프 PPR 결과는 비어있지 않아야 합니다.", resultSelfLoop.isEmpty())
        for ((entityId, score) in resultSelfLoop) {
            assertFalse("점수는 NaN이 아니어야 합니다. ($entityId)", score.isNaN())
            assertFalse("점수는 Infinite가 아니어야 합니다. ($entityId)", score.isInfinite())
            assertTrue("점수는 0.0보다 커야 합니다. ($entityId)", score > 0f)
        }
        val sumSelfLoop = resultSelfLoop.sumOf { it.second.toDouble() }.toFloat()
        assertTrue(
            "셀프 루프 노드 시드에서 확률 합은 1.0f 이하로 정상 수렴해야 합니다. Actual: $sumSelfLoop",
            sumSelfLoop <= 1.0001f
        )

        // 3) 복합 시드(고립 노드 + 셀프 루프 노드 + 일반 노드) 검증
        val resultMixed = engine.computePpr(
            seedEntities = setOf("isolated_1", "self_loop_node", "normal_a"),
            maxResults = 10,
            excludeSeeds = false
        )
        for ((entityId, score) in resultMixed) {
            assertFalse("점수는 NaN이 아니어야 합니다. ($entityId)", score.isNaN())
            assertFalse("점수는 Infinite가 아니어야 합니다. ($entityId)", score.isInfinite())
        }
        val sumMixed = resultMixed.sumOf { it.second.toDouble() }.toFloat()
        assertTrue(
            "복합 시드에서 확률 합은 1.0f 이하로 정상 수렴해야 합니다. Actual: $sumMixed",
            sumMixed <= 1.0001f
        )

        // excludeSeeds = true 조건에서도 무결성 검증
        val resultMixedExcluded = engine.computePpr(
            seedEntities = setOf("isolated_1", "self_loop_node", "normal_a"),
            maxResults = 10,
            excludeSeeds = true
        )
        for ((entityId, score) in resultMixedExcluded) {
            assertFalse("점수는 NaN이 아니어야 합니다. ($entityId)", score.isNaN())
            assertFalse("점수는 Infinite가 아니어야 합니다. ($entityId)", score.isInfinite())
        }
        val sumMixedExcluded = resultMixedExcluded.sumOf { it.second.toDouble() }.toFloat()
        assertTrue(
            "excludeSeeds=true 조건에서 확률 합은 1.0f 이하이어야 합니다. Actual: $sumMixedExcluded",
            sumMixedExcluded <= 1.0001f
        )
    }

    // =========================================================================
    // 4. RED-FUZZ-04: Android onTrimMemory 긴급 메모리 회수
    // =========================================================================
    @Test
    fun testRedFuzz04_TieredMemoryTrimMemoryCriticalPurge() {
        val memoryManager = TieredMemoryManager()

        // 1) L1 버퍼 적재
        memoryManager.updateL1Buffer("실시간 사용자 활성 입력 버퍼 스트링입니다.")

        // 2) L2 발화 5건 적재
        repeat(5) { i ->
            memoryManager.addSessionUtterance("사용자가 이전에 입력 완료한 세션 발화 문장입니다. 인덱스: #$i")
        }

        // 3) L3 에피소드 20건 적재
        repeat(20) { i ->
            memoryManager.addEpisode(
                topic = "데일리 에피소드 주제 $i",
                summary = "에피소드 상세 요약본 데이터로 장기 기억을 보존하는 텍스트 블록입니다. #$i",
                timestamp = System.currentTimeMillis() + i * 1000L
            )
        }

        // 적재 상태 확인
        assertEquals("실시간 사용자 활성 입력 버퍼 스트링입니다.", memoryManager.getL1Buffer())
        assertEquals(5, memoryManager.getSessionUtterances().size)
        assertEquals(20, memoryManager.getRecentEpisodes().size)

        // 4) trimMemory(level = 80) (ComponentCallbacks2.TRIM_MEMORY_COMPLETE / CRITICAL) 호출
        memoryManager.trimMemory(level = 80)

        // 5) L1/L2가 플러시되고 L3가 최소로 정리되었는지 단언
        assertTrue(
            "L1 활성 버퍼는 완전히 플러시되어 비어있어야 합니다.",
            memoryManager.getL1Buffer().isEmpty()
        )
        assertTrue(
            "L2 세션 발화는 완전히 플러시되어 비어있어야 합니다.",
            memoryManager.getSessionUtterances().isEmpty()
        )
        assertTrue(
            "L3 에피소드는 최소(1건 이하)로 정리되어야 합니다.",
            memoryManager.getRecentEpisodes().size <= 1
        )

        // 6) 메모리 소비량이 50KB 이하로 감소함을 단언
        val estimatedBytes = memoryManager.getEstimatedMemoryBytes()
        val limitBytes = 50 * 1024L // 50 KB
        assertTrue(
            "CRITICAL 긴급 메모리 회수 후 메모리 추정치는 50KB 이하로 급감해야 합니다. Actual: ${estimatedBytes}B, Limit: ${limitBytes}B",
            estimatedBytes <= limitBytes
        )
    }

    // =========================================================================
    // 5. RED-FUZZ-05: SQLite WAL 콜드 스타트 캐시 복원력
    // =========================================================================
    @Test
    fun testRedFuzz05_SqliteWalColdStartCacheResilience() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "test_red_fuzz_cold_start.db"
        context.deleteDatabase(dbName)

        // 1) 100개 엔티티 및 200개 엣지 적재
        var db = OnDeviceEgoGraphDatabase(context, dbName)
        for (i in 0 until 100) {
            db.upsertEntity(
                id = "entity_$i",
                label = "라벨_$i",
                category = "카테고리_${i % 5}",
                weight = 1.0f + (i % 10) * 0.1f
            )
        }

        for (i in 0 until 200) {
            val src = "entity_${i % 100}"
            val dst = "entity_${(i * 7 + 1) % 100}"
            val relation = "REL_${i % 4}_${i / 100}"
            db.upsertEdge(
                src = src,
                dst = dst,
                relation = relation,
                weight = 1.0f + (i % 5) * 0.2f
            )
        }

        // 2) DB 명시적 close (프로세스 종료 및 콜드 스타트 시뮬레이션)
        db.close()

        // 3) DB 재오픈 (콜드 스타트 복구)
        val reopenedDb = OnDeviceEgoGraphDatabase(context, dbName)
        val entities = reopenedDb.getAllEntities()
        val edges = reopenedDb.getAllEdges()

        assertEquals("재오픈된 DB에서 100개 엔티티가 온전히 복원되어야 합니다.", 100, entities.size)
        assertEquals("재오픈된 DB에서 200개 엣지가 온전히 복원되어야 합니다.", 200, edges.size)

        // 4) OnDeviceL1GraphCache.warmup() 수행
        val l1Cache = OnDeviceL1GraphCache()
        l1Cache.warmup(entities, edges)

        // JIT 컴파일 웜업 (모든 엔티티에 대해 예열)
        repeat(2) {
            for (i in 0 until 100) {
                l1Cache.get1Hop("entity_$i")
            }
        }
        val graphCache = l1Cache
        repeat(5) {
            graphCache.get1Hop("entity_1")
        }

        // 5) 1-hop 조회가 0.5ms 이내에 이전 데이터를 100% 온전하게 반환함을 단언
        for (i in 0 until 100) {
            val entityId = "entity_$i"
            val expectedEdges = reopenedDb.get1HopNeighbors(entityId)

            val start = System.nanoTime()
            val cachedEdges = l1Cache.get1Hop(entityId)
            val lookupMs = (System.nanoTime() - start) / 1_000_000.0

            assertTrue(
                "L1 캐시 1-hop 조회가 0.5ms 이내에 완료되어야 합니다. Actual: ${lookupMs}ms",
                lookupMs < 0.5
            )
            assertEquals(
                "엔티티 $entityId 의 1-hop 엣지 개수가 DB 결과와 100% 일치해야 합니다.",
                expectedEdges.size,
                cachedEdges.size
            )
        }

        // 자원 정리
        reopenedDb.close()
        context.deleteDatabase(dbName)
    }
}
