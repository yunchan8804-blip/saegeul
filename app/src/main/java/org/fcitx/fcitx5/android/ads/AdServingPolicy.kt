/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Fail-closed serving and publish evaluation for AVENUE configs.
 *
 * This object does not import or initialize an ads SDK. Ads are denied unless a
 * valid signed config exists and the placement is allowed.
 */
internal enum class AdFormat {
    REWARDED,
    NATIVE,
    INTERSTITIAL
}

internal enum class BlockReason {
    CONFIG_MISSING,
    CONFIG_UNSIGNED,
    CONFIG_EXPIRED,
    CONFIG_NON_MONOTONIC,
    GLOBAL_KILL_SWITCH,
    VENUE_NOT_FOUND,
    DESTINATION_INTERRUPT,
    IME_SURFACE,
    PERMISSION_FLOW,
    FIRST_LAUNCH,
    CONSENT_UNAVAILABLE
}

internal data class AdVenue(
    val id: String,
    val screen: String,
    val trigger: String,
    val format: AdFormat,
    val requiresConsent: Boolean
)

internal data class SignedAvenueConfig(
    val version: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val globalKillSwitch: Boolean,
    val signature: String?,
    val venues: List<AdVenue>
)

internal data class AdServingRequest(
    val config: SignedAvenueConfig?,
    val lastAcceptedVersion: Long?,
    val nowEpochMs: Long,
    val venueId: String
)

internal data class AdDecision(
    val allow: Boolean,
    val reason: BlockReason?,
    val configVersion: Long?
)

internal object AdServingPolicy {
    private val blockedVenueIds = mapOf(
        "settings-entry" to BlockReason.DESTINATION_INTERRUPT,
        "ime-surface" to BlockReason.IME_SURFACE,
        "first-launch" to BlockReason.FIRST_LAUNCH,
        "permission-interstitial" to BlockReason.PERMISSION_FLOW
    )

    fun evaluate(request: AdServingRequest): AdDecision {
        val config = request.config
            ?: return AdDecision(false, BlockReason.CONFIG_MISSING, null)
        val validity = configValidity(
            config,
            request.lastAcceptedVersion,
            request.nowEpochMs
        )
        if (validity != null) {
            return AdDecision(false, validity, config.version)
        }
        if (config.globalKillSwitch) {
            return AdDecision(false, BlockReason.GLOBAL_KILL_SWITCH, config.version)
        }
        val venue = config.venues.find { it.id == request.venueId }
            ?: return AdDecision(false, BlockReason.VENUE_NOT_FOUND, config.version)
        val placement = placementBlock(venue)
        if (placement != null) {
            return AdDecision(false, placement, config.version)
        }
        return AdDecision(true, null, config.version)
    }

    fun canPublish(venue: AdVenue): Boolean = placementBlock(venue) == null

    private fun configValidity(
        config: SignedAvenueConfig,
        lastAcceptedVersion: Long?,
        nowEpochMs: Long
    ): BlockReason? {
        if (config.version <= 0L) return BlockReason.CONFIG_NON_MONOTONIC
        if (config.signature.isNullOrBlank()) return BlockReason.CONFIG_UNSIGNED
        if (config.expiresAtEpochMs <= nowEpochMs) return BlockReason.CONFIG_EXPIRED
        if (config.issuedAtEpochMs <= 0L) return BlockReason.CONFIG_EXPIRED
        if (config.version <= (lastAcceptedVersion ?: 0L)) {
            return BlockReason.CONFIG_NON_MONOTONIC
        }
        return null
    }

    private fun placementBlock(venue: AdVenue): BlockReason? {
        blockedVenueIds[venue.id]?.let { return it }
        val target = "${venue.screen} ${venue.trigger}".lowercase()
        if (
            venue.format == AdFormat.INTERSTITIAL &&
            SETTINGS_ENTRY.containsMatchIn(target)
        ) {
            return BlockReason.DESTINATION_INTERRUPT
        }
        if (IME_SURFACE.containsMatchIn(target)) return BlockReason.IME_SURFACE
        if (PERMISSION.containsMatchIn(target)) return BlockReason.PERMISSION_FLOW
        if (
            venue.format == AdFormat.INTERSTITIAL &&
            FIRST_LAUNCH.containsMatchIn(target)
        ) {
            return BlockReason.FIRST_LAUNCH
        }
        if (!venue.requiresConsent) return BlockReason.CONSENT_UNAVAILABLE
        return null
    }

    private val SETTINGS_ENTRY =
        Regex("설정.*열|설정.*진입|settings.*entry", RegexOption.IGNORE_CASE)
    private val IME_SURFACE =
        Regex("키보드 입력|ime|composition", RegexOption.IGNORE_CASE)
    private val PERMISSION =
        Regex("권한|permission", RegexOption.IGNORE_CASE)
    private val FIRST_LAUNCH =
        Regex("첫 실행|first launch", RegexOption.IGNORE_CASE)
}
