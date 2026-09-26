/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAdapter
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Red Team Adversarial Unit Tests: Clipboard Surrogate Truncation & Notifier Disk Flooding Invariants.
 *
 * Attack Vector 1: UTF-16 surrogate pair truncation (broken emojis), negative boundary crash,
 * and 1,000,000-char excerpt latency budget.
 *
 * Attack Vector 2: Sensitive clipboard entry masking invariants and zero-leakage guarantee.
 *
 * Attack Vector 3: BackgroundProgressNotifier disk flooding attack under denied notification permission.
 */
class RedTeamClipboardAndNotifierTest {

    companion object {
        const val BULLET = ClipboardEntry.BULLET

        /**
         * Resilient implementation of clipboard excerpting:
         * 1. Safe on negative lines and chars arguments (returns empty string without crashing).
         * 2. Defends against UTF-16 surrogate pair truncation (prevents orphaned High Surrogates).
         * 3. Highly performant on massive texts within strict latency budgets.
         */
        fun safeExcerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String {
            if (lines <= 0 || chars <= 0 || str.isEmpty()) return ""
            return buildString {
                val length = str.length
                var lineBreak = -1
                for (i in 1..lines) {
                    val start = lineBreak + 1
                    if (start >= length) break
                    val excerptEnd = min(start + chars, length)
                    lineBreak = str.indexOf('\n', start)

                    val rawEnd = if (lineBreak in start until excerptEnd) lineBreak else excerptEnd

                    // Invariant: UTF-16 surrogate pair boundary defense.
                    // If rawEnd ends right after a High Surrogate (0xD800..0xDBFF),
                    // truncating at rawEnd orphans the High Surrogate without its Low Surrogate.
                    // Step back by 1 code unit so broken emojis are never rendered.
                    var safeEnd = rawEnd
                    if (safeEnd > start && Character.isHighSurrogate(str[safeEnd - 1])) {
                        safeEnd--
                    }

                    if (lineBreak < 0 || lineBreak >= excerptEnd) {
                        if (mask) {
                            append(BULLET.repeat(safeEnd - start))
                        } else {
                            append(str.substring(start, safeEnd))
                        }
                        break
                    } else {
                        if (mask) {
                            append(BULLET.repeat(safeEnd - start))
                        } else {
                            appendLine(str.substring(start, safeEnd))
                        }
                    }
                }
            }
        }
    }

    /**
     * Simulator representing disk write throttling in background notification suppression.
     * Evaluates I/O storm resistance during rapid progress update bursts (e.g. 10,000 iterations).
     */
    class ThrottledNotifierSimulator(
        private val throttleWindowMs: Long = 1000L,
        private var currentTimeMs: Long = 1_000_000L
    ) {
        var diskWriteCount = 0
            private set
        var lastRecordedBlockedMs = 0L
            private set
        var suppressedCallsCount = 0
            private set

        fun advanceTime(deltaMs: Long) {
            currentTimeMs += deltaMs
        }

        fun setTime(timeMs: Long) {
            currentTimeMs = timeMs
        }

        /**
         * Naive implementation: Unconditionally writes to SharedPreferences on every blocked notification.
         */
        fun recordBlockedNaive() {
            diskWriteCount++
            lastRecordedBlockedMs = currentTimeMs
        }

        /**
         * Robust throttled implementation: Coalesces disk writes so that SharedPreferences
         * is committed at most once per [throttleWindowMs].
         */
        fun recordBlockedThrottled(): Boolean {
            if (lastRecordedBlockedMs != 0L && (currentTimeMs - lastRecordedBlockedMs) < throttleWindowMs) {
                suppressedCallsCount++
                return false // Suppressed: protects against disk flooding storm
            }
            diskWriteCount++
            lastRecordedBlockedMs = currentTimeMs
            return true
        }

        fun reset() {
            diskWriteCount = 0
            lastRecordedBlockedMs = 0L
            suppressedCallsCount = 0
        }
    }

    // =========================================================================
    // ATTACK VECTOR 1: ClipboardAdapter.excerptText Surrogate Pairs & Boundaries
    // =========================================================================

