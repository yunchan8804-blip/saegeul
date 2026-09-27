/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.RuleResult
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [KoreanSyntaxRuleFilter].
 *
 * Verifies:
 * 1. ACC-01: Rejection of causal clauses followed by interrogative/imperative endings.
 * 2. ACC-02: Rejection of accusative object markers with intransitive predicates.
 * 3. ACC-04: Rejection of formality / honorific tone inconsistencies in single context.
 * 4. Candidate filtering with prefix context.
 * 5. Stage 1 filter latency budget constraint (<= 0.05ms / 50µs per call).
 */
class KoreanSyntaxRuleFilterTest {

    private lateinit var filter: KoreanSyntaxRuleFilter

    @Before
    fun setUp() {
        filter = KoreanSyntaxRuleFilter()
    }

    @Test
    fun testAcc01CausalSubordinationMismatch() {
        // Violations: -어서/-아서/-여서/-느라고 followed by question or imperative
        val rejectCases = listOf(
            "답장이 늦어서 무슨 일인가요?",
            "비가 와서 우산을 쓰세요",
            "비가 와서 우산을 쓰십시오",
            "시간이 없어서 서두르자",
            "회의가 길어져서 어떡하죠?",
            "밥을 먹느라고 늦었어?",
            "날씨가 추워서 따뜻하게 입으세요",
            "회의가 길어져서 지금 바로 가야 하나요?",
            "차가 너무 막혀서 조심히 오세요",
            "밥을 급하게 먹느라고 체했어?"
        )

        for (text in rejectCases) {
            val result = filter.check(text)
            assertTrue("Expected ACC-01 violation for: '$text', but got $result", result is RuleResult.Invalid)
            val invalid = result as RuleResult.Invalid
            assertEquals(ViolationType.ACC_01_CAUSAL_SUBORDINATION, invalid.violationType)
            assertFalse(filter.isValid(text))
        }

        // Valid cases: declarative statement with -어서, or using -으니까/-니
        val allowCases = listOf(
            "답장이 늦어서 죄송합니다.",
            "비가 와서 길이 미끄럽습니다.",
            "피곤해서 일찍 잤습니다.",
            "비가 오니까 무슨 일인가요?",
            "비가 오니 우산을 쓰세요",
            "시간이 없으니까 서두르자",
            "날씨가 추우니까 따뜻하게 입으세요"
        )

        for (text in allowCases) {
            val result = filter.check(text)
            assertTrue("Expected valid for: '$text', but got $result", result is RuleResult.Valid)
            assertTrue(filter.isValid(text))
        }
    }

    @Test
    fun testAcc02IntransitiveObjectMismatch() {
        // Violations: [을/를] directly bound to intransitive/adjective predicate
        val rejectCases = listOf(
            "고마운 마음을 정말 감사해요",
            "마음을 감사합니다",
            "도움을 감사드립니다",
            "따뜻한 배려를 너무 고마워요",
            "선물을 고맙습니다",
            "그 소식을 슬픕니다",
            "좋은 소식을 기쁩니다",
            "감사한 마음을 너무나 고마워요",
            "선생님의 큰 은혜를 진심으로 감사해요"
        )

        for (text in rejectCases) {
            val result = filter.check(text)
            assertTrue("Expected ACC-02 violation for: '$text', but got $result", result is RuleResult.Invalid)
            val invalid = result as RuleResult.Invalid
            assertEquals(ViolationType.ACC_02_INTRANSITIVE_OBJECT, invalid.violationType)
            assertFalse(filter.isValid(text))
        }

        // Valid cases: correct preposition (-에) or transitive verbs (전하다, 표하다)
        val allowCases = listOf(
            "마음을 전합니다",
            "도움에 감사합니다",
            "따뜻한 배려에 감사드립니다",
            "소식에 슬픕니다",
            "좋은 소식에 기쁩니다",
            "선물을 전해 드립니다"
        )

        for (text in allowCases) {
            val result = filter.check(text)
            assertTrue("Expected valid for: '$text', but got $result", result is RuleResult.Valid)
            assertTrue(filter.isValid(text))
        }
    }

    @Test
    fun testAcc03InterrogativeDiscord() {
        val rejectCases = listOf(
            "내가 뭘 회의 참석합니다",
            "누가 언제 회의합니다",
            "어디서 무엇을 진행합니다",
            "왜 자꾸 회의합니다"
        )
        for (text in rejectCases) {
            val result = filter.check(text)
            assertTrue("Expected ACC-03 violation for: '$text', but got $result", result is RuleResult.Invalid)
            val invalid = result as RuleResult.Invalid
            assertEquals(ViolationType.ACC_03_INTERROGATIVE_DISCORD, invalid.violationType)
            assertFalse(filter.isValid(text))
        }
    }

