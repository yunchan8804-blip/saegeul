/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Fail-closed gate for the Typing DNA sync interstitial.
 * Offline mode and blocked AVENUE placements never request an ad.
 */
internal object TypingDnaAdGate {
    fun shouldShowInterstitial(
        offlineMode: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
        config: SignedAvenueConfig = LocalAvenueCatalog.defaultConfig(nowEpochMs)
    ): Boolean {
        if (offlineMode) return false
        val decision = AdServingPolicy.evaluate(
            AdServingRequest(
                config = config,
                lastAcceptedVersion = null,
                nowEpochMs = nowEpochMs,
                venueId = LocalAvenueCatalog.TYPING_DNA_SYNC_COMPLETE
            )
        )
        return decision.allow
    }
}