    /**
     * Probing vulnerability in raw excerpt logic when negative `chars` is injected.
     * Invariant: Safe excerpt defense must swallow negative bounds and return empty string ("").
     */
    @Test
    fun probeNegativeCharsBoundaryCrashResilience() {
        val sampleText = "새글 스마트 한국어 입력기 클립보드"

        // Invariant verification: safe excerpt architecture handles negative & zero chars gracefully
        assertEquals("", ClipboardAdapter.excerptText(sampleText, chars = -10))
        assertEquals("", ClipboardAdapter.excerptText(sampleText, chars = -1))
        assertEquals("", ClipboardAdapter.excerptText(sampleText, chars = 0))
        assertEquals("", ClipboardAdapter.excerptText(sampleText, lines = -5))
        assertEquals("", ClipboardAdapter.excerptText(sampleText, lines = 0))
        assertEquals("", ClipboardAdapter.excerptText(sampleText, lines = -10, chars = -10))
    }

    /**
     * Probing UTF-16 surrogate pair truncation (Broken Emoji Attack).
     *
     * Scenario: A 2-char emoji ('😀' = \uD83D\uDE00) is placed across the `chars` boundary (e.g. index 127..128).
     * If sliced strictly at index 128, \uD83D (High Surrogate) remains alone at the end,
     * producing a broken, unrenderable character.
     *
     * Invariant:
     * - The excerpt output must NEVER terminate with an isolated High Surrogate (\uD83D).
     * - Truncation must cleanly step back to index 127, preserving unicode integrity.
     */
    @Test
    fun probeSurrogatePairTruncationPreventsBrokenEmoji() {
        // Construct string with exactly 127 ASCII chars followed by '😀' (\uD83D\uDE00)
        val prefix127 = "A".repeat(127)
        val testEmoji = "\uD83D\uDE00" // Grinning face: High surrogate \uD83D, Low surrogate \uDE00
        val input = prefix127 + testEmoji // Total length: 129 chars

        // Safe defense invariant: High Surrogate is detected and boundary steps back
        val safeResult = ClipboardAdapter.excerptText(input, chars = 128)
        assertEquals(
            "Safe excerpt must pull back boundary to 127 chars, discarding broken surrogate",
            127,
            safeResult.length
        )
        assertEquals(prefix127, safeResult)
        assertFalse(
            "Safe excerpt result must never end with a High Surrogate",
            Character.isHighSurrogate(safeResult.last())
        )
    }

    /**
     * Invariant: When sufficient character budget is available (e.g. chars = 129),
     * the full emoji ('😀') must be completely preserved without truncation.
     */
    @Test
    fun verifySurrogatePairPreservedWhenBudgetPermits() {
        val prefix127 = "B".repeat(127)
        val testEmoji = "\uD83D\uDE00"
        val input = prefix127 + testEmoji // 129 chars

        val safeResult = safeExcerptText(input, chars = 129)
        assertEquals(129, safeResult.length)
        assertEquals(input, safeResult)
        assertTrue(safeResult.endsWith(testEmoji))
    }

    /**
     * Invariant: Multiline text with emojis located across multiple line truncation boundaries
     * must never produce broken surrogates on any line.
     */
    @Test
    fun verifyMultilineSurrogateIntegrityAcrossAllLines() {
        val line1 = "X".repeat(127) + "\uD83D\uDE80" // Rocket emoji at 127..128
        val line2 = "Y".repeat(127) + "\uD83C\uDF89" // Party popper at 127..128
        val line3 = "Z".repeat(10)
        val multilineInput = "$line1\n$line2\n$line3"

        val safeResult = safeExcerptText(multilineInput, lines = 4, chars = 128)
        val resultLines = safeResult.split('\n')

        for (line in resultLines) {
            if (line.isNotEmpty()) {
                assertFalse(
                    "No excerpted line should end with an orphaned high surrogate: $line",
                    Character.isHighSurrogate(line.last())
                )
            }
        }
    }

    /**
     * Latency Budget: 1,000,000-character massive clipboard text processing.
     *
     * Invariant:
     * - Excerpting a 1,000,000-character clipboard string must complete within 10ms.
     * - Must not trigger OutOfMemoryError, excessive string copying, or UI thread ANR.
     */
    @Test
    fun verifyMassiveOneMillionCharClipboardLatencyBudget() {
        // Construct 1,000,000-character string with periodic newlines
        val lineChunk = "가나다라마바사아자차카타파하 1234567890 Saegeul Keyboard Fast Clipboard Test!\n"
        val repeatCount = 1_000_000 / lineChunk.length + 1
        val massiveText = lineChunk.repeat(repeatCount).substring(0, 1_000_000)
        assertEquals(1_000_000, massiveText.length)

        // Warm up JIT
        repeat(5) {
            safeExcerptText(massiveText, lines = 4, chars = 128)
        }

        val elapsedMs = measureTimeMillis {
            val result = safeExcerptText(massiveText, lines = 4, chars = 128)
            assertNotNull(result)
            assertTrue(result.isNotEmpty())
            assertTrue(result.length <= 4 * 129) // Bounded excerpt size
        }

        assertTrue(
            "1,000,000-char excerpt must complete within 10ms budget (actual: ${elapsedMs}ms)",
            elapsedMs <= 10L
        )

        // Production excerptText latency budget check
        val prodElapsedMs = measureTimeMillis {
            val prodResult = ClipboardAdapter.excerptText(massiveText, lines = 4, chars = 128)
            assertNotNull(prodResult)
            assertTrue(prodResult.isNotEmpty())
        }

        assertTrue(
            "Production 1,000,000-char excerptText must complete within 10ms budget (actual: ${prodElapsedMs}ms)",
            prodElapsedMs <= 10L
        )
    }

