/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.memory

import java.time.Instant
import java.time.ZoneId

/**
 * Persona tone categorized according to the current app/package context.
 */
enum class PersonaTone {
    FORMAL_BUSINESS,
    CASUAL_CHAT,
    CONCISE_SEARCH
}

/**
 * Coarse time-of-day bins for contextual adaptation.
 */
enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT
}

/**
 * Encoded TPO (Time, Place, Occasion / Target app) context data.
 */
data class TpoContext(
    val tone: PersonaTone,
    val timeOfDay: TimeOfDay,
    val packageName: String
)

/**
 * TPO Context Encoder (EAI-12).
 * Encodes active package name and timestamp into high-level persona tones and temporal contexts.
 */
class TpoContextEncoder(
    private val defaultZoneId: ZoneId = ZoneId.systemDefault()
) {

    private val formalBusinessPackages = setOf(
        "com.slack",
        "com.google.android.gm",
        "com.microsoft.teams"
    )

    private val casualChatPackages = setOf(
        "com.kakao.talk",
        "com.instagram.android",
        "com.facebook.orca"
    )

    /**
     * Resolves the persona tone associated with the given application package.
     */
    fun resolveTone(packageName: String): PersonaTone {
        val trimmed = packageName.trim()
        return when {
            formalBusinessPackages.any { trimmed == it || trimmed.startsWith("$it.") } -> PersonaTone.FORMAL_BUSINESS
            casualChatPackages.any { trimmed == it || trimmed.startsWith("$it.") } -> PersonaTone.CASUAL_CHAT
            else -> PersonaTone.CONCISE_SEARCH
        }
    }

    /**
     * Resolves the coarse time-of-day bucket based on epoch timestamp in milliseconds.
     * - 06:00 ~ 11:59: MORNING
     * - 12:00 ~ 17:59: AFTERNOON
     * - 18:00 ~ 22:59: EVENING
     * - 23:00 ~ 05:59: NIGHT
     */
    fun resolveTimeOfDay(
        epochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = defaultZoneId
    ): TimeOfDay {
        val hour = Instant.ofEpochMilli(epochMs).atZone(zoneId).hour
        return when (hour) {
            in 6 until 12 -> TimeOfDay.MORNING
            in 12 until 18 -> TimeOfDay.AFTERNOON
            in 18 until 23 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }

    /**
     * Encodes package name and epoch timestamp into a [TpoContext].
     */
    fun encode(
        packageName: String,
        epochMs: Long = System.currentTimeMillis()
    ): TpoContext {
        val tone = resolveTone(packageName)
        val timeOfDay = resolveTimeOfDay(epochMs)
        return TpoContext(
            tone = tone,
            timeOfDay = timeOfDay,
            packageName = packageName
        )
    }

    companion object {
        private val defaultEncoder = TpoContextEncoder()

        /**
         * Convenience static-style encode using the default system timezone.
         */
        fun encode(
            packageName: String,
            epochMs: Long = System.currentTimeMillis()
        ): TpoContext = defaultEncoder.encode(packageName, epochMs)
    }
}
