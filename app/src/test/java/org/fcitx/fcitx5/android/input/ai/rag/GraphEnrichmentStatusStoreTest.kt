/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEnrichmentStatusStoreTest {

    @Test
    fun snapshotIsNeverBeforeAnyRecord() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)

        assertEquals(GraphEnrichmentStatus(), store.snapshot())
    }

    @Test
    fun legacyFailedStatusDefaultsToUnknownFailure() {
        val prefs = MemorySharedPreferences().apply { preset("phase", GraphEnrichmentPhase.FAILED.name) }
        val store = GraphEnrichmentStatusStore(prefs, Unit)

        assertEquals(GraphEnrichmentFailure.UNKNOWN, store.snapshot().failure)
    }

    @Test
    fun failedStatusFromTheRemovedExternalProviderLoadsAsNeverRun() {
        listOf("REAUTH_REQUIRED", "PROVIDER_BUSY", "TIMEOUT", "NETWORK", "PROVIDER_ERROR").forEach { legacyFailure ->
            val prefs = MemorySharedPreferences().apply {
                preset("phase", GraphEnrichmentPhase.FAILED.name)
                preset("failure", legacyFailure)
                preset("last_applied_ms", 20L)
                preset("nodes", 2)
            }
            val snapshot = GraphEnrichmentStatusStore(prefs, Unit).snapshot()

            assertEquals(legacyFailure, GraphEnrichmentPhase.NEVER, snapshot.phase)
            assertEquals(legacyFailure, GraphEnrichmentFailure.NONE, snapshot.failure)
            assertEquals(legacyFailure, 20L, snapshot.lastAppliedMs)
            assertEquals(legacyFailure, 2, snapshot.nodes)
        }
    }

    @Test
    fun unrecognizedStoredFailureStillLoadsAsUnknown() {
        val prefs = MemorySharedPreferences().apply {
            preset("phase", GraphEnrichmentPhase.FAILED.name)
            preset("failure", "NOT_A_KNOWN_FAILURE")
        }
        val snapshot = GraphEnrichmentStatusStore(prefs, Unit).snapshot()

        assertEquals(GraphEnrichmentPhase.FAILED, snapshot.phase)
        assertEquals(GraphEnrichmentFailure.UNKNOWN, snapshot.failure)
    }

    @Test
    fun recordIncrementalApplyUpdatesCountsWithoutLeavingRunning() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)

        store.recordIncrementalApply(15L, nodes = 5, edges = 3, topics = 2)

        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.RUNNING,
                startedMs = 10L,
                lastAppliedMs = 15L,
                nodes = 5,
                edges = 3,
                topics = 2,
                failure = GraphEnrichmentFailure.NONE
            ),
            store.snapshot()
        )

        store.recordIncrementalApply(25L, nodes = 9, edges = 6, topics = 3)

        assertEquals(GraphEnrichmentPhase.RUNNING, store.snapshot().phase)
        assertEquals(25L, store.snapshot().lastAppliedMs)
        assertEquals(9, store.snapshot().nodes)
    }

    @Test
    fun recordQueuedClearsAStaleFailureAndPreservesAppliedGraphCounts() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 2, 1, 1), 20L)
        store.recordStarted(30L)
        store.recordFailure(40L, failure = GraphEnrichmentFailure.DEVICE_BUSY)

        store.recordQueued()

        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.QUEUED,
                startedMs = 30L,
                finishedMs = 40L,
                lastAppliedMs = 20L,
                nodes = 2,
                edges = 1,
                topics = 1,
                failure = GraphEnrichmentFailure.NONE
            ),
            store.snapshot()
        )
    }

    @Test
    fun startedRunPreservesPreviouslyAppliedGraphCounts() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 2, 1, 1), 20L)

        store.recordStarted(30L)

        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.RUNNING,
                startedMs = 30L,
                lastAppliedMs = 20L,
                nodes = 2,
                edges = 1,
                topics = 1
            ),
            store.snapshot()
        )
    }

    @Test
    fun successfulAndPartialResultsUpdateAppliedGraphCounts() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)

        store.recordStarted(10L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 2, 1, 1), 20L)
        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.SUCCEEDED,
                startedMs = 10L,
                finishedMs = 20L,
                lastAppliedMs = 20L,
                nodes = 2,
                edges = 1,
                topics = 1
            ),
            store.snapshot()
        )

        store.recordStarted(30L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "partial", 4, 3, 2), 40L)
        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.PARTIAL,
                startedMs = 30L,
                finishedMs = 40L,
                lastAppliedMs = 40L,
                nodes = 4,
                edges = 3,
                topics = 2
            ),
            store.snapshot()
        )
    }

    @Test
    fun noDataAndFailuresKeepPreviouslyAppliedGraphCounts() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 2, 1, 1), 20L)

        store.recordStarted(30L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(false, "no_data", 0, 0, 0), 40L)
        assertEquals(
            GraphEnrichmentStatus(
                phase = GraphEnrichmentPhase.NO_DATA,
                startedMs = 30L,
                finishedMs = 40L,
                lastAppliedMs = 20L,
                nodes = 2,
                edges = 1,
                topics = 1
            ),
            store.snapshot()
        )

        store.recordStarted(50L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(false, "parse_failed", 0, 0, 0), 60L)
        assertEquals(GraphEnrichmentPhase.FAILED, store.snapshot().phase)
        assertEquals(GraphEnrichmentFailure.INVALID_RESPONSE, store.snapshot().failure)
        assertEquals(20L, store.snapshot().lastAppliedMs)
        assertEquals(2, store.snapshot().nodes)

        store.recordStarted(70L)
        store.recordFailure(80L, interrupted = true, failure = GraphEnrichmentFailure.STORAGE)
        assertEquals(GraphEnrichmentPhase.INTERRUPTED, store.snapshot().phase)
        assertEquals(GraphEnrichmentFailure.NONE, store.snapshot().failure)
        assertEquals(20L, store.snapshot().lastAppliedMs)
        assertEquals(2, store.snapshot().nodes)
    }

    @Test
    fun recordFailureStoresReasonAndPreservesPreviouslyAppliedGraph() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 2, 1, 1), 20L)

        store.recordStarted(30L)
        store.recordFailure(40L, failure = GraphEnrichmentFailure.STORAGE)

        assertEquals(GraphEnrichmentPhase.FAILED, store.snapshot().phase)
        assertEquals(GraphEnrichmentFailure.STORAGE, store.snapshot().failure)
        assertEquals(20L, store.snapshot().lastAppliedMs)
        assertEquals(2, store.snapshot().nodes)
        assertEquals(1, store.snapshot().edges)
        assertEquals(1, store.snapshot().topics)
    }

    @Test
    fun recordFailureStoresTheExceptionClassNameOnlyForAGenuineUnhandledFailure() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)

        store.recordFailure(20L, failure = GraphEnrichmentFailure.UNKNOWN, detail = "IllegalStateException")

        assertEquals("IllegalStateException", store.snapshot().failureDetail)

        // An interrupted pause never carries a detail, even if one were passed in.
        store.recordStarted(30L)
        store.recordFailure(40L, interrupted = true, detail = "ShouldNeverBeStored")
        assertEquals(null, store.snapshot().failureDetail)
    }

    @Test
    fun startingOrRecordingAResultClearsAPreviouslyStoredFailureDetail() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        store.recordStarted(10L)
        store.recordFailure(20L, failure = GraphEnrichmentFailure.UNKNOWN, detail = "IllegalStateException")
        assertEquals("IllegalStateException", store.snapshot().failureDetail)

        store.recordStarted(30L)
        assertEquals(null, store.snapshot().failureDetail)

        store.recordFailure(40L, failure = GraphEnrichmentFailure.UNKNOWN, detail = "IllegalStateException")
        store.recordResult(PersonalGraphEnricher.EnrichResult(true, "ok", 1, 1, 1), 50L)
        assertEquals(null, store.snapshot().failureDetail)
    }

    @Test
    fun listenerWrapperIsRemovedWithTheOriginalListener() {
        val store = GraphEnrichmentStatusStore(MemorySharedPreferences(), Unit)
        var calls = 0
        val listener: () -> Unit = { calls++ }

        store.addListener(listener)
        store.recordStarted(10L)
        assertTrue(calls > 0)

        store.removeListener(listener)
        val callsBeforeRemoval = calls
        store.recordFailure(20L)
        assertEquals(callsBeforeRemoval, calls)
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun preset(key: String, value: Any?) {
            values[key] = value
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                put(key, values?.toSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(requireNotNull(key))
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                val changed = linkedSetOf<String>()
                if (clearAll) {
                    changed.addAll(values.keys)
                    values.clear()
                }
                removals.forEach { key ->
                    if (values.remove(key) != null) changed.add(key)
                }
                updates.forEach { (key, value) ->
                    if (values[key] != value) {
                        values[key] = value
                        changed.add(key)
                    }
                }
                changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemorySharedPreferences, key) } }
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                updates[requireNotNull(key)] = value
                return this
            }
        }
    }
}
