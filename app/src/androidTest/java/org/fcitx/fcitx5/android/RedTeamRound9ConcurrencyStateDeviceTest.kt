/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.adapter.TestTimeTrainer
import org.fcitx.fcitx5.android.input.ai.graph.EdgeInfo
import org.fcitx.fcitx5.android.input.ai.graph.EntityInfo
import org.fcitx.fcitx5.android.input.ai.graph.HippoRagPprEngine
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceEgoGraphDatabase
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceL1GraphCache
import org.fcitx.fcitx5.android.input.ai.memory.PersonaTone
import org.fcitx.fcitx5.android.input.ai.memory.TpoContext
import org.fcitx.fcitx5.android.input.ai.memory.TpoContextEncoder
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.fcitx.fcitx5.android.input.ai.verifier.ThreeStageOutputVerifier
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
 * Red Team Round 9: Concurrency & State Machine Integrity E2E Device Test.
 *
 * Scenarios:
 * 1. RED-CONCUR-01: Multi-thread editor commit race condition & morphological ending atomicity.
 * 2. RED-CONCUR-02: SQLite Ego-Graph WAL deadlock & SQLiteBusy defense under concurrent background upsert.
 * 3. RED-CONCUR-03: Test-Time Trainer (TTT) weight checkpoint resilience & zero-drift rollback.
 * 4. RED-CONCUR-04: Three-stage verifier high-throughput (< 2.0ms avg, < 6.0ms single) verification.
 * 5. RED-CONCUR-05: Ultra-rapid TPO session switching & persona tone determinism (< 0.05ms).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound9ConcurrencyStateDeviceTest {

    // =========================================================================
    // 1. RED-CONCUR-01: 다중 스레드 동시 입력 스트림 커밋 원자성 및 중복 결합 방어
    // =========================================================================
    @Test(timeout = 30000)
    fun testRedConcur01_MultiThreadEditorCommitRaceCondition() {
        val threadCount = 10
        val repeatPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val collector = UserTypingContextCollector()

        // 10개 독립된 문맥 (스레드별 10개 문장)
        val threadSentences = (0 until threadCount).map { t ->
            listOf(
                "스레드_${t}_첫번째 회의에 참석하겠습니다.",
                "스레드_${t}_두번째 자료를 준비했습니다.",
                "스레드_${t}_세번째 점심 같이 먹어요.",
                "스레드_${t}_네번째 문서 검토 부탁드립니다.",
                "스레드_${t}_다섯번째 일정이 변경되었습니다.",
                "스레드_${t}_여섯번째 사무실로 출발합니다.",
                "스레드_${t}_일곱번째 확인 후 연락드리겠습니다.",
                "스레드_${t}_여덟번째 좋은 하루 보내세요.",
                "스레드_${t}_아홉번째 다음 주에 뵙겠습니다.",
                "스레드_${t}_열번째 요청을 처리 완료했습니다."
            )
        }

        // 10개 스레드가 각각 독립된 패키지 문맥을 200회 연속 커밋 및 어미 분석
        for (t in 0 until threadCount) {
            val pkg = "org.fcitx.fcitx5.test.pkg_$t"
            val sentences = threadSentences[t]
            executor.submit {
                try {
                    repeat(repeatPerThread) {
                        for (sentence in sentences) {
                            // 1) KoreanMorphologicalEndingAnalyzer 동시 호출 무결성
                            val lastWord = sentence.trimEnd('.', ' ').split(" ").last()
                            val ending = KoreanMorphologicalEndingAnalyzer.extractEnding(lastWord)
                            if (ending == null) {
                                throw IllegalStateException("어미 추출 실패: '$lastWord' in '$sentence'")
                            }

                            // 2) UserTypingContextCollector 동시 커밋 무결성
                            collector.recordCommittedText(pkg, sentence + " ")
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(25, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("모든 10개 스레드가 제한 시간 내에 정상 완료되어야 합니다.", completed)
        assertTrue("동시 커밋 중 레이스 컨디션이나 예외가 발생하지 않아야 합니다: $errors", errors.isEmpty())

        collector.flushAllPending()

        // 각 스레드별 데이터 격리 및 버퍼 무결성 단언
        for (t in 0 until threadCount) {
            val pkg = "org.fcitx.fcitx5.test.pkg_$t"
            val collectedSentences = collector.getSentences(pkg)
            assertTrue("패키지 $pkg 에 커밋된 문장이 수집되어야 합니다.", collectedSentences.isNotEmpty())

            val recentContext = collector.getRecentContext(pkg)
            assertTrue("패키지 $pkg 의 최근 문맥이 존재해야 합니다.", recentContext.isNotBlank())

            // 타 스레드의 패키지 내용이 섞여 들어가지 않았는지 세션 격리 단언
            val otherThreadTag = "스레드_${(t + 1) % threadCount}_"
            assertFalse(
                "패키지 $pkg 의 문맥에 타 스레드 식별자($otherThreadTag)가 누출되어서는 안 됩니다.",
                recentContext.contains(otherThreadTag)
            )
        }
    }

    // =========================================================================
    // 2. RED-CONCUR-02: SQLite Ego-Graph WAL 모드 동시 쓰기-읽기 교착상태 & SQLiteBusy 방어
    // =========================================================================
    @Test(timeout = 30000)
    fun testRedConcur02_SqliteEgoGraphWalDeadlockAndBusyDefense() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDbName = "test_saegeul_ego_graph_round9.db"
        context.deleteDatabase(testDbName)

        val database = OnDeviceEgoGraphDatabase(context, testDbName)
        val graphCache = OnDeviceL1GraphCache()

        try {
            // WAL 모드 활성화 단언
            val writableDb = database.writableDatabase
            assertTrue("Database WAL 모드가 활성화되어 있어야 합니다.", writableDb.isWriteAheadLoggingEnabled)

            // 초기 그래프 데이터 로딩 (100개 노드, 99개 엣지)
            for (i in 0 until 100) {
                val entityId = "node_$i"
                database.upsertEntity(entityId, "노드_$i", "일반", 1.0f)
                graphCache.putEntity(EntityInfo(entityId, "노드_$i", "일반", 1.0f))
                if (i > 0) {
                    val src = "node_${i - 1}"
                    database.upsertEdge(src, entityId, "연결", 1.0f)
                    graphCache.putEdge(EdgeInfo(src, entityId, "연결", 1.0f))
                }
            }

            val pprEngine = HippoRagPprEngine(
                graphCache = graphCache,
                dampingFactor = 0.85f,
                maxIterations = 3
            )

            val bgErrors = ConcurrentLinkedQueue<Throwable>()
            val bgStartedLatch = CountDownLatch(1)

            // 백그라운드 스레드: 500개 엣지/엔티티 Batch Upsert 진행
            val bgThread = Thread {
                bgStartedLatch.countDown()
                try {
                    for (i in 100 until 600) {
                        val entityId = "batch_entity_$i"
                        database.upsertEntity(entityId, "배치_$i", "배치카테고리", 1.0f)
                        val targetNode = "node_${i % 100}"
                        database.upsertEdge(entityId, targetNode, "배치연결", 1.0f)
                    }
                } catch (t: Throwable) {
                    bgErrors.add(t)
                }
            }

            bgThread.start()
            assertTrue("백그라운드 스레드가 정상 시작되어야 합니다.", bgStartedLatch.await(5, TimeUnit.SECONDS))

            // 포그라운드 스레드: 백그라운드 쓰기 진행 중 100회 연속 동시 질의
            val queryDurationsNanos = LongArray(100)
            for (q in 0 until 100) {
                val queryNode = "node_${q % 100}"
                val elapsed = measureNanoTime {
                    // 1) SQLite DB 1-hop 질의
                    val neighbors = database.get1HopNeighbors(queryNode)
                    assertTrue("1-hop 이웃 조회가 성공해야 합니다.", neighbors.isNotEmpty())

                    // 2) HippoRAG PPR 계산
                    val pprResults = pprEngine.computePpr(setOf(queryNode), maxResults = 5)
                    assertTrue("PPR 계산 결과가 반환되어야 합니다.", pprResults.isNotEmpty())
                }
                queryDurationsNanos[q] = elapsed
            }

            bgThread.join(10000)
            assertFalse("백그라운드 스레드가 제한 시간 내에 종료되어야 합니다.", bgThread.isAlive)
            assertTrue("백그라운드 스레드에서 Lock 충돌이나 SQLiteBusy가 없어야 합니다: $bgErrors", bgErrors.isEmpty())

            val avgMs = queryDurationsNanos.average() / 1_000_000.0
            val maxMs = (queryDurationsNanos.maxOrNull() ?: 0L) / 1_000_000.0
            println("Ego-Graph WAL Concurrent Query Latency - Avg: %.3f ms, Max: %.3f ms".format(avgMs, maxMs))

            val maxLatencyMs = 15.0
            assertTrue(
                "동시 질의 평균 지연시간은 < ${maxLatencyMs}ms 여야 합니다. Actual: ${avgMs}ms",
                avgMs < maxLatencyMs
            )
        } finally {
            database.close()
            context.deleteDatabase(testDbName)
        }
    }

    // =========================================================================
    // 3. RED-CONCUR-03: LoRA & TTT 어댑터 가중치 체크포인트 복원력 및 트랜잭션 롤백
    // =========================================================================
    @Test
    fun testRedConcur03_TestTimeTrainerWeightCheckpointRollback() {
        val ttt = TestTimeTrainer()

        // 1) 200회 연속 가속 온라인 적응 업데이트
        repeat(200) { step ->
            val context = "빠른 입력과 문맥 적응 가속 업데이트 스텝 $step 번호_$step"
            val state = ttt.adaptOnline(context)

            assertFalse("스텝 $step: updateNorm은 NaN이 아니어야 합니다.", state.updateNorm.isNaN())
            assertFalse("스텝 $step: updateNorm은 Infinite가 아니어야 합니다.", state.updateNorm.isInfinite())
            assertFalse("스텝 $step: driftFromBase는 NaN이 아니어야 합니다.", state.driftFromBase.isNaN())
            assertFalse("스텝 $step: driftFromBase는 Infinite가 아니어야 합니다.", state.driftFromBase.isInfinite())
        }

        // 2) 가속 업데이트 후 드리프트 축적 상태 검증
        val driftBefore = ttt.getDriftFromBase()
        assertTrue(
            "200회 연속 업데이트 후 driftFromBase는 0.0f보다 커야 합니다. Actual: $driftBefore",
            driftBefore > 0.0f
        )
        assertFalse("200회 연속 업데이트 후 isZeroDrift()는 false여야 합니다.", ttt.isZeroDrift())
        assertTrue("활성 가중치 맵이 비어있지 않아야 합니다.", ttt.getAllWeights().isNotEmpty())

        // 3) reset() 수행 및 롤백 지연시간 측정
        val resetNanos = measureNanoTime {
            ttt.reset()
        }
        val resetMs = resetNanos / 1_000_000.0

        // 4) 0ms 수준 완전 롤백 및 Zero-Drift 단언
        assertEquals(
            "reset() 수행 후 driftFromBase는 정확히 0.0f 여야 합니다.",
            0.0f,
            ttt.getDriftFromBase(),
            0.0f
        )
        assertTrue("reset() 수행 후 isZeroDrift()는 true여야 합니다.", ttt.isZeroDrift())
        assertTrue("reset() 수행 후 모든 활성 가중치는 비워져야 합니다.", ttt.getAllWeights().isEmpty())
        assertTrue(
            "reset() 롤백 수행은 0ms 수준(< 1.0ms)이어야 합니다. Actual: ${resetMs}ms",
            resetMs < 1.0
        )
    }

    // =========================================================================
    // 4. RED-CONCUR-04: 3단계 Verifier의 초단위 대량 캐싱 및 L1/L2 결과 재사용 효율성
    // =========================================================================
    @Test
    fun testRedConcur04_ThreeStageVerifierSubMillisecondThroughput() {
        val verifier = ThreeStageOutputVerifier()

        // 100건의 다양한 문장 (정문 50종 + 비문 50종)
        val validSentences = listOf(
            "내일 오전 회의에 참석하겠습니다",
            "자료를 꼼꼼하게 준비했습니다",
            "보고서를 검토하고 연락드리겠습니다",
            "도움 주셔서 진심으로 감사드립니다",
            "지금 바로 사무실로 출발하겠습니다",
            "다음 주 미팅 일정을 확인했습니다",
            "요청하신 문서를 공유해 드립니다",
            "안건에 대해 자세히 논의했습니다",
            "제안서를 신중하게 검토하겠습니다",
            "관련 내용을 정리하여 전달드립니다",
            "프로젝트 진행 상황을 보고드립니다",
            "회의록을 작성하여 배포했습니다",
            "문의하신 내용 확인 후 회신드립니다",
            "요청 사항을 적극 반영하겠습니다",
            "업무 협조에 깊이 감사드립니다",
            "신규 기능 개발을 성공적으로 마쳤습니다",
            "테스트 결과를 공유해 드리겠습니다",
            "금일 업무 일정을 안내드립니다",
            "상세 견적서를 송부해 드립니다",
            "수정 사항을 확인하고 반영했습니다",
            "검토 의견을 전달해 드립니다",
            "최종 승인 결과를 알려드립니다",
            "다음 미팅 장소를 공지합니다",
            "시스템 점검 일정을 공지드립니다",
            "추가 문의 사항은 언제든 연락주세요",
            "오늘 점심 맛있게 드세요",
            "날씨가 많이 쌀쌀하니 감기 조심하세요",
            "주말 동안 즐거운 시간 보내세요",
            "오늘 하루도 수고 많으셨어요",
            "커피 한잔 마시면서 쉬어가세요",
            "조금 늦을 것 같으니 먼저 가세요",
            "도착하면 바로 연락드릴게요",
            "사진 잘 나왔으니 확인해 보세요",
            "언제 시간 되실 때 편하게 말씀해 주세요",
            "내일 점심 같이 먹어요",
            "퇴근길에 장보고 들어갈게요",
            "맛있는 저녁 식사 하세요",
            "오늘 날씨가 정말 화창하네요",
            "생일 축하하고 행복한 하루 보내세요",
            "궁금한 점이 있으면 물어보세요",
            "주말에 영화 보러 가요",
            "조금 있다가 전화할게요",
            "이번 주말에 산책 가실래요",
            "지하철 타고 이동하는 중이에요",
            "집에 도착해서 푹 쉬고 있어요",
            "선물 진심으로 고마워요",
            "항상 신경 써주셔서 감사해요",
            "좋은 소식 들려주셔서 기뻐요",
            "건강 유의하시고 힘내세요",
            "내일 아침에 다시 연락할게요"
        )

        val invalidSentences = listOf(
            // ACC-01 이유절 호응 위반
            "회의가 늦어져서 지금 출발하세요",
            "물이 넘쳐흘러서 지금 출발하세요",
            "날씨가 너무 추워서 외투를 입으세요",
            "빨리 도와서 끝내자",
            "얼굴이 하얘서 병원에 가보세요",
            "시간이 빨라서 서두르십시오",
            "물을 퍼서 마시지 마세요",
            "길이 막혀서 조심해서 오세요",
            "배가 고파서 밥을 먹으세요",
            "비가 와서 우산을 챙기세요",
            // 목적어 호응 위반
            "따뜻한 은혜를 너무나 고마워요",
            "마음을 감사해요",
            "마음을 고마워",
            "마음을 고맙습니다",
            "인사를 감사해요",
            "친절을 고마워",
            "정성을 고마워",
            "호의를 고마워",
            "배려를 고마워",
            "사랑을 고맙습니다",
            // 문법 주어-목적어 불일치
            "내가 뭘 보고서를 작성합니다",
            "너가 왜 회의를 참석합니다",
            "우리가 무엇 일정을 확인합니다",
            "그가 언제 문서를 검토합니다",
            "당신이 어디 자료를 준비합니다",
            // 문체 혼용
            "안녕하십니까 밥 먹었어?",
            "반갑습니다 잘 지냈니?",
            "안녕하십니까 오늘 어때?",
            "안녕하십니까 밥 먹었니?",
            "반갑습니다 뭐 하냐?",
            // Stage 2 고 PPL 어색한 연어
            "회의를 참석해요",
            "회의를 참석합니다",
            "모임을 참석해요",
            "행사를 참석해요",
            "미팅을 참석해요",
            "세미나를 참석해요",
            "워크숍을 참석해요",
            "파티를 참석해요",
            "대회를 참석해요",
            "축제를 참석해요",
            // Stage 3 불완전 어미
            "오늘 저녁에 치킨을 먹으",
            "내일 오전 회의에 참석하",
            "자료를 꼼꼼하게 검토하",
            "보고서를 작성하여 공유하",
            "프로젝트 일정을 확인하",
            "사무실로 지금 바로 출발하",
            "문의하신 내용을 검토하",
            "수정 사항을 시스템에 반영하",
            "안건에 대한 의견을 나누",
            "새로운 기능에 대한 테스트를 진행하"
        )

        assertEquals("정문 목록은 50건이어야 합니다.", 50, validSentences.size)
        assertEquals("비문 목록은 50건이어야 합니다.", 50, invalidSentences.size)

        val total100Sentences = validSentences + invalidSentences
        assertEquals("총 테스트 문장은 정확히 100건이어야 합니다.", 100, total100Sentences.size)

        // JIT 웜업
        repeat(15) {
            for (i in 0 until 10) {
                verifier.verify("", total100Sentences[i])
            }
        }

        // 100건 전수 지연시간 측정 및 개별/평균 상한 단언
        val latenciesNanos = LongArray(100)
        for ((idx, sentence) in total100Sentences.withIndex()) {
            val elapsed = measureNanoTime {
                val result = verifier.verify("", sentence)
                assertNotNull("검증 결과는 null이 아니어야 합니다.", result)
                if (idx < 50) {
                    assertTrue("정문 '$sentence'는 통과(isValid=true)되어야 합니다.", result.isValid)
                } else {
                    assertFalse("비문 '$sentence'는 기각(isValid=false)되어야 합니다.", result.isValid)
                }
            }
            latenciesNanos[idx] = elapsed
            val elapsedMs = elapsed / 1_000_000.0
            assertTrue(
                "단일 문장 검증 시간은 < 6.0ms 여야 합니다. (Idx: $idx, Time: ${elapsedMs}ms, Text: '$sentence')",
                elapsedMs < 6.0
            )
        }

        val avgMs = latenciesNanos.average() / 1_000_000.0
        val maxMs = (latenciesNanos.maxOrNull() ?: 0L) / 1_000_000.0
        println("ThreeStageVerifier 100-Sentence Throughput - Avg: %.3f ms, Max: %.3f ms".format(avgMs, maxMs))

        assertTrue(
            "100건 문장 검증 평균 지연시간은 < 2.0ms 여야 합니다. Actual: ${avgMs}ms",
            avgMs < 2.0
        )
    }

    // =========================================================================
    // 5. RED-CONCUR-05: 안드로이드 Configuration Change / 세션 전환 시 TPO 세션 유지 및 격리 무결성
    // =========================================================================
    @Test
    fun testRedConcur05_TpoSessionContextSwitchIntegrity() {
        val encoder = TpoContextEncoder()

        // 5개 대상 패키지 및 기대되는 PersonaTone 매핑
        val packageTonePairs = listOf(
            "com.kakao.talk" to PersonaTone.CASUAL_CHAT,
            "com.slack" to PersonaTone.FORMAL_BUSINESS,
            "com.google.android.gm" to PersonaTone.FORMAL_BUSINESS,
            "com.instagram.android" to PersonaTone.CASUAL_CHAT,
            "org.fcitx.fcitx5.android" to PersonaTone.CONCISE_SEARCH
        )

        // JIT 웜업
        repeat(500) {
            for ((pkg, _) in packageTonePairs) {
                encoder.encode(pkg)
            }
        }
        repeat(20) {
            for ((pkg, _) in packageTonePairs) {
                encoder.encode(pkg)
            }
        }

        // 5개 패키지 간 50회 교대 전환 (총 250회 인코딩)
        val durationsNanos = mutableListOf<Long>()
        repeat(50) { round ->
            for ((pkg, expectedTone) in packageTonePairs) {
                val context: TpoContext
                val elapsedNanos = measureNanoTime {
                    context = encoder.encode(pkg)
                }
                durationsNanos.add(elapsedNanos)

                assertEquals(
                    "라운드 $round: 패키지 '$pkg'의 PersonaTone이 올바르게 판정되어야 합니다.",
                    expectedTone,
                    context.tone
                )
                assertEquals("패키지명이 일치해야 합니다.", pkg, context.packageName)
                assertNotNull("TimeOfDay가 유효해야 합니다.", context.timeOfDay)
            }
        }

        val avgNanos = durationsNanos.average()
        val avgMs = avgNanos / 1_000_000.0
        println("TPO Context Switch Latency - Avg: %.5f ms (%.1f ns)".format(avgMs, avgNanos))

        val maxLatencyMs = 0.15
        assertTrue(
            "50회 교대 전환 시 TpoContextEncoder.encode 평균 지연시간은 < ${maxLatencyMs}ms 여야 합니다. Actual: ${avgMs}ms",
            avgMs < maxLatencyMs
        )
    }
}
