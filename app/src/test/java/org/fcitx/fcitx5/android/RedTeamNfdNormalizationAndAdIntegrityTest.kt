/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import java.text.Normalizer
import org.fcitx.fcitx5.android.ads.AdFormat
import org.fcitx.fcitx5.android.ads.AdServingPolicy
import org.fcitx.fcitx5.android.ads.AdServingRequest
import org.fcitx.fcitx5.android.ads.AdVenue
import org.fcitx.fcitx5.android.ads.AvenueFrequencyPolicy
import org.fcitx.fcitx5.android.ads.AvenueFrequencyState
import org.fcitx.fcitx5.android.ads.BlockReason
import org.fcitx.fcitx5.android.ads.SignedAvenueConfig
import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 6 Extreme Red Team Adversarial Unit Tests:
 * 1. macOS/iOS NFD Decomposed Hangul Normalization and Typo Correction Failure Invariants.
 * 2. Avenue Ad Policy Integrity: Future-dated config fail-closed rejection.
 * 3. Avenue Ad Policy Integrity: Expiration earlier than issuance inversion rejection.
 * 4. Avenue Frequency State Integrity: System clock rollback daily cap bypass protection.
 * 5. Typo Engine Robustness: Edge QWERTY sequences and multi-vowels crash resilience.
 */
class RedTeamNfdNormalizationAndAdIntegrityTest {

    /**
     * Test 1: nfd decomposed hangul string correctly normalizes and detects typos
     *
     * macOS/iOS clipboard or external inputs introduce NFD (decomposed Jamo) strings.
     * The typo engine must normalize NFD representations and detect typos accurately.
     */
    @Test
    fun `nfd decomposed hangul string correctly normalizes and detects typos`() {
        val typoEngine = KoreanTypoCorrectionEngine()
        val nfdTypo = Normalizer.normalize("오눌", Normalizer.Form.NFD)
        val corrections = typoEngine.correct(nfdTypo)
        assertTrue(
            "Corrections for NFD typo '$nfdTypo' must contain '오늘', got: $corrections",
            corrections.contains("오늘")
        )
    }

    /**
     * Test 2: nfd decomposed hangul calculates accurate replacement overlap
     *
     * In an NFD-decomposed sentence like "회의 일정 오눌", the candidate "오늘" should
     * yield an overlap matching the 2-character word length.
     */
    @Test
    fun `nfd decomposed hangul calculates accurate replacement overlap`() {
        val typoEngine = KoreanTypoCorrectionEngine()
        val nfdSentence = Normalizer.normalize("회의 일정 오눌", Normalizer.Form.NFD)
        val overlap = typoEngine.calculateReplacementOverlap(nfdSentence, "오늘")
        assertEquals(
            "Replacement overlap for NFD typo word '오눌' in sentence must be exactly 2",
            2,
            overlap
        )
    }

    /**
     * Test 3: future issued ad config is rejected fail-closed
     *
     * When now = 1_000_000L and config.issuedAtEpochMs = now + 500_000L (future-dated),
     * AdServingPolicy.evaluate MUST reject the config with allow == false and
     * a reason of CONFIG_EXPIRED or CONFIG_NON_MONOTONIC.
     */
    @Test
    fun `future issued ad config is rejected fail-closed`() {
        val now = 1_000_000L
        val venue = AdVenue(
            id = "test-rewarded-future",
            screen = "정보 > 개발자 응원",
            trigger = "click",
            format = AdFormat.REWARDED,
            requiresConsent = true
        )
        val futureConfig = SignedAvenueConfig(
            version = 10L,
            issuedAtEpochMs = now + 500_000L,
            expiresAtEpochMs = now + 1_000_000L,
            globalKillSwitch = false,
            signature = "valid-signature",
            venues = listOf(venue)
        )
        val request = AdServingRequest(
            config = futureConfig,
            lastAcceptedVersion = 9L,
            nowEpochMs = now,
            venueId = "test-rewarded-future"
        )
        val decision = AdServingPolicy.evaluate(request)
        assertFalse(
            "Future-issued ad config must not be allowed (fail-closed)",
            decision.allow
        )
        assertTrue(
            "Future-issued ad config rejection reason must be CONFIG_EXPIRED or CONFIG_NON_MONOTONIC, but got: ${decision.reason}",
            decision.reason == BlockReason.CONFIG_EXPIRED || decision.reason == BlockReason.CONFIG_NON_MONOTONIC
        )
    }

