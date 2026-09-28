/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaLevelCurve
import org.fcitx.fcitx5.android.input.ai.VaultHabitState

/** Hero card: current level, progress toward the next one, and total sentences learned. */
internal class LevelHeroSection(private val activity: Activity) {
    private val levelBadge: TextView = activity.findViewById(R.id.tv_level_badge)
    private val levelDesc: TextView = activity.findViewById(R.id.tv_level_desc)
    private val levelProgress: LinearProgressIndicator = activity.findViewById(R.id.progress_level)
    private val levelProgressText: TextView = activity.findViewById(R.id.tv_level_progress_text)
    private val habitLine: TextView = activity.findViewById(R.id.tv_habit_line)
    private val pointsLine: TextView = activity.findViewById(R.id.tv_points_line)

    fun render(snapshot: DashboardSnapshot) {
        val stats = snapshot.typingStats
        levelBadge.text = activity.getString(R.string.vault_level_number, stats.level)
        levelBadge.contentDescription = activity.getString(R.string.vault_level_content_description, stats.level)
        if (stats.levelTitle.isNotBlank()) {
            levelDesc.text = stats.levelTitle
            levelDesc.visibility = View.VISIBLE
        } else {
            levelDesc.visibility = View.GONE
        }

        levelProgress.progress = stats.levelProgressPercent

        levelProgressText.text = if (stats.level >= TypingDnaLevelCurve.MAX_LEVEL) {
            activity.getString(R.string.vault_level_progress_max)
        } else {
            val remain = (stats.nextLevelTargetSentences - stats.totalSentences).coerceAtLeast(1)
            activity.getString(
                R.string.vault_level_progress_next,
                stats.level + 1,
                remain,
                stats.levelProgressPercent
            )
        }

        renderHeroLine(stats.totalSentences, snapshot.habit)
        if (snapshot.pointBalance > 0) {
            pointsLine.visibility = View.VISIBLE
            pointsLine.text = activity.getString(R.string.vault_home_points_chip, snapshot.pointBalance)
        } else {
            pointsLine.visibility = View.GONE
        }
    }

    /** Hero's one line: total sentences learned (the single "배운 문장" count shown anywhere on this screen), plus the streak when there is one. */
    private fun renderHeroLine(totalSentences: Int, habit: VaultHabitState) {
        habitLine.text = if (habit.streak > 0) {
            activity.getString(R.string.vault_home_learned_line_with_streak, totalSentences, habit.streak)
        } else {
            activity.getString(R.string.vault_home_learned_line, totalSentences)
        }
    }
}
