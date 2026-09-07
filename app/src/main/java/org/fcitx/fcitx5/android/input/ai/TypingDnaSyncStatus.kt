/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Persists the timestamp of the last "지금 즉시 분석 및 동기화" run. Timestamps only, not sensitive. */
class TypingDnaSyncStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("typing_dna_sync_status", Context.MODE_PRIVATE)

    fun lastSyncMs(): Long = prefs.getLong("last_sync_ms", 0L)

    fun recordSync(nowMs: Long) {
        prefs.edit().putLong("last_sync_ms", nowMs).apply()
    }
}

enum class TypingDnaSyncLevel { NEVER, SYNC_ONLY, ENRICHED }

object TypingDnaSyncStatus {

    /** 마지막 실행 수준 판정. lastEnrichMs는 PersonalGraphStore.stats().builtMs(0=없음). */
    fun level(lastSyncMs: Long, lastEnrichMs: Long): TypingDnaSyncLevel = when {
        lastSyncMs <= 0L && lastEnrichMs <= 0L -> TypingDnaSyncLevel.NEVER
        lastEnrichMs > 0L && lastEnrichMs >= lastSyncMs -> TypingDnaSyncLevel.ENRICHED
        else -> TypingDnaSyncLevel.SYNC_ONLY
    }

    /** pill/상태줄에 쓸 시각 문자열. 같은 날이면 "HH:mm", 아니면 "M/d HH:mm". */
    fun formatTime(ms: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val target = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val sameDay = target.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "M/d HH:mm"
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(target.time)
    }
}
