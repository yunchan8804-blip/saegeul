/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionDiagnosticsTest {

    @Test
    fun emittedIncrementsTotalAndPerCategoryCounters() {
        val diagnostics = CollectionDiagnostics(MemorySharedPreferences()) { 1_000L }

        diagnostics.emitted("work", 12)
        diagnostics.emitted("work", 8)
        diagnostics.emitted("messenger", 5)

        val today = diagnostics.today()
        assertEquals(3, today.emitted)
        assertEquals(2, today.emittedByCategory["work"])
        assertEquals(1, today.emittedByCategory["messenger"])
    }

    @Test
    fun droppedIncrementsPerReasonCounters() {
        val diagnostics = CollectionDiagnostics(MemorySharedPreferences()) { 1_000L }

        diagnostics.dropped("short")
        diagnostics.dropped("short")
        diagnostics.dropped("backspace")

        val today = diagnostics.today()
        assertEquals(2, today.droppedByReason["short"])
        assertEquals(1, today.droppedByReason["backspace"])
    }

    @Test
    fun compiledTracksSuccessAndFailureSeparately() {
        val diagnostics = CollectionDiagnostics(MemorySharedPreferences()) { 1_000L }

        diagnostics.compiled("work", ok = true)
        diagnostics.compiled("work", ok = false)
        diagnostics.compiled("messenger", ok = true)

        val today = diagnostics.today()
        assertEquals(2, today.compiled)
        assertEquals(1, today.compileFailed)
    }

    @Test
    fun countersResetWhenTheStoredDayIsNoLongerToday() {
        var now = 0L
        val prefs = MemorySharedPreferences()
        val diagnostics = CollectionDiagnostics(prefs) { now }

        diagnostics.emitted("work", 10)
        assertEquals(1, diagnostics.today().emitted)

        // advance by more than one day
        now += 2 * 86_400_000L
        val today = diagnostics.today()
        assertEquals(0, today.emitted)
        assertTrue(today.droppedByReason.isEmpty())

        // a fresh event after the reset starts counting from zero again
        diagnostics.dropped("blank")
        assertEquals(1, diagnostics.today().droppedByReason["blank"])
        assertEquals(0, diagnostics.today().emitted)
    }

    @Test
    fun recentRingBufferCapsAt200EventsAndNeverStoresRawText() {
        val diagnostics = CollectionDiagnostics(MemorySharedPreferences()) { 1_000L }

        repeat(250) { diagnostics.emitted("work", 42) }

        val recent = diagnostics.recent()
        assertEquals(200, recent.size)
        recent.forEach { event ->
            assertEquals("work", event.category)
            assertEquals(42, event.length)
            // the event carries only a category/length/reason tag, never the sentence itself
            assertFalse(event.toString().contains("문장"))
        }
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

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
                changed.forEach { key ->
                    listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemorySharedPreferences, key) }
                }
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                updates[requireNotNull(key)] = value
                return this
            }
        }
    }
}