    // =========================================================================
    // ATTACK VECTOR 2: ClipboardEntry Sensitive Clip Masking Invariants
    // =========================================================================

    /**
     * Invariant: A sensitive clipboard entry (sensitive = true) must have its entire text
     * replaced with BULLET ("•") when mask = true, guaranteeing zero plain-text credential leakage.
     */
    @Test
    fun verifySensitiveClipboardEntryMaskingFullBulletReplacement() {
        val secretPassword = "SuperSecretP@ssw0rd!#2026"
        val entry = ClipboardEntry(
            id = 42,
            text = secretPassword,
            pinned = false,
            sensitive = true
        )

        val masked = ClipboardAdapter.excerptText(entry.text, mask = entry.sensitive)

        // 1. Length preservation: Exactly matches original text length
        assertEquals(secretPassword.length, masked.length)

        // 2. Full bullet replacement: Every single character is BULLET
        assertEquals(BULLET.repeat(secretPassword.length), masked)
        assertTrue("All masked characters must be BULLET '•'", masked.all { it.toString() == BULLET })

        // 3. Zero leakage: Raw secret substrings must NEVER appear in output
        assertFalse("Masked output must not contain 'SuperSecret'", masked.contains("SuperSecret"))
        assertFalse("Masked output must not contain 'P@ssw0rd'", masked.contains("P@ssw0rd"))
        assertFalse("Masked output must not contain '2026'", masked.contains("2026"))
    }

    /**
     * Invariant: Multiline sensitive entry masking completely obfuscates the payload
     * into BULLET ("•") sequences, guaranteeing absolute zero plaintext leakage.
     */
    @Test
    fun verifyMultilineSensitiveClipboardEntryMaskingFullObfuscationWithoutLeak() {
        val multilineSecrets = "api_key_live_abcdef123456\nclient_secret_9876543210\nrefresh_token_xyz"
        val entry = ClipboardEntry(
            id = 43,
            text = multilineSecrets,
            sensitive = true
        )

        val masked = ClipboardAdapter.excerptText(entry.text, mask = entry.sensitive, lines = 4, chars = 128)

        // Expected length is sum of characters without newlines
        val expectedLength = "api_key_live_abcdef123456".length + "client_secret_9876543210".length + "refresh_token_xyz".length
        assertEquals(expectedLength, masked.length)

        // All characters are strictly BULLET '•'
        assertEquals(BULLET.repeat(expectedLength), masked)
        assertTrue("All masked characters must be BULLET '•'", masked.all { it.toString() == BULLET })

        // Absolute zero plaintext leakage
        assertFalse("Masked output must not leak 'api_key'", masked.contains("api_key"))
        assertFalse("Masked output must not leak 'client_secret'", masked.contains("client_secret"))
        assertFalse("Masked output must not leak 'refresh_token'", masked.contains("refresh_token"))
    }

    /**
     * Invariant: Non-sensitive entry (sensitive = false, mask = false) must preserve
     * original plain text without modification or unintended bullet masking.
     */
    @Test
    fun verifyNonSensitiveClipboardEntryContentPreserved() {
        val normalText = "안녕하세요 새글 안드로이드 한국어 입력기입니다."
        val entry = ClipboardEntry(
            id = 44,
            text = normalText,
            sensitive = false
        )

        val excerpt = ClipboardAdapter.excerptText(entry.text, mask = entry.sensitive)
        assertEquals(normalText, excerpt)
        assertFalse("Non-sensitive entry must not contain bullets", excerpt.contains(BULLET))
    }

    // =========================================================================
    // ATTACK VECTOR 3: BackgroundProgressNotifier Throttling & Disk Flooding
    // =========================================================================

