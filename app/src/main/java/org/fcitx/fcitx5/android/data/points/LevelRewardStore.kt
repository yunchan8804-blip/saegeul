/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.points

import android.content.Context
import java.io.IOException

object LevelRewardMath {
    /** Levels gained since the last reward, or null when nothing is owed. */
    fun gainedLevels(lastRewardedLevel: Int, newLevel: Int): Int? {
        return if (newLevel > lastRewardedLevel) newLevel - lastRewardedLevel else null
    }

    fun pointsFor(levelsGained: Int): Int = levelsGained * PointPricing.POINTS_PER_LEVEL_UP
}

/**
 * Grants 10 points per level gained, once per level, and keeps a
 * "pending celebration" flag so the UI can congratulate on next open.
 * The first run after install only records the baseline (no retroactive
 * grants for levels earned before this feature existed).
 */
class LevelRewardStore(
    context: Context,
    private val ledger: PointLedger = PointLedger(context)
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("point_meta", Context.MODE_PRIVATE)

    /**
     * Pays [LevelRewardMath.pointsFor] for every level gained since the last rewarded one and
     * returns the points paid, or 0 when nothing was paid.
     *
     * Double payment is worse than a lost payment, so the new level is committed first and the
     * points are earned second: if the level cannot be saved nothing is paid, and if the ledger
     * refuses the points the level goes back to its previous value so a later call pays again.
     * A process death between those two steps loses that payment instead of repeating it.
     * Calls are serialized across all instances in the process.
     */
    fun grantIfLevelUp(newLevel: Int, nowEpochMs: Long): Int = synchronized(GRANT_LOCK) {
        val last = prefs.getInt(KEY_LAST_LEVEL, Int.MIN_VALUE)
        if (last == Int.MIN_VALUE) {
            prefs.edit().putInt(KEY_LAST_LEVEL, newLevel).apply()
            return 0
        }
        val gained = LevelRewardMath.gainedLevels(last, newLevel) ?: return 0
        val points = LevelRewardMath.pointsFor(gained)
        if (!prefs.edit().putInt(KEY_LAST_LEVEL, newLevel).commit()) return 0
        if (!ledger.earn(points, VENUE_LEVEL_UP, nowEpochMs, "levels:$gained")) {
            prefs.edit().putInt(KEY_LAST_LEVEL, last).commit()
            return 0
        }
        prefs.edit().putInt(KEY_PENDING_CELEBRATION, newLevel).apply()
        points
    }

    /**
     * Marks [level] as already rewarded without granting any points, raising the recorded
     * last-rewarded level when [level] is higher than what is already stored (never lowering it).
     * Used right after a vault backup import: restoring a higher Typing DNA level must not let the
     * next [grantIfLevelUp] pay out points for progress the user didn't just earn by typing.
     * Deliberately does not touch [KEY_PENDING_CELEBRATION], so a silent restore never triggers a
     * level-up celebration.
     *
     * Persists synchronously (`commit()`) because the import caller restarts the process right
     * after, which would drop an asynchronous `apply()`; throws [IOException] when the write fails.
     * Blocks on disk I/O, so call it off the main thread.
     */
    fun markAlreadyRewardedUpTo(level: Int): Unit = synchronized(GRANT_LOCK) {
        val current = prefs.getInt(KEY_LAST_LEVEL, Int.MIN_VALUE)
        if (current == Int.MIN_VALUE || level > current) {
            if (!prefs.edit().putInt(KEY_LAST_LEVEL, level).commit()) {
                throw IOException("Failed to persist the already-rewarded level: $level")
            }
        }
    }

    fun pendingCelebrationLevel(): Int = prefs.getInt(KEY_PENDING_CELEBRATION, 0)

    fun clearCelebration() {
        prefs.edit().putInt(KEY_PENDING_CELEBRATION, 0).apply()
    }

    companion object {
        const val VENUE_LEVEL_UP = "level-up"
        private val GRANT_LOCK = Any()
        private const val KEY_LAST_LEVEL = "last_rewarded_level"
        private const val KEY_PENDING_CELEBRATION = "pending_celebration_level"
    }
}
