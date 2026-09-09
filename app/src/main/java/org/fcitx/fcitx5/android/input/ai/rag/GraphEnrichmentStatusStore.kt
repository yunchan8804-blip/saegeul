/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.content.Context
import android.content.SharedPreferences

enum class GraphEnrichmentPhase {
    NEVER,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    NO_DATA,
    FAILED,
    INTERRUPTED
}

enum class GraphEnrichmentFailure {
    NONE,
    INVALID_RESPONSE,
    REAUTH_REQUIRED,
    PROVIDER_BUSY,
    TIMEOUT,
    NETWORK,
    PROVIDER_ERROR,
    STORAGE,
    UNKNOWN
}

data class GraphEnrichmentStatus(
    val phase: GraphEnrichmentPhase = GraphEnrichmentPhase.NEVER,
    val startedMs: Long = 0L,
    val finishedMs: Long = 0L,
    val lastAppliedMs: Long = 0L,
    val nodes: Int = 0,
    val edges: Int = 0,
    val topics: Int = 0,
    val failure: GraphEnrichmentFailure = GraphEnrichmentFailure.NONE
)

class GraphEnrichmentStatusStore private constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    internal constructor(prefs: SharedPreferences, @Suppress("UNUSED_PARAMETER") forTesting: Unit) : this(prefs)

    private val listenerWrappers = mutableMapOf<() -> Unit, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun snapshot(): GraphEnrichmentStatus {
        val phase = prefs.getString(KEY_PHASE, null)
            ?.let { stored -> GraphEnrichmentPhase.entries.firstOrNull { it.name == stored } }
            ?: GraphEnrichmentPhase.NEVER
        val failure = prefs.getString(KEY_FAILURE, null)
            ?.let { stored -> GraphEnrichmentFailure.entries.firstOrNull { it.name == stored } }
            ?: if (phase == GraphEnrichmentPhase.FAILED) GraphEnrichmentFailure.UNKNOWN else GraphEnrichmentFailure.NONE
        return GraphEnrichmentStatus(
            phase = phase,
            startedMs = prefs.getLong(KEY_STARTED_MS, 0L),
            finishedMs = prefs.getLong(KEY_FINISHED_MS, 0L),
            lastAppliedMs = prefs.getLong(KEY_LAST_APPLIED_MS, 0L),
            nodes = prefs.getInt(KEY_NODES, 0),
            edges = prefs.getInt(KEY_EDGES, 0),
            topics = prefs.getInt(KEY_TOPICS, 0),
            failure = failure
        )
    }

    fun recordStarted(nowMs: Long) {
        val previous = snapshot()
        write(
            phase = GraphEnrichmentPhase.RUNNING,
            startedMs = nowMs,
            finishedMs = 0L,
            lastAppliedMs = previous.lastAppliedMs,
            nodes = previous.nodes,
            edges = previous.edges,
            topics = previous.topics,
            failure = GraphEnrichmentFailure.NONE
        )
    }

    fun recordResult(result: PersonalGraphEnricher.EnrichResult, nowMs: Long) {
        val previous = snapshot()
        val phase = when {
            result.ok && result.reason == "ok" -> GraphEnrichmentPhase.SUCCEEDED
            result.ok && result.reason == "partial" -> GraphEnrichmentPhase.PARTIAL
            !result.ok && result.reason == "no_data" -> GraphEnrichmentPhase.NO_DATA
            else -> GraphEnrichmentPhase.FAILED
        }
        val applied = phase == GraphEnrichmentPhase.SUCCEEDED || phase == GraphEnrichmentPhase.PARTIAL
        write(
            phase = phase,
            startedMs = previous.startedMs,
            finishedMs = nowMs,
            lastAppliedMs = if (applied) nowMs else previous.lastAppliedMs,
            nodes = if (applied) result.nodes else previous.nodes,
            edges = if (applied) result.edges else previous.edges,
            topics = if (applied) result.topics else previous.topics,
            failure = when {
                phase != GraphEnrichmentPhase.FAILED -> GraphEnrichmentFailure.NONE
                result.reason == "parse_failed" -> GraphEnrichmentFailure.INVALID_RESPONSE
                else -> GraphEnrichmentFailure.UNKNOWN
            }
        )
    }

    fun recordFailure(
        nowMs: Long,
        interrupted: Boolean = false,
        failure: GraphEnrichmentFailure = GraphEnrichmentFailure.UNKNOWN
    ) {
        val previous = snapshot()
        write(
            phase = if (interrupted) GraphEnrichmentPhase.INTERRUPTED else GraphEnrichmentPhase.FAILED,
            startedMs = previous.startedMs,
            finishedMs = nowMs,
            lastAppliedMs = previous.lastAppliedMs,
            nodes = previous.nodes,
            edges = previous.edges,
            topics = previous.topics,
            failure = if (interrupted) GraphEnrichmentFailure.NONE else failure
        )
    }

    fun addListener(listener: () -> Unit) {
        if (listenerWrappers.containsKey(listener)) return
        val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        listenerWrappers[listener] = wrapper
        prefs.registerOnSharedPreferenceChangeListener(wrapper)
    }

    fun removeListener(listener: () -> Unit) {
        val wrapper = listenerWrappers.remove(listener) ?: return
        prefs.unregisterOnSharedPreferenceChangeListener(wrapper)
    }

    private fun write(
        phase: GraphEnrichmentPhase,
        startedMs: Long,
        finishedMs: Long,
        lastAppliedMs: Long,
        nodes: Int,
        edges: Int,
        topics: Int,
        failure: GraphEnrichmentFailure
    ) {
        prefs.edit()
            .putString(KEY_PHASE, phase.name)
            .putLong(KEY_STARTED_MS, startedMs)
            .putLong(KEY_FINISHED_MS, finishedMs)
            .putLong(KEY_LAST_APPLIED_MS, lastAppliedMs)
            .putInt(KEY_NODES, nodes)
            .putInt(KEY_EDGES, edges)
            .putInt(KEY_TOPICS, topics)
            .putString(KEY_FAILURE, failure.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "graph_enrichment_status"
        const val KEY_PHASE = "phase"
        const val KEY_STARTED_MS = "started_ms"
        const val KEY_FINISHED_MS = "finished_ms"
        const val KEY_LAST_APPLIED_MS = "last_applied_ms"
        const val KEY_NODES = "nodes"
        const val KEY_EDGES = "edges"
        const val KEY_TOPICS = "topics"
        const val KEY_FAILURE = "failure"
    }
}
