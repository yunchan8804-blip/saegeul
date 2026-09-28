/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Bundled AVENUE catalog used until a remotely signed config exists.
 * Only placements that pass [AdServingPolicy] are included.
 * Frequency limits follow the pilot rules in
 * docs/business/ad-monetization-avenue-operations.md 4.5 and section 13.3.
 */
internal object LocalAvenueCatalog {
    const val TYPING_DNA_SYNC_COMPLETE = "typing-dna-sync-complete"
    const val TYPING_DNA_DASHBOARD_BANNER = "typing-dna-dashboard-banner"
    const val THEME_POINT_EARN = "theme-point-earn"

    val typingDnaSyncComplete = AdVenue(
        id = TYPING_DNA_SYNC_COMPLETE,
        screen = "AI 언어 지문 대시보드",
        trigger = "금고 동기화 완료 뒤(안내 창이 있으면 닫은 뒤)",
        format = AdFormat.INTERSTITIAL,
        requiresConsent = true,
        dailyCap = 1,
        cooldownMinutes = 24L * 60L,
        minActions = 3
    )

    val typingDnaDashboardBanner = AdVenue(
        id = TYPING_DNA_DASHBOARD_BANNER,
        screen = "AI 언어 지문 대시보드",
        trigger = "대시보드 하단 배너 노출",
        format = AdFormat.BANNER,
        requiresConsent = true
    )

    val themePointEarn = AdVenue(
        id = THEME_POINT_EARN,
        screen = "테마 상점",
        trigger = "사용자가 직접 광고 시청으로 포인트 적립 선택",
        format = AdFormat.REWARDED,
        requiresConsent = true,
        dailyCap = 3,
        cooldownMinutes = 20L
    )

    fun defaultConfig(nowEpochMs: Long = System.currentTimeMillis()): SignedAvenueConfig {
        return SignedAvenueConfig(
            version = 1,
            issuedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + YEAR_MS,
            globalKillSwitch = false,
            signature = "local-demo",
            venues = listOf(
                typingDnaSyncComplete,
                typingDnaDashboardBanner,
                themePointEarn
            )
        )
    }

    private const val YEAR_MS = 365L * 24L * 60L * 60L * 1000L
}
