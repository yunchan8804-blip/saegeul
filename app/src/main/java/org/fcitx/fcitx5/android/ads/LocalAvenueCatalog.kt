/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Bundled AVENUE catalog used until a remotely signed config exists.
 * Only placements that pass [AdServingPolicy] are included.
 */
internal object LocalAvenueCatalog {
    const val TYPING_DNA_SYNC_COMPLETE = "typing-dna-sync-complete"

    val typingDnaSyncComplete = AdVenue(
        id = TYPING_DNA_SYNC_COMPLETE,
        screen = "AI 언어 지문 대시보드",
        trigger = "지금 즉시 분석 및 동기화 완료 뒤",
        format = AdFormat.INTERSTITIAL,
        requiresConsent = true
    )

    fun defaultConfig(nowEpochMs: Long = System.currentTimeMillis()): SignedAvenueConfig {
        return SignedAvenueConfig(
            version = 1,
            issuedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + YEAR_MS,
            globalKillSwitch = false,
            signature = "local-demo",
            venues = listOf(typingDnaSyncComplete)
        )
    }

    private const val YEAR_MS = 365L * 24L * 60L * 60L * 1000L
}
