/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.points

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LevelRewardStoreAtomicityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_777_000_000_000L

    private fun unsafe(): sun.misc.Unsafe {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as sun.misc.Unsafe
    }

    private fun ledgerAt(target: File): PointLedger {
        val ledger = unsafe().allocateInstance(PointLedger::class.java) as PointLedger
        PointLedger::class.java.getDeclaredField("file").apply { isAccessible = true }.set(ledger, target)
        PointLedger::class.java.getDeclaredField("json").apply { isAccessible = true }
            .set(ledger, kotlinx.serialization.json.Json { encodeDefaults = true })
        return ledger
    }

    private fun redirectLedger(ledger: PointLedger, target: File) {
        PointLedger::class.java.getDeclaredField("file").apply { isAccessible = true }.set(ledger, target)
    }

    private fun storeWith(prefs: SharedPreferences, ledger: PointLedger): LevelRewardStore {
        val store = unsafe().allocateInstance(LevelRewardStore::class.java) as LevelRewardStore
        LevelRewardStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(store, prefs)
        LevelRewardStore::class.java.getDeclaredField("ledger").apply { isAccessible = true }.set(store, ledger)
        return store
    }

    /** A path whose parent is a regular file, so the ledger can neither create it nor append to it. */
    private fun unwritableLedgerFile(): File {
        val blocker = tempFolder.newFile("not-a-directory")
        return File(blocker, "ledger.jsonl")
    }

    @Test
    fun failedEarnLeavesTheLevelUnchangedAndTheNextCallPays() {
        val ledgerFile = File(tempFolder.root, "ledger.jsonl")
        val ledger = ledgerAt(unwritableLedgerFile())
        val prefs = MemoryPreferences()
        val store = storeWith(prefs, ledger)
        store.grantIfLevelUp(1, now)

        assertEquals(0, store.grantIfLevelUp(3, now))
        assertEquals(1, prefs.getInt("last_rewarded_level", -1))
        assertEquals(0, prefs.getInt("pending_celebration_level", 0))

        redirectLedger(ledger, ledgerFile)
        val paid = store.grantIfLevelUp(3, now)

        assertEquals(LevelRewardMath.pointsFor(2), paid)
        assertEquals(paid, ledger.balance())
        assertEquals(3, prefs.getInt("last_rewarded_level", -1))
        assertEquals(3, prefs.getInt("pending_celebration_level", 0))
    }

    @Test
    fun unsavedLevelPaysNothing() {
        val ledger = ledgerAt(File(tempFolder.root, "ledger.jsonl"))
        val prefs = MemoryPreferences()
        val store = storeWith(prefs, ledger)
        store.grantIfLevelUp(1, now)
        prefs.failCommits = true

        assertEquals(0, store.grantIfLevelUp(4, now))

        assertEquals(0, ledger.balance())
        assertEquals(1, prefs.getInt("last_rewarded_level", -1))
    }

    @Test
    fun aLevelUpIsPaidOnce() {
        val ledger = ledgerAt(File(tempFolder.root, "ledger.jsonl"))
        val store = storeWith(MemoryPreferences(), ledger)
        store.grantIfLevelUp(1, now)

        val first = store.grantIfLevelUp(2, now)
        val second = store.grantIfLevelUp(2, now)

        assertEquals(LevelRewardMath.pointsFor(1), first)
        assertEquals(0, second)
        assertEquals(first, ledger.balance())
    }

    @Test
    fun concurrentCallsPayOnce() {
        val ledger = ledgerAt(File(tempFolder.root, "ledger.jsonl"))
        val prefs = MemoryPreferences()
        val store = storeWith(prefs, ledger)
        store.grantIfLevelUp(1, now)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Int> {
                    ready.countDown()
                    go.await()
                    store.grantIfLevelUp(2, now)
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown()
            val paid = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(LevelRewardMath.pointsFor(1), paid.sum())
            assertEquals(LevelRewardMath.pointsFor(1), ledger.balance())
        } finally {
            pool.shutdownNow()
        }
    }

    /** In-memory [SharedPreferences] whose commits can be made to fail. */
    private class MemoryPreferences : SharedPreferences {
        private val data = HashMap<String, Any?>()

        @Volatile
        var failCommits = false

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int) = synchronized(data) { data[key] as? Int ?: defValue }
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()

            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun remove(key: String?) = apply { pending.remove(key) }
            override fun clear() = apply { }
            override fun commit(): Boolean {
                if (failCommits) return false
                write()
                return true
            }
            override fun apply() = write()

            private fun write() = synchronized(data) { data.putAll(pending) }
        }
    }
}
