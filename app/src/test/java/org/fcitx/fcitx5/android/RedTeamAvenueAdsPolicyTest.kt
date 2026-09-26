/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.ads.AdVenue
import org.fcitx.fcitx5.android.ads.AvenueFrequencyPolicy
import org.fcitx.fcitx5.android.ads.AvenueFrequencyState
import org.fcitx.fcitx5.android.ads.AvenueFrequencyStore
import org.fcitx.fcitx5.android.ads.BlockReason
import org.fcitx.fcitx5.android.ads.LocalAvenueCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red Team Adversarial Unit Tests: Avenue Ads Policy & Frequency Bypass Exploit Vectors.
 *
 * Probes:
 * 1. Time-travel (Clock Rollback) frequency cap & cooldown bypass attacks.
 * 2. AvenueFrequencyStore.recordExposure monotonicity violation on past timestamp injection.
 * 3. Minimum actions unmet boundary enforcement invariants.
 */
class RedTeamAvenueAdsPolicyTest {

    private companion object {
        const val DAY_MS = 86_400_000L
        const val TEST_VENUE_ID = "redteam-test-venue"
    }

    /**
     * Attack Vector 1: Time Travel (Clock Rollback) Frequency Cap & Cooldown Bypass Attack.
     *
     * Scenario: A user/attacker rolls back device system clock (nowEpochMs < state.lastShownEpochMs
     * or today < state.dayIndex) in an attempt to:
     * - Force dailyCap counters to reset to 0.
     * - Erase active cooldown period so that interstitials/rewarded ads can be repeatedly served.
     *
     * Invariant:
     * - When clock is in the past relative to lastShownEpochMs, AvenueFrequencyPolicy.evaluate
     *   MUST strictly block ad serving and return BlockReason.COOLDOWN_ACTIVE (or a non-null BlockReason).
     * - In no circumstance may an ad be served (returning null) when time has moved backwards.
     */
    @Test
    fun probeTimeTravelRollbackBlocksAdAndPreservesCooldown() {
        val venue = AdVenue(
            id = LocalAvenueCatalog.TYPING_DNA_SYNC_COMPLETE,
            screen = "AI 언어 지문 대시보드",
            trigger = "지금 즉시 분석 및 동기화 완료 뒤",
            format = org.fcitx.fcitx5.android.ads.AdFormat.INTERSTITIAL,
            requiresConsent = true,
            dailyCap = 2,
            cooldownMinutes = 120L,
            minActions = 3
        )
        val now = 1_777_000_000_000L
        val today = now / DAY_MS

        val legitimateShownState = AvenueFrequencyState(
            dayIndex = today,
            shownToday = 1,
            lastShownEpochMs = now,
            actionsTotal = 100
        )

        // Normal check: ad was just shown, shownToday (1) < dailyCap (2), so at now + 1 min it is blocked by cooldown
        assertEquals(
            "Ad served 1 min ago must be blocked by cooldown",
            BlockReason.COOLDOWN_ACTIVE,
            AvenueFrequencyPolicy.evaluate(venue, legitimateShownState, now + 60_000L)
        )

        // Sub-vector 1A: Clock rolled back by 1 hour (same dayIndex)
        val rollback1Hour = now - 3_600_000L
        val reason1Hour = AvenueFrequencyPolicy.evaluate(venue, legitimateShownState, rollback1Hour)
        assertEquals(
            "Time-travel rollback by 1 hour must return BlockReason.COOLDOWN_ACTIVE",
            BlockReason.COOLDOWN_ACTIVE,
            reason1Hour
        )

        // Sub-vector 1B: Clock rolled back by 1 day (today < state.dayIndex)
        val rollback1Day = now - DAY_MS
        val reason1Day = AvenueFrequencyPolicy.evaluate(venue, legitimateShownState, rollback1Day)
        assertEquals(
            "Time-travel rollback by 1 day must block with COOLDOWN_ACTIVE",
            BlockReason.COOLDOWN_ACTIVE,
            reason1Day
        )

        // Sub-vector 1C: Clock rolled back by 1 year
        val rollback1Year = now - 365L * DAY_MS
        val reason1Year = AvenueFrequencyPolicy.evaluate(venue, legitimateShownState, rollback1Year)
        assertEquals(
            "Time-travel rollback by 1 year must block with COOLDOWN_ACTIVE",
            BlockReason.COOLDOWN_ACTIVE,
            reason1Year
        )

        // Sub-vector 1D: Rewarded ad venue with dailyCap = 3, cooldownMinutes = 20
        val rewardVenue = LocalAvenueCatalog.themePointEarn // dailyCap = 3, cooldownMinutes = 20
        val rewardCappedState = AvenueFrequencyState(
            dayIndex = today,
            shownToday = 3,
            lastShownEpochMs = now,
            actionsTotal = 50
        )
        // Normal check: daily cap exhausted
        assertEquals(
            "Exhausted daily cap must return FREQUENCY_CAPPED at current time",
            BlockReason.FREQUENCY_CAPPED,
            AvenueFrequencyPolicy.evaluate(rewardVenue, rewardCappedState, now)
        )
        // Attack: User rolls back clock to yesterday hoping shownToday resets to 0
        val rewardRollback = now - DAY_MS
        val reasonRewardRollback = AvenueFrequencyPolicy.evaluate(rewardVenue, rewardCappedState, rewardRollback)
        assertEquals(
            "Time-travel rollback to yesterday to reset daily cap must be blocked by COOLDOWN_ACTIVE",
            BlockReason.COOLDOWN_ACTIVE,
            reasonRewardRollback
        )

        // Sub-vector 1E: Zero cooldown minutes venue with clock rollback
        val zeroCooldownVenue = AdVenue(
            id = "zero-cooldown",
            screen = "test",
            trigger = "test",
            format = org.fcitx.fcitx5.android.ads.AdFormat.INTERSTITIAL,
            requiresConsent = false,
            dailyCap = 5,
            cooldownMinutes = 0L,
            minActions = 0
        )
        val zeroCooldownState = AvenueFrequencyState(
            dayIndex = today,
            shownToday = 1,
            lastShownEpochMs = now,
            actionsTotal = 10
        )
        val reasonZeroCooldown = AvenueFrequencyPolicy.evaluate(zeroCooldownVenue, zeroCooldownState, now - 5_000L)
        assertEquals(
            "Venue with 0 cooldown minutes must still block clock rollback (negative delta < 0)",
            BlockReason.COOLDOWN_ACTIVE,
            reasonZeroCooldown
        )
    }