    @Test
    fun testAcc04FormalityInconsistency() {
        // Violations: mixed formal (-ㅂ니다/-해요) and informal (-어/-지/-냐) in same text
        val rejectCases = listOf(
            "안녕하세요. 밥 먹었어?",
            "감사합니다. 내일 봐.",
            "안녕. 내일 뵙겠습니다.",
            "회의 참석하겠습니다. 어디야?",
            "안녕하세요 밥 먹었어?",
            "오늘 일정 공유합니다. 확인해줘.",
            "안녕하십니까 선배님! 밥 먹었어? 내일 뵐게요."
        )

        for (text in rejectCases) {
            val result = filter.check(text)
            assertTrue("Expected ACC-04 violation for: '$text', but got $result", result is RuleResult.Invalid)
            val invalid = result as RuleResult.Invalid
            assertEquals(ViolationType.ACC_04_FORMALITY_INCONSISTENCY, invalid.violationType)
            assertFalse(filter.isValid(text))
        }

        // Valid cases: consistent formality throughout
        val allowCases = listOf(
            "안녕하세요. 오늘 일정 공유해 드립니다.",
            "안녕하세요. 밥 먹었어요?",
            "안녕. 밥 먹었어?",
            "오늘 회의 참석합니다. 잘 부탁해요.",
            "내일 봐. 잘 자."
        )

        for (text in allowCases) {
            val result = filter.check(text)
            assertTrue("Expected valid for: '$text', but got $result", result is RuleResult.Valid)
            assertTrue(filter.isValid(text))
        }
    }

    @Test
    fun testAcc05StemDuplication() {
        // Violations: "하" stem immediately followed by another "해/했/하여" stem within an eojeol
        val rejectCases = listOf(
            "안녕하해요",
            "공부하했어",
            "오늘 회의 준비하해요",
            "어제 발표 자료를 정리하했어"
        )

        for (text in rejectCases) {
            val result = filter.check(text)
            assertTrue("Expected ACC-05 violation for: '$text', but got $result", result is RuleResult.Invalid)
            val invalid = result as RuleResult.Invalid
            assertEquals(ViolationType.ACC_05_STEM_DUPLICATION, invalid.violationType)
            assertFalse(filter.isValid(text))
        }

        // Valid cases: eojeol-initial "하해" (河海, used on its own) and normal endings must pass
        val allowCases = listOf(
            "하해와 같은 은혜에 감사드립니다",
            "안녕해요",
            "공부해요",
            "안녕하세요"
        )

        for (text in allowCases) {
            val result = filter.check(text)
            assertTrue("Expected valid for: '$text', but got $result", result is RuleResult.Valid)
            assertTrue(filter.isValid(text))
        }
    }

    @Test
    fun testFilterCandidatesWithContext() {
        // ACC-01 filtering with prefix context
        val cands1 = listOf("무슨 일인가요?", "죄송합니다", "우산을 쓰세요", "내일 뵙겠습니다")
        val filtered1 = filter.filterCandidates(cands1, context = "답장이 늦어서")
        assertEquals(listOf("죄송합니다", "내일 뵙겠습니다"), filtered1)

        // ACC-02 filtering with prefix context
        val cands2 = listOf("정말 감사해요", "전합니다", "보냅니다", "너무 고마워요")
        val filtered2 = filter.filterCandidates(cands2, context = "고마운 마음을")
        assertEquals(listOf("전합니다", "보냅니다"), filtered2)

        // ACC-04 filtering with formal prefix
        val cands3 = listOf("밥 먹었어?", "오늘 회의 참석합니다", "내일 봐", "잘 부탁드립니다")
        val filtered3 = filter.filterCandidates(cands3, context = "안녕하세요.")
        assertEquals(listOf("오늘 회의 참석합니다", "잘 부탁드립니다"), filtered3)
    }

    @Test
    fun testStage1FilterLatencyConstraint() {
        // Benchmark 10,000 checks to verify latency is <= 0.05ms (50,000ns)
        val sentences = listOf(
            "답장이 늦어서 무슨 일인가요?",
            "고마운 마음을 정말 감사해요",
            "안녕하세요. 밥 먹었어?",
            "답장이 늦어서 죄송합니다.",
            "마음을 전합니다",
            "안녕하세요. 오늘 일정 공유해 드립니다."
        )

        // Warmup
        for (i in 0 until 1_000) {
            filter.check(sentences[i % sentences.size])
        }

        val iterations = 10_000
        val startNs = System.nanoTime()
        var count = 0
        for (i in 0 until iterations) {
            val s = sentences[i % sentences.size]
            if (filter.isValid(s)) count++
        }
        val durationNs = System.nanoTime() - startNs
        val avgNs = durationNs / iterations.toDouble()
        val avgMs = avgNs / 1_000_000.0

        println("KoreanSyntaxRuleFilter average latency: $avgMs ms ($avgNs ns)")
        // Verify latency is strictly below 0.05ms (50,000ns)
        assertTrue("Stage 1 filter must execute in <= 0.05ms (50,000ns), took $avgMs ms ($avgNs ns)", avgNs <= 50_000.0)
    }
}