    /**
     * Test 4: ad config with expiration earlier than issuance is rejected
     *
     * Inverted config timestamps (issuedAtEpochMs = 2_000_000L, expiresAtEpochMs = 1_000_000L)
     * must be rejected with allow == false.
     */
    @Test
    fun `ad config with expiration earlier than issuance is rejected`() {
        val now = 500_000L
        val venue = AdVenue(
            id = "test-banner-inverted",
            screen = "정보 > 홈 화면",
            trigger = "view",
            format = AdFormat.BANNER,
            requiresConsent = true
        )
        val invertedConfig = SignedAvenueConfig(
            version = 5L,
            issuedAtEpochMs = 2_000_000L,
            expiresAtEpochMs = 1_000_000L,
            globalKillSwitch = false,
            signature = "valid-signature",
            venues = listOf(venue)
        )
        val request = AdServingRequest(
            config = invertedConfig,
            lastAcceptedVersion = 4L,
            nowEpochMs = now,
            venueId = "test-banner-inverted"
        )
        val decision = AdServingPolicy.evaluate(request)
        assertFalse(
            "Ad config with expiration earlier than issuance must be rejected fail-closed",
            decision.allow
        )
    }

    /**
     * Test 5: time travel backwards in frequency state never resets daily cap
     *
     * When user rolls device time backwards (e.g. today = 49L when recorded dayIndex was 50L),
     * the frequency state daily cap counter must not reset to 0 to bypass capping,
     * and AdServing/FrequencyPolicy must strictly block the venue.
     */
    @Test
    fun `time travel backwards in frequency state never resets daily cap`() {
        val venue = AdVenue(
            id = "test-daily-cap-venue",
            screen = "테스트 화면",
            trigger = "테스트 트리거",
            format = AdFormat.INTERSTITIAL,
            requiresConsent = true,
            dailyCap = 3,
            cooldownMinutes = 0L,
            minActions = 0
        )
        val state = AvenueFrequencyState(
            dayIndex = 50L,
            shownToday = 3,
            lastShownEpochMs = 0L,
            actionsTotal = 10
        )
        val dayMs = 86_400_000L
        val rolledBackEpochMs = 49L * dayMs
        val blockReason = AvenueFrequencyPolicy.evaluate(venue, state, rolledBackEpochMs)
        assertNotNull(
            "Rolling device date backwards (dayIndex 50 -> 49) must not reset daily cap and must block ad serving",
            blockReason
        )
    }

    /**
     * Test 6: qwerty to hangul handles triple vowels and edge sequences without crashing
     *
     * Extreme QWERTY input sequences (triple vowels, repeated consonants, empty, symbols)
     * must produce stable strings without crashing or throwing exceptions.
     */
    @Test
    fun `qwerty to hangul handles triple vowels and edge sequences without crashing`() {
        val edgeInputs = listOf("hkkl", "rrrrr", "", "123!@#", "asdfghjkl", "qwrtyp", "a quick brown fox", "   ", "ㅗㅏㅣ", "ㄱㅅㄷ")
        for (input in edgeInputs) {
            val result = try {
                KoreanTypoCorrectionEngine.qwertyToHangul(input)
            } catch (t: Throwable) {
                throw AssertionError("qwertyToHangul crashed on edge sequence '$input'", t)
            }
            assertNotNull("qwertyToHangul returned null for '$input'", result)
        }
    }
}