    /**
     * Attack Vector 2: AvenueFrequencyStore.recordExposure Monotonicity Attack.
     *
     * Scenario: An adversary or out-of-order event injector passes a past nowEpochMs to
     * AvenueFrequencyStore.recordExposure.
     *
     * Invariant:
     * - Stored dayIndex and lastShownEpochMs must be strictly monotonic (non-decreasing).
     * - A past timestamp must NEVER overwrite newer stored timestamps with older values.
     */
    @Test
    fun probeRecordExposurePreservesMonotonicityAgainstPastTimestampInjection() {
        val memoryPrefs = MemorySharedPreferences()
        val store = createAvenueFrequencyStore(memoryPrefs)

        val tLegitimate = 1_777_000_000_000L
        val dayLegitimate = tLegitimate / DAY_MS

        // Step 1: Legitimate exposure recorded at tLegitimate
        store.recordExposure(TEST_VENUE_ID, tLegitimate)
        val initial = store.state(TEST_VENUE_ID)
        assertEquals("Initial dayIndex must match legitimate day", dayLegitimate, initial.dayIndex)
        assertEquals("Initial lastShown must match legitimate epoch", tLegitimate, initial.lastShownEpochMs)
        assertEquals("Initial shown count must be 1", 1, initial.shownToday)

        // Step 2: Adversary injects past exposure (10 days in the past)
        val tPast10Days = tLegitimate - 10L * DAY_MS
        store.recordExposure(TEST_VENUE_ID, tPast10Days)
        val afterPastInjection = store.state(TEST_VENUE_ID)

        assertTrue(
            "Monotonicity Invariant: Stored dayIndex must not rewind from $dayLegitimate to ${afterPastInjection.dayIndex}",
            afterPastInjection.dayIndex >= initial.dayIndex
        )
        assertTrue(
            "Monotonicity Invariant: Stored lastShownEpochMs must not rewind from $tLegitimate to ${afterPastInjection.lastShownEpochMs}",
            afterPastInjection.lastShownEpochMs >= initial.lastShownEpochMs
        )

        // Step 3: Same day past timestamp injection (1 hour before legitimate)
        val tSameDayPast = tLegitimate - 3_600_000L
        store.recordExposure(TEST_VENUE_ID, tSameDayPast)
        val afterSameDayPast = store.state(TEST_VENUE_ID)

        assertTrue(
            "Monotonicity Invariant: Stored lastShownEpochMs must not rewind on same-day past injection",
            afterSameDayPast.lastShownEpochMs >= initial.lastShownEpochMs
        )
    }

