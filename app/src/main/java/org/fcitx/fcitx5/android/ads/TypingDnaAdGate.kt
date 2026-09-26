/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Fail-closed gate for the Typing DNA sync interstitial and dashboard banner.
 * Offline mode, blocked AVENUE placements, and exhausted frequency limits
 * never request an ad.
 */
internal object TypingDnaAdGate {
    fun shouldShowInterstitial(
        offlineMode: Boolean,
        frequency: AvenueFrequencyState = AvenueFrequencyState(),
        nowEpochMs: Long = System.currentTimeMillis(),
        config: SignedAvenueConfig = LocalAvenueCatalog.defaultConfig(nowEpochMs)
    ): Boolean = shouldServe(
        venueId = LocalAvenueCatalog.TYPING_DNA_SYNC_COMPLETE,
        offlineMode = offlineMode,
        frequency = frequency,
        nowEpochMs = nowEpochMs,
        config = config
    )

    fun shouldShowBanner(
        offlineMode: Boolean,
        frequency: AvenueFrequencyState = AvenueFrequencyState(),
        nowEpochMs: Long = System.currentTimeMillis(),
        config: SignedAvenueConfig = LocalAvenueCatalog.defaultConfig(nowEpochMs)
    ): Boolean = shouldServe(
        venueId = LocalAvenueCatalog.TYPING_DNA_DASHBOARD_BANNER,
        offlineMode = offlineMode,
        frequency = frequency,
        nowEpochMs = nowEpochMs,
        config = config
    )

    fun shouldShowThemePointEarn(
        offlineMode: Boolean,
        frequency: AvenueFrequencyState = AvenueFrequencyState(),
        nowEpochMs: Long = System.currentTimeMillis(),
        config: SignedAvenueConfig = LocalAvenueCatalog.defaultConfig(nowEpochMs)
    ): Boolean = shouldServe(
        venueId = LocalAvenueCatalog.THEME_POINT_EARN,
        offlineMode = offlineMode,
        frequency = frequency,
        nowEpochMs = nowEpochMs,
        config = config
    )

    private fun shouldServe(
        venueId: String,
        offlineMode: Boolean,
        frequency: AvenueFrequencyState,
        nowEpochMs: Long,
        config: SignedAvenueConfig
    ): Boolean {
        if (offlineMode) return false
        val decision = AdServingPolicy.evaluate(
            AdServingRequest(
                config = config,
                lastAcceptedVersion = null,
                nowEpochMs = nowEpochMs,
                venueId = venueId
            )
        )
        if (!decision.allow) return false
        val venue = config.venues.find { it.id == venueId } ?: return false
        return AvenueFrequencyPolicy.evaluate(venue, frequency, nowEpochMs) == null
    }
}