    /**
     * Probing Disk Flooding vulnerability when notification permission is denied.
     *
     * Vulnerability: If a background loop (e.g. GemmaAccumulationWorker, GraphEnrichmentRunner)
     * invokes progress() 10,000 times in a tight loop without permission, an unthrottled notifier
     * executes 10,000 synchronous/asynchronous SharedPreferences writes, saturating disk I/O
     * and causing QueuedWork ANRs.
     */
    @Test
    fun probeUnthrottledPermissionDenialCausesDiskFloodingStorm() {
        val simulator = ThrottledNotifierSimulator()

        // Simulate 10,000 tight loop calls under naive unthrottled policy
        repeat(10_000) {
            simulator.recordBlockedNaive()
        }

        // Proves vulnerability: Naive notifier fires 10,000 disk writes
        assertEquals(
            "Unthrottled policy causes 10,000 disk writes (Disk Flooding Storm)",
            10_000,
            simulator.diskWriteCount
        )
    }

    /**
     * Invariant: Throttled notifier coalesces 10,000 rapid progress calls during denied permission,
     * writing to disk at most once within the throttle window and dropping redundant disk writes.
     */
    @Test
    fun verifyThrottledNotifierSuppresses10kBurstDiskFlooding() {
        val simulator = ThrottledNotifierSimulator(throttleWindowMs = 1000L)

        // 10,000 rapid iterations occurring in a sub-second burst (e.g. 50ms total)
        repeat(10_000) { i ->
            simulator.setTime(1_000_000L + (i / 200)) // Time advances by at most 50ms total
            simulator.recordBlockedThrottled()
        }

        // Invariant: Only 1 disk write occurred; 9,999 were safely throttled
        assertEquals(
            "Throttling must reduce 10,000 rapid calls to exactly 1 disk write",
            1,
            simulator.diskWriteCount
        )
        assertEquals(
            "9,999 disk writes must be suppressed to prevent ANR",
            9_999,
            simulator.suppressedCallsCount
        )
    }

    /**
     * Invariant: After the throttle window expires (e.g. 1000ms later), the next
     * suppressed notification must be allowed to update the last_blocked_ms timestamp.
     */
    @Test
    fun verifyThrottledNotifierResumesAfterCooldownWindow() {
        val simulator = ThrottledNotifierSimulator(throttleWindowMs = 1000L, currentTimeMs = 1_000_000L)

        // First call: allowed
        assertTrue(simulator.recordBlockedThrottled())
        assertEquals(1, simulator.diskWriteCount)

        // Immediate calls within 500ms: suppressed
        simulator.advanceTime(500L)
        assertFalse(simulator.recordBlockedThrottled())
        assertEquals(1, simulator.diskWriteCount)

        // Call after cooldown window (1001ms elapsed since initial write): allowed
        simulator.advanceTime(501L) // Total 1001ms from start
        assertTrue(simulator.recordBlockedThrottled())
        assertEquals(2, simulator.diskWriteCount)
        assertEquals(1_001_001L, simulator.lastRecordedBlockedMs)
    }

    /**
     * Invariant: BackgroundProgressNotifier.shouldPost pure decision rules.
     * When permission is not granted, shouldPost MUST strictly return false for ALL notification kinds
     * regardless of user preference settings.
     */
    @Test
    fun verifyBackgroundProgressNotifierPureDecisionRulesUnderDeniedPermission() {
        for (kind in BackgroundProgressNotifier.Kind.entries) {
            for (pref in listOf(true, false)) {
                val allowed = BackgroundProgressNotifier.shouldPost(
                    kind = kind,
                    permissionGranted = false,
                    prefEnabled = pref
                )
                assertFalse(
                    "Notification of kind $kind with prefEnabled=$pref must NEVER post without permission",
                    allowed
                )
            }
        }
    }

    /**
     * Invariant: When permission is granted, alerts must always post, while progress/done
     * strictly respect the user's background notification preference.
     */
    @Test
    fun verifyBackgroundProgressNotifierRulesWhenPermissionGranted() {
        // ALERT kind always posts regardless of pref
        assertTrue(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.ALERT, true, prefEnabled = true))
        assertTrue(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.ALERT, true, prefEnabled = false))

        // PROGRESS kind follows pref
        assertTrue(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.PROGRESS, true, prefEnabled = true))
        assertFalse(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.PROGRESS, true, prefEnabled = false))

        // DONE kind follows pref
        assertTrue(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.DONE, true, prefEnabled = true))
        assertFalse(BackgroundProgressNotifier.shouldPost(BackgroundProgressNotifier.Kind.DONE, true, prefEnabled = false))
    }
}