    /**
     * Attack Vector 3: Minimum Actions Unmet Boundary Invariant.
     *
     * Invariant:
     * - When actionsTotal < venue.minActions, AvenueFrequencyPolicy.evaluate must return
     *   BlockReason.MIN_ACTIONS_NOT_MET.
     * - Negative actionsTotal (underflow injection) must also be blocked by MIN_ACTIONS_NOT_MET.
     * - Only when actionsTotal >= minActions (and no cap/cooldown is violated) may it return null.
     */
    @Test
    fun probeMinActionsUnmetBlocksAdUntilRequiredActionsMet() {
        val venue = LocalAvenueCatalog.typingDnaSyncComplete // minActions = 3, dailyCap = 1
        val now = 1_777_000_000_000L
        val today = now / DAY_MS

        // Sub-vector 3A: Boundary testing [0, minActions - 1, minActions, minActions + 1]
        val actionsUnderflow = AvenueFrequencyState(dayIndex = today, actionsTotal = -5)
        assertEquals(
            "Negative actionsTotal must be blocked by MIN_ACTIONS_NOT_MET",
            BlockReason.MIN_ACTIONS_NOT_MET,
            AvenueFrequencyPolicy.evaluate(venue, actionsUnderflow, now)
        )

        val actionsZero = AvenueFrequencyState(dayIndex = today, actionsTotal = 0)
        assertEquals(
            "0 actionsTotal must be blocked by MIN_ACTIONS_NOT_MET",
            BlockReason.MIN_ACTIONS_NOT_MET,
            AvenueFrequencyPolicy.evaluate(venue, actionsZero, now)
        )

        val actionsTwo = AvenueFrequencyState(dayIndex = today, actionsTotal = venue.minActions - 1)
        assertEquals(
            "actionsTotal = minActions - 1 must be blocked by MIN_ACTIONS_NOT_MET",
            BlockReason.MIN_ACTIONS_NOT_MET,
            AvenueFrequencyPolicy.evaluate(venue, actionsTwo, now)
        )

        val actionsMet = AvenueFrequencyState(dayIndex = today, actionsTotal = venue.minActions)
        assertNull(
            "actionsTotal = minActions must allow ad when no other block reasons exist",
            AvenueFrequencyPolicy.evaluate(venue, actionsMet, now)
        )

        val actionsExceeded = AvenueFrequencyState(dayIndex = today, actionsTotal = venue.minActions + 10)
        assertNull(
            "actionsTotal > minActions must allow ad",
            AvenueFrequencyPolicy.evaluate(venue, actionsExceeded, now)
        )

        // Sub-vector 3B: AvenueFrequencyStore.recordAction integration progression
        val memoryPrefs = MemorySharedPreferences()
        val store = createAvenueFrequencyStore(memoryPrefs)

        assertEquals("New store starts with 0 actions", 0, store.state(TEST_VENUE_ID).actionsTotal)
        assertEquals(
            "New store state must be blocked by MIN_ACTIONS_NOT_MET",
            BlockReason.MIN_ACTIONS_NOT_MET,
            AvenueFrequencyPolicy.evaluate(venue, store.state(TEST_VENUE_ID), now)
        )

        // Increment actions step-by-step
        repeat(venue.minActions - 1) {
            store.recordAction(TEST_VENUE_ID)
            assertEquals(
                "Must still be blocked before reaching minActions",
                BlockReason.MIN_ACTIONS_NOT_MET,
                AvenueFrequencyPolicy.evaluate(venue, store.state(TEST_VENUE_ID), now)
            )
        }

        // Final action reaching minActions
        store.recordAction(TEST_VENUE_ID)
        assertEquals(venue.minActions, store.state(TEST_VENUE_ID).actionsTotal)
        assertNull(
            "Must be unblocked once minActions is achieved via recordAction",
            AvenueFrequencyPolicy.evaluate(venue, store.state(TEST_VENUE_ID), now)
        )
    }

    // --- Test Helpers ---

    private fun createAvenueFrequencyStore(prefs: SharedPreferences): AvenueFrequencyStore {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val store = unsafe.allocateInstance(AvenueFrequencyStore::class.java) as AvenueFrequencyStore

        val prefsField = AvenueFrequencyStore::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(store, prefs)
        return store
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
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
