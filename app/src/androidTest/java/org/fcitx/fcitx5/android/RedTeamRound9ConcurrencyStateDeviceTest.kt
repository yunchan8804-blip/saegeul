/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Red Team Round 9: Concurrency & State Machine Integrity E2E Device Test.
 *
 * Scenarios:
 * 1. RED-CONCUR-01: Multi-thread editor commit race condition & morphological ending atomicity.
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
}
