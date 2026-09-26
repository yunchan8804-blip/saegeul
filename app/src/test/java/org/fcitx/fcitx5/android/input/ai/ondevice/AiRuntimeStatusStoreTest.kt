/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiRuntimeStatusStoreTest {

    @Test
    fun `snapshot is empty before any record`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)

        val snapshot = store.snapshot()

        assertNull(snapshot.lastWarmupResult)
        assertNull(snapshot.backend)
        assertNull(snapshot.lastFailureCode)
        assertEquals(0, snapshot.recoveryCount)
    }

    @Test
    fun `recordWarmup stores result duration and backend`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)

        store.recordWarmup(result = "OK", durationMs = 250L, backend = "gpu", nowMs = 1_000L)

        val snapshot = store.snapshot()
        assertEquals("OK", snapshot.lastWarmupResult)
        assertEquals(250L, snapshot.lastWarmupDurationMs)
        assertEquals("gpu", snapshot.backend)
        assertEquals(1_000L, snapshot.lastWarmupAtMs)
    }

    @Test
    fun `recordGeneration stores latency and timestamp`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)

        store.recordGeneration(latencyMs = 400L, nowMs = 2_000L)

        val snapshot = store.snapshot()
        assertEquals(400L, snapshot.lastGenerationLatencyMs)
        assertEquals(2_000L, snapshot.lastGenerationAtMs)
    }

    @Test
    fun `recordRecovery increments the recovery count and stores the source code`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)

        store.recordRecovery("NATIVE_STOP_TIMEOUT", 10L)
        store.recordRecovery("NATIVE_CLOSE_FAILED", 20L)

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.recoveryCount)
        assertEquals(20L, snapshot.lastRecoveryAtMs)
        assertEquals("NATIVE_CLOSE_FAILED", snapshot.lastFailureCode)
    }

    @Test
    fun `recordFailure stores the failure code and timestamp`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)

        store.recordFailure("ENGINE_UNRECOVERABLE", 30L)

        val snapshot = store.snapshot()
        assertEquals("ENGINE_UNRECOVERABLE", snapshot.lastFailureCode)
        assertEquals(30L, snapshot.lastFailureAtMs)
    }

    @Test
    fun `clear resets everything to defaults`() {
        val store = AiRuntimeStatusStore(MemorySharedPreferences(), Unit)
        store.recordWarmup(result = "OK", durationMs = 250L, backend = "gpu", nowMs = 1_000L)
        store.recordFailure("BUSY", 5L)

        store.clear()

        val snapshot = store.snapshot()
        assertEquals(AiRuntimeStatusStore.Snapshot(), snapshot)
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

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

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

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
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                updates.forEach { (key, value) -> values[key] = value }
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                updates[requireNotNull(key)] = value
                return this
            }
        }
    }
}
