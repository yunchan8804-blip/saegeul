/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * Records what the Typing DNA collection pipeline did and why, without ever storing the raw
 * text being typed: only category names, drop reasons, and lengths/counts. Backed by a daily
 * counter persisted to prefs (reset when the calendar day changes) and an in-memory ring
 * buffer of recent events for the dashboard.
 */
class CollectionDiagnostics internal constructor(
    private val prefs: SharedPreferences,
    private val clock: () -> Long
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        System::currentTimeMillis
    )

    private val lock = Any()
    private val ring = ArrayDeque<Event>()

    data class Event(
        val atMs: Long,
        val kind: String,
        val category: String? = null,
        val reason: String? = null,
        val length: Int? = null
    )

    data class DailyCounters(
        val day: Long,
        val emitted: Int,
        val droppedByReason: Map<String, Int>,
        val emittedByCategory: Map<String, Int>,
        val compiled: Int,
        val compileFailed: Int
    )

    fun emitted(category: String, length: Int) {
        Timber.i("SaegeulCollect emitted category=%s len=%d", category, length)
        recordEvent(Event(clock(), KIND_EMITTED, category = category, length = length))
        mutateCounters { counters ->
            counters.copy(
                emitted = counters.emitted + 1,
                emittedByCategory = counters.emittedByCategory +
                    (category to ((counters.emittedByCategory[category] ?: 0) + 1))
            )
        }
    }

    fun dropped(reason: String) {
        Timber.i("SaegeulCollect dropped reason=%s", reason)
        recordEvent(Event(clock(), KIND_DROPPED, reason = reason))
        mutateCounters { counters ->
            counters.copy(
                droppedByReason = counters.droppedByReason +
                    (reason to ((counters.droppedByReason[reason] ?: 0) + 1))
            )
        }
    }

    fun batchReady(category: String, size: Int) {
        Timber.i("SaegeulCollect batchReady category=%s size=%d", category, size)
        recordEvent(Event(clock(), KIND_BATCH_READY, category = category, length = size))
    }

    fun compiled(category: String, ok: Boolean) {
        Timber.i("SaegeulCollect compiled category=%s ok=%s", category, ok)
        recordEvent(Event(clock(), KIND_COMPILED, category = category))
        mutateCounters { counters ->
            if (ok) {
                counters.copy(compiled = counters.compiled + 1)
            } else {
                counters.copy(compileFailed = counters.compileFailed + 1)
            }
        }
    }

    fun today(): DailyCounters = synchronized(lock) { loadCounters() }

    fun recent(): List<Event> = synchronized(lock) { ring.toList() }

    private fun recordEvent(event: Event) {
        synchronized(lock) {
            ring.addLast(event)
            while (ring.size > MAX_RING_SIZE) {
                ring.removeFirst()
            }
        }
    }

    private fun mutateCounters(mutator: (DailyCounters) -> DailyCounters) {
        synchronized(lock) {
            saveCounters(mutator(loadCounters()))
        }
    }

    private fun loadCounters(): DailyCounters {
        val today = currentDay()
        val storedDay = prefs.getLong(KEY_DAY, today)
        if (storedDay != today) {
            return DailyCounters(
                day = today,
                emitted = 0,
                droppedByReason = emptyMap(),
                emittedByCategory = emptyMap(),
                compiled = 0,
                compileFailed = 0
            )
        }
        return DailyCounters(
            day = storedDay,
            emitted = prefs.getInt(KEY_EMITTED, 0),
            droppedByReason = decodeCounts(prefs.getString(KEY_DROPPED_BY_REASON, null)),
            emittedByCategory = decodeCounts(prefs.getString(KEY_EMITTED_BY_CATEGORY, null)),
            compiled = prefs.getInt(KEY_COMPILED, 0),
            compileFailed = prefs.getInt(KEY_COMPILE_FAILED, 0)
        )
    }

    private fun saveCounters(counters: DailyCounters) {
        prefs.edit()
            .putLong(KEY_DAY, counters.day)
            .putInt(KEY_EMITTED, counters.emitted)
            .putString(KEY_DROPPED_BY_REASON, encodeCounts(counters.droppedByReason))
            .putString(KEY_EMITTED_BY_CATEGORY, encodeCounts(counters.emittedByCategory))
            .putInt(KEY_COMPILED, counters.compiled)
            .putInt(KEY_COMPILE_FAILED, counters.compileFailed)
            .apply()
    }

    private fun currentDay(): Long = clock() / DAY_MS

    private fun encodeCounts(counts: Map<String, Int>): String {
        val root = org.json.JSONObject()
        counts.forEach { (key, value) -> root.put(key, value) }
        return root.toString()
    }

    private fun decodeCounts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = org.json.JSONObject(raw)
            val result = mutableMapOf<String, Int>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = root.optInt(key, 0)
            }
            result.toMap()
        }.getOrElse { emptyMap() }
    }

    companion object {
        private const val PREFS_NAME = "collection_stats"
        private const val DAY_MS = 86_400_000L
        private const val MAX_RING_SIZE = 200

        private const val KIND_EMITTED = "emitted"
        private const val KIND_DROPPED = "dropped"
        private const val KIND_BATCH_READY = "batchReady"
        private const val KIND_COMPILED = "compiled"

        private const val KEY_DAY = "day"
        private const val KEY_EMITTED = "emitted"
        private const val KEY_DROPPED_BY_REASON = "droppedByReason"
        private const val KEY_EMITTED_BY_CATEGORY = "emittedByCategory"
        private const val KEY_COMPILED = "compiled"
        private const val KEY_COMPILE_FAILED = "compileFailed"
    }
}
