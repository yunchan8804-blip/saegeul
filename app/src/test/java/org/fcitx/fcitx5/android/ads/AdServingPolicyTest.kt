/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdServingPolicyTest {
    private val now = 1_777_000_000_000L

    private val rewarded = AdVenue(
        id = "support-rewarded",
        screen = "정보 > 개발자 응원",
        trigger = "사용자가 보상형 광고 버튼을 직접 선택",
        format = AdFormat.REWARDED,
        requiresConsent = true
    )

    private val settingsEntry = AdVenue(
        id = "settings-entry",
        screen = "정보 > 개발자 응원",
        trigger = "사용자가 보상형 광고 버튼을 직접 선택",
        format = AdFormat.REWARDED,
        requiresConsent = true
    )

    private fun signed(
        venues: List<AdVenue> = listOf(rewarded),
        version: Long = 2,
        signature: String? = "local-demo",
        globalKillSwitch: Boolean = false,
        expiresAtEpochMs: Long = now + 86_400_000L
    ) = SignedAvenueConfig(
        version = version,
        issuedAtEpochMs = now,
        expiresAtEpochMs = expiresAtEpochMs,
        globalKillSwitch = globalKillSwitch,
        signature = signature,
        venues = venues
    )

    private fun request(
        config: SignedAvenueConfig?,
        venueId: String = rewarded.id,
        lastAcceptedVersion: Long? = 1,
        nowEpochMs: Long = now
    ) = AdServingRequest(
        config = config,
        lastAcceptedVersion = lastAcceptedVersion,
        nowEpochMs = nowEpochMs,
        venueId = venueId
    )

    @Test
    fun missingConfigDeniesServing() {
        val decision = AdServingPolicy.evaluate(request(config = null))
        assertFalse(decision.allow)
        assertEquals(BlockReason.CONFIG_MISSING, decision.reason)
    }

    @Test
    fun unsignedConfigDeniesServing() {
        val decision = AdServingPolicy.evaluate(request(signed(signature = null)))
        assertFalse(decision.allow)
        assertEquals(BlockReason.CONFIG_UNSIGNED, decision.reason)
    }

    @Test
    fun expiredConfigDeniesServing() {
        val decision = AdServingPolicy.evaluate(
            request(signed(expiresAtEpochMs = now))
        )
        assertFalse(decision.allow)
        assertEquals(BlockReason.CONFIG_EXPIRED, decision.reason)
    }

    @Test
    fun nonMonotonicConfigDeniesServing() {
        val decision = AdServingPolicy.evaluate(
            request(signed(version = 3), lastAcceptedVersion = 3)
        )
        assertFalse(decision.allow)
        assertEquals(BlockReason.CONFIG_NON_MONOTONIC, decision.reason)
    }

    @Test
    fun killSwitchDeniesServingButDoesNotBlockPublishingPlacement() {
        val decision = AdServingPolicy.evaluate(
            request(signed(globalKillSwitch = true, version = 4), lastAcceptedVersion = 3)
        )
        assertFalse(decision.allow)
        assertEquals(BlockReason.GLOBAL_KILL_SWITCH, decision.reason)
        assertTrue(AdServingPolicy.canPublish(rewarded))
    }

    @Test
    fun settingsEntryCannotBePublishedOrAllowed() {
        val decision = AdServingPolicy.evaluate(
            request(
                signed(venues = listOf(rewarded, settingsEntry), version = 2),
                venueId = settingsEntry.id
            )
        )
        assertFalse(AdServingPolicy.canPublish(settingsEntry))
        assertFalse(decision.allow)
        assertEquals(BlockReason.DESTINATION_INTERRUPT, decision.reason)
    }

    @Test
    fun blockedPlacementsCannotBeAllowed() {
        val placements = listOf(
            AdVenue("ime-surface", "키보드 입력", "composition", AdFormat.NATIVE, true),
            AdVenue("first-launch", "첫 실행", "first launch interstitial", AdFormat.INTERSTITIAL, true),
            AdVenue(
                "permission-interstitial",
                "권한 요청",
                "permission prompt",
                AdFormat.INTERSTITIAL,
                true
            )
        )
        placements.forEach { venue ->
            val decision = AdServingPolicy.evaluate(
                request(signed(venues = listOf(rewarded, venue)), venueId = venue.id)
            )
            assertFalse(AdServingPolicy.canPublish(venue))
            assertFalse(decision.allow)
        }
    }

    @Test
    fun validRewardedOptInIsAllowed() {
        val decision = AdServingPolicy.evaluate(request(signed()))
        assertTrue(AdServingPolicy.canPublish(rewarded))
        assertTrue(decision.allow)
        assertNull(decision.reason)
        assertEquals(2L, decision.configVersion)
    }

    @Test
    fun typingDnaSyncCompleteInterstitialIsAllowed() {
        val venue = LocalAvenueCatalog.typingDnaSyncComplete
        val config = signed(venues = listOf(venue), version = 1)
        val decision = AdServingPolicy.evaluate(
            request(config, venueId = venue.id, lastAcceptedVersion = null)
        )
        assertTrue(AdServingPolicy.canPublish(venue))
        assertTrue(decision.allow)
        assertNull(decision.reason)
    }

    @Test
    fun typingDnaAdGateAllowsSyncCompleteAndBlocksOffline() {
        assertTrue(TypingDnaAdGate.shouldShowInterstitial(offlineMode = false, nowEpochMs = now))
        assertFalse(TypingDnaAdGate.shouldShowInterstitial(offlineMode = true, nowEpochMs = now))
    }
}
