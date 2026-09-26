/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.KoreanDiscourseContinuation
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceContextCompletionGate
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceRecoveryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Red Team Adversarial Unit Tests: On-Device AI Security Boundaries.
 *
 * Probes:
 * 1. OnDeviceRecoveryBudget non-monotonic timestamp injection and sliding window eviction corruption.
 * 2. KoreanDiscourseContinuation multiline and historical jamo context resilience.
 * 3. OnDeviceContextCompletionGate ticket mismatch, forged ticket, and concurrent invalidation race condition defense.
 */
class RedTeamOnDeviceAiSecurityTest {

    // =========================================================================
    // Attack Vector 1: OnDeviceRecoveryBudget Non-Monotonic Timestamp Injection
    // =========================================================================

    /**
     * Attack Vector 1A: Non-monotonic past timestamp injection and sliding window eviction.
     *
     * Scenario: After consuming a recovery slot at t = 1_000_000L, an out-of-order or malicious
     * recovery attempt is made with a past timestamp t = 500_000L.
     *
     * Invariant:
     * - The sliding window (windowMs = 600_000L) must properly evict expired items.
     * - At t = 1_200_000L:
     *   - 500_000L is 700_000ms in the past (700_000 >= 600_000), so it MUST be expired.
     *   - 1_000_000L is 200_000ms in the past (200_000 < 600_000), so it is active.
     *   - The internal queue must not allow the 1_000_000L entry to block pruning of the 500_000L entry.
     *   - Exactly 1 consumption must remain in window at 1_200_000L.
     */
    @Test
    fun probeNonMonotonicPastTimestampInjectionDoesNotBreakSlidingWindowEviction() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)

        // 1. Initial consumption at t = 1_000_000L
        val firstGranted = budget.tryConsume(1_000_000L)
        assertTrue("Initial recovery at 1_000_000L must be granted", firstGranted)
        assertEquals(1, budget.consumedInWindow(1_000_000L))

        // 2. Adversary injects past consumption at t = 500_000L
        val pastGranted = budget.tryConsume(500_000L)

        // 3. Evaluate window at t = 1_200_000L
        // At 1_200_000L:
        // - 500_000L is 700_000ms old (> 600_000ms) -> EXPIRED
        // - 1_000_000L is 200_000ms old (< 600_000ms) -> STILL ACTIVE
        val activeCount = budget.consumedInWindow(1_200_000L)
        assertEquals(
            "Sliding window must evict the expired 500_000L entry at 1_200_000L despite queue ordering",
            1,
            activeCount
        )

        // Slots should be available for new consumptions at 1_200_000L
        assertTrue(
            "New recovery must be granted at 1_200_000L since only 1 slot is occupied",
            budget.tryConsume(1_200_000L)
        )
    }

    /**
     * Attack Vector 1B: Denial-of-service via past timestamp budget exhaustion attack.
     *
     * Scenario: An attacker spams recovery consumptions using already-expired past timestamps
     * (t = 100_000L, 200_000L, 300_000L) when the current time is 1_000_000L.
     *
     * Invariant:
     * - The budget must not be permanently blocked by expired past entries.
     * - At t = 1_000_000L, legitimate attempts must succeed because the past entries have already expired.
     */
    @Test
    fun probePastTimestampExhaustionDoesNotDenyCurrentLegitimateRecoveries() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)

        // Attempt to consume slots with timestamps older than 600_000ms relative to 1_000_000L
        budget.tryConsume(100_000L)
        budget.tryConsume(200_000L)
        budget.tryConsume(300_000L)

        // At t = 1_000_000L, all entries (100k, 200k, 300k) are at least 700k old (> 600k window)
        assertEquals(
            "Expired past consumptions must not count towards window at 1_000_000L",
            0,
            budget.consumedInWindow(1_000_000L)
        )

        // Legitimate recoveries at 1_000_000L must all be granted
        assertTrue("Legitimate recovery 1 must be granted", budget.tryConsume(1_000_000L))
        assertTrue("Legitimate recovery 2 must be granted", budget.tryConsume(1_000_000L))
        assertTrue("Legitimate recovery 3 must be granted", budget.tryConsume(1_000_000L))
        assertFalse("4th recovery at same timestamp must be rejected", budget.tryConsume(1_000_000L))
    }

    /**
     * Attack Vector 1C: Budget reset restores clean state regardless of past corruptions.
     */
    @Test
    fun probeBudgetResetClearsAllEntries() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)
        budget.tryConsume(1_000_000L)
        budget.tryConsume(500_000L)
        budget.tryConsume(900_000L)

        budget.reset()

        assertEquals("Reset must clear all recorded consumptions", 0, budget.consumedInWindow(1_000_000L))
        assertTrue("Recovery immediately after reset must succeed", budget.tryConsume(1_000_000L))
    }

    // =========================================================================
    // Attack Vector 2: KoreanDiscourseContinuation Multiline & Jamo Contexts
    // =========================================================================

    /**
     * Attack Vector 2A: Multiline context containing newlines ('\n') in preceding sentences.
     *
     * Scenario: User typed previous sentences separated by '\n'.
     * Input: "첫 번째 문장입니다.\n밥 먹었어. "
     *
     * Invariant:
     * - Discourse continuation must recognize the completed sentence on the current line
     *   and return listOf("그리고", "그런데", "이제").
     * - Preceding newlines in previous utterances must NOT cause isSafeContext to reject the input.
     */
    @Test
    fun probeMultilineContextWithTerminalPunctuationSuggestsDiscourseContinuation() {
        val multilineWithTrailingSpace = "첫 번째 문장입니다.\n밥 먹었어. "
        assertEquals(
            "Completed sentence after newline with trailing space must suggest next-sentence continuations",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(multilineWithTrailingSpace)
        )

        val multilineWithoutTrailingSpace = "첫 번째 문장입니다.\n밥 먹었어."
        assertEquals(
            "Completed sentence after newline ending with period must suggest next-sentence continuations",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(multilineWithoutTrailingSpace)
        )

        val multipleNewlinesContext = "첫 문장 끝!\n\n식사하셨습니까? "
        assertEquals(
            "Multiple consecutive newlines must not suppress continuation on completed question",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(multipleNewlinesContext)
        )
    }

    /**
     * Attack Vector 2B: Context containing historical Hangul Jamo (e.g. 'ㅋㅋ', 'ㅠㅠ') in earlier lines.
     *
     * Scenario: In conversational messaging, users frequently type laughter or emotion jamo
     * in prior lines, followed by a new complete sentence.
     * Input: "진짜 너무 웃겨 ㅋㅋㅋ\n밥 먹었어. "
     *
     * Invariant:
     * - The presence of compatibility jamo in earlier lines must NOT poison the safe-context filter
     *   for the current complete Korean sentence.
     */
    @Test
    fun probePreviousUtteranceWithHangulJamoAllowsDiscourseContinuation() {
        val contextWithLaughter = "진짜 너무 웃겨 ㅋㅋㅋ\n밥 먹었어. "
        assertEquals(
            "Context with laughter jamo in preceding line must still suggest next-sentence continuations",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(contextWithLaughter)
        )

        val contextWithCrying = "어제 너무 고생했어 ㅠㅠ\n오늘 푹 쉬자. "
        assertEquals(
            "Context with crying jamo in preceding line must suggest next-sentence continuations",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(contextWithCrying)
        )

        val contextWithSmile = "오늘 날씨 좋다 ㅎㅎ\n산책 갈까? "
        assertEquals(
            "Context with smile jamo in preceding line must suggest next-sentence continuations",
            listOf("그리고", "그런데", "이제"),
            KoreanDiscourseContinuation.suggest(contextWithSmile)
        )
    }

    /**
     * Attack Vector 2C: Terminal newline boundary invariant.
     *
     * Invariant:
     * - When the context ends with '\n' (user just pressed enter without typing next sentence),
     *   discourse continuation must NOT be suggested (returns emptyList).
     */
    @Test
    fun probeTerminalNewlineContextRejectsDiscourseContinuation() {
        val terminalNewline = "밥 먹었어.\n"
        assertTrue(
            "Context ending with newline must NOT suggest discourse continuation",
            KoreanDiscourseContinuation.suggest(terminalNewline).isEmpty()
        )

        val terminalCrlf = "밥 먹었어.\r\n"
        assertTrue(
            "Context ending with CRLF must NOT suggest discourse continuation",
            KoreanDiscourseContinuation.suggest(terminalCrlf).isEmpty()
        )

        val terminalNewlineWithSpace = "밥 먹었어.\n "
        assertTrue(
            "Context with newline followed by space must NOT suggest discourse continuation",
            KoreanDiscourseContinuation.suggest(terminalNewlineWithSpace).isEmpty()
        )

        val onlyNewline = "\n"
        assertTrue(
            "Single newline context must NOT suggest discourse continuation",
            KoreanDiscourseContinuation.suggest(onlyNewline).isEmpty()
        )
    }

    /**
     * Attack Vector 2D: Multiline connective suggestion ('는데', '지만').
     */
    @Test
    fun probeMultilineConnectiveSuggestion() {
        val multilineNde = "어제 비가 많이 왔어.\n밥 먹었는데 "
        assertEquals(
            "Multiline context with connective suffix '는데' must suggest connective words",
            listOf("아직", "생각보다", "그래도"),
            KoreanDiscourseContinuation.suggest(multilineNde)
        )

        val multilineJiman = "회의가 길어졌지만,\n시간이 늦었지만 "
        assertEquals(
            "Multiline context with contrast suffix '지만' must suggest contrast words",
            listOf("그래도", "아직"),
            KoreanDiscourseContinuation.suggest(multilineJiman)
        )
    }

    // =========================================================================
    // Attack Vector 3: OnDeviceContextCompletionGate Mismatch & Concurrent Race
    // =========================================================================

    /**
     * Attack Vector 3A: Forged ticket rejection and active ticket protection.
     *
     * Invariant:
     * - Calling publish, invalidate, nativeStopped, or takeForApply with a forged / foreign Ticket
     *   must return false / null.
     * - A forged ticket must NOT alter or invalidate the legitimate active run.
     */
    @Test
    fun probeForgedTicketMismatchRejectedAndActiveTicketProtected() {
        val gate = OnDeviceContextCompletionGate()
        val snapshot = OnDeviceContextCompletionGate.Snapshot(1L, "가나다라 마바사", 0, 8)

        val legitTicket = gate.begin(snapshot)
        assertNotNull("Legitimate ticket must be issued", legitTicket)

        // Foreign ticket created by a different gate instance
        val foreignGate = OnDeviceContextCompletionGate()
        val foreignTicket = foreignGate.begin(snapshot)!!

        assertFalse("Publish with foreign ticket must be rejected", gate.publish(foreignTicket, snapshot, "악의적 완성"))
        assertFalse("Invalidate with foreign ticket must be rejected", gate.invalidate(foreignTicket))
        assertFalse("nativeStopped with foreign ticket must be rejected", gate.nativeStopped(foreignTicket))
        assertNull("takeForApply with foreign ticket must return null", gate.takeForApply(foreignTicket, snapshot))

        // Legitimate ticket must proceed unhindered
        assertTrue("Legitimate ticket publish must succeed", gate.publish(legitTicket!!, snapshot, "정상 완성"))
        assertTrue("Legitimate ticket nativeStopped must succeed", gate.nativeStopped(legitTicket))
        assertEquals("Legitimate completion must be applied", "정상 완성", gate.takeForApply(legitTicket, snapshot))
    }

    /**
     * Attack Vector 3B: Stale ticket from completed run cannot tamper with subsequent run.
     */
    @Test
    fun probeStaleTicketFromCompletedRunCannotInterfereWithNewRun() {
        val gate = OnDeviceContextCompletionGate()
        val snap1 = OnDeviceContextCompletionGate.Snapshot(1L, "첫 번째 문맥", 0, 6)
        val snap2 = OnDeviceContextCompletionGate.Snapshot(2L, "두 번째 문맥", 0, 6)

        // Run 1 completes
        val ticket1 = gate.begin(snap1)!!
        gate.publish(ticket1, snap1, "완성1")
        gate.nativeStopped(ticket1)
        assertEquals("완성1", gate.takeForApply(ticket1, snap1))

        // Run 2 begins
        val ticket2 = gate.begin(snap2)!!

        // Stale ticket1 attempts to tamper with Run 2
        assertFalse("Stale ticket1 invalidate must be rejected", gate.invalidate(ticket1))
        assertFalse("Stale ticket1 publish must be rejected", gate.publish(ticket1, snap2, "오염 완성"))
        assertFalse("Stale ticket1 nativeStopped must be rejected", gate.nativeStopped(ticket1))
        assertNull("Stale ticket1 takeForApply must return null", gate.takeForApply(ticket1, snap2))

        // Run 2 completes normally
        assertTrue("Ticket 2 publish must succeed", gate.publish(ticket2, snap2, "완성2"))
        assertTrue("Ticket 2 nativeStopped must succeed", gate.nativeStopped(ticket2))
        assertEquals("완성2", gate.takeForApply(ticket2, snap2))
    }

    /**
     * Attack Vector 3C: Concurrent invalidation race condition defense.
     *
     * Invariant:
     * - Multiple concurrent threads calling invalidate() on the same active ticket must execute
     *   safely without unhandled exceptions or inconsistent state.
     * - After concurrent invalidation, takeForApply must strictly return null.
     */
    @Test
    fun probeConcurrentInvalidationRaceConditionDefense() {
        val gate = OnDeviceContextCompletionGate()
        val snapshot = OnDeviceContextCompletionGate.Snapshot(10L, "동시 무효화 테스트", 0, 9)
        val ticket = gate.begin(snapshot)!!

        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                startSignal.await()
                if (gate.invalidate(ticket)) {
                    successCount.incrementAndGet()
                }
                latch.countDown()
            }
        }

        startSignal.countDown()
        assertTrue("Threads must complete within timeout", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // At least one invalidate returned true, and run is invalidated
        assertTrue("At least one invalidate must succeed", successCount.get() >= 1)

        // Native stops afterwards
        gate.nativeStopped(ticket)

        // takeForApply must strictly return null
        assertNull("takeForApply after invalidation must strictly return null", gate.takeForApply(ticket, snapshot))
    }

    /**
     * Attack Vector 3D: Concurrent begin() mutual exclusion invariant.
     *
     * Invariant:
     * - When multiple threads concurrently attempt to begin a completion on an idle gate,
     *   EXACTLY ONE thread must receive a non-null ticket; all other threads must receive null.
     */
    @Test
    fun probeConcurrentBeginMutualExclusion() {
        val gate = OnDeviceContextCompletionGate()
        val snapshot = OnDeviceContextCompletionGate.Snapshot(20L, "상호 배제 테스트 문맥", 0, 10)

        val threadCount = 16
        val latch = CountDownLatch(threadCount)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val acquiredTickets = java.util.Collections.synchronizedList(mutableListOf<OnDeviceContextCompletionGate.Ticket>())

        for (i in 0 until threadCount) {
            executor.submit {
                startSignal.await()
                val ticket = gate.begin(snapshot)
                if (ticket != null) {
                    acquiredTickets.add(ticket)
                }
                latch.countDown()
            }
        }

        startSignal.countDown()
        assertTrue("All threads must finish within timeout", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exactly 1 thread must acquire a ticket under concurrent begin()", 1, acquiredTickets.size)
    }
}
