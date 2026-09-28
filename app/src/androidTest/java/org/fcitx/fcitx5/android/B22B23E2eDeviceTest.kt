/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.runner.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.fcitx.fcitx5.android.input.ai.KoreanSentenceEndingExtractor
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaProfiler
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android E2E Device & Instrumentation Test for B22 (0 ending collection rate)
 * and B23 (cross-session buffer leak) regression verification.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class B22B23E2eDeviceTest {

    private val b22SampleSentences = listOf(
        "오늘 회의 참석합니다",
        "점심 먹었어",
        "확인 부탁드려요",
        "내일 봐",
        "자료 보냈음",
        "고마워",
        "축하해요",
        "지금 출발해요",
        "조금 늦을게요",
        "다음에 보자",
        "확인했습니다",
        "잘 부탁드립니다",
        "날씨가 춥네요",
        "언제 볼까요",
        "정말 감사용",
        "다녀왔습니다",
        "완전 대박이네",
        "같이 먹자",
        "내일 갈게",
        "일정 확인했음",
        "밥 먹었니",
        "진짜 고마워요",
        "내일 연락드릴게요",
        "수고하셨습니다"
    )

    /**
     * B22 회귀 검증:
     * 마침표 없는 24개 한국어 실제 입력 문장에 대해 어미 수집률 > 0건 (15건 이상 추출) 검증.
     */
    @Test
    fun testB22EndingExtractionRateWithoutPunctuation() {
        var extractedCount = 0
        val extractedEndings = mutableListOf<String>()

        for (sentence in b22SampleSentences) {
            val ending = KoreanSentenceEndingExtractor.endingOf(sentence)
            if (!ending.isNullOrEmpty()) {
                extractedCount++
                extractedEndings.add(ending)
            }
        }

        assertTrue(
            "어미 수집률은 0건이 아니어야 하며 15건 이상 추출되어야 합니다 (B22 근절). 실제 추출: $extractedCount / ${b22SampleSentences.size} ($extractedEndings)",
            extractedCount >= 15
        )

        // KoreanSentenceEndingExtractor.topEndings() 검증
        val topEndings = KoreanSentenceEndingExtractor.topEndings(b22SampleSentences, limit = 10)
        assertTrue("topEndings 결과가 비어있지 않아야 합니다.", topEndings.isNotEmpty())

        // TypingDnaProfiler.profileOnDevice() 연동 검증
        val profiler = TypingDnaProfiler()
        val persona = profiler.profileOnDevice(TypingDnaVault.CATEGORY_MESSENGER, b22SampleSentences)
        assertTrue("프로파일링된 habitualEndings가 비어있지 않아야 합니다.", persona.habitualEndings.isNotEmpty())
        assertTrue("프로파일링된 어미 수가 0보다 커야 합니다.", persona.habitualEndings.size > 0)
    }

    /**
     * 형태소 분석기 직접 검증:
     * 다양한 어미 스타일(격식체, 해요체, 해체, 선어말어미 결합, 받침 승격 등)이 정상 분해/추출되는지 검증.
     */
    @Test
    fun testMorphologicalAnalyzerDirectCoverage() {
        val testPairs = listOf(
            "참석합니다" to "합니다",
            "먹었어" to "었어",
            "부탁드려요" to "려요",
            "보냈음" to "음",
            "축하해요" to "해요",
            "늦을게요" to "을게요",
            "춥네요" to "네요",
            "볼까요" to "ㄹ까요",
            "감사용" to "용",
            "갈게" to "ㄹ게"
        )
        for ((word, expectedEnding) in testPairs) {
            val ending = KoreanMorphologicalEndingAnalyzer.extractEnding(word)
            assertNotNull("'$word'에서 어미가 추출되어야 합니다.", ending)
            assertTrue(
                "'$word'의 추출 어미 '$ending'은 '$expectedEnding'과 매칭되어야 합니다.",
                ending?.contains(expectedEnding) == true || expectedEnding.contains(ending!!)
            )
        }
    }

    /**
     * B23 회귀 검증:
     * 패키지 A(com.kakao.talk) 입력 중 패키지 B(com.slack)로 onEditorSessionStarted 세션 전환 시
     * 이전 버퍼가 원자적으로 소거되어 잔여 문맥 누출 0건 검증.
     */
    @Test
    fun testB23AtomicDiscardOnEditorSessionBoundarySwitch() {
        val collector = UserTypingContextCollector()

        val pkgKakao = "com.kakao.talk"
        val pkgSlack = "com.slack"

        // 1. 카카오톡 세션 시작 및 미완결 문장 버퍼링
        collector.onEditorSessionStarted(pkgKakao, fieldId = 1001, restarting = false)
        collector.recordCommittedText(pkgKakao, "안녕하세요 카카오톡 비밀 대화 중입니다만 아직 작성 중인 ")
        assertTrue("카카오톡 버퍼에 미완료 텍스트가 대기 중이어야 합니다.", collector.hasPending(pkgKakao))

        // 2. 다른 앱(슬랙)으로 세션 전환 발생 (onEditorSessionStarted)
        collector.onEditorSessionStarted(pkgSlack, fieldId = 2001, restarting = false)

        // 3. 이전 세션의 미완료 버퍼가 원자적으로 소거되어 잔여 문맥 누출 0건인지 검증
        assertFalse("세션 전환 후 카카오톡 대기 버퍼가 완전히 소거되어야 합니다 (B23 근절).", collector.hasPending(pkgKakao))
        assertFalse("슬랙 대기 버퍼 역시 비어있어야 합니다.", collector.hasPending(pkgSlack))
        assertEquals("카카오톡 최근 문맥이 누출되지 않아야 합니다.", "", collector.getRecentContext(pkgKakao))
        assertEquals("슬랙 최근 문맥에 이전 앱 내용이 섞이지 않아야 합니다.", "", collector.getRecentContext(pkgSlack))

        // 4. 슬랙에서 새 문장 입력 시 이전 앱의 데이터가 혼합되지 않는지 격리 무결성 검증
        collector.recordCommittedText(pkgSlack, "슬랙 긴급 업무 보고 완료했습니다.")
        val slackContext = collector.getRecentContext(pkgSlack)
        assertTrue("슬랙 문맥에 입력한 내용이 포함되어야 합니다.", slackContext.contains("슬랙 긴급 업무 보고 완료했습니다"))
        assertFalse("슬랙 문맥에 카카오톡 비밀 대화 내용이 절대로 섞여 나오지 않아야 합니다.", slackContext.contains("카카오톡"))
        assertFalse("슬랙 문맥에 '비밀 대화'가 포함되지 않아야 합니다.", slackContext.contains("비밀 대화"))
    }

    /**
     * 동일 에디터 세션 재시작 vs 신규 필드 이동 시 버퍼 관리 검증:
     * 동일 필드 restart는 버퍼를 보존하고, 신규 필드 진입은 원자적으로 소거함을 보장.
     */
    @Test
    fun testB23SameSessionRestartPreservesPendingBuffer() {
        val collector = UserTypingContextCollector()
        val pkg = "com.kakao.talk"

        collector.onEditorSessionStarted(pkg, fieldId = 1001, restarting = false)
        collector.recordCommittedText(pkg, "작성 중인 문서의 초안 ")
        assertTrue(collector.hasPending(pkg))

        // 동일 필드 재시작 (restarting = true, 동일 fieldId) 시 버퍼 보존
        collector.onEditorSessionStarted(pkg, fieldId = 1001, restarting = true)
        assertTrue("동일 필드 restart 시에는 버퍼가 보존되어야 합니다.", collector.hasPending(pkg))

        // 다른 필드로 이동 시 버퍼 원자적 소거
        collector.onEditorSessionStarted(pkg, fieldId = 1002, restarting = false)
        assertFalse("새로운 fieldId로 이동 시 이전 필드의 버퍼는 원자적으로 소거되어야 합니다.", collector.hasPending(pkg))
    }
}
