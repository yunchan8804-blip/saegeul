/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.points.LevelRewardStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widget for the language vault: level, progress, totals.
 * Tapping it opens the vault dashboard.
 */
class VaultWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val snapshot = runCatching {
                DashboardSnapshotReader(context).read()
            }.getOrNull() ?: return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, VaultWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, renderViews(context, snapshot))
        }

        private fun renderViews(context: Context, snapshot: DashboardSnapshot): RemoteViews {
            val stats = snapshot.typingStats
            val habit = snapshot.habit
            val rewardStore = LevelRewardStore(context)
            val pendingLevel = rewardStore.pendingCelebrationLevel()
            val views = RemoteViews(context.packageName, R.layout.widget_vault)
            if (pendingLevel > 0) {
                views.setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    R.drawable.bg_widget_card_glow
                )
                views.setTextViewText(
                    R.id.widget_level,
                    "🎉 Lv.$pendingLevel 달성! 탭해서 확인"
                )
            } else {
                views.setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    R.drawable.bg_widget_card
                )
                views.setTextViewText(
                    R.id.widget_level,
                    "Lv.${stats.level} · ${stats.levelTitle}"
                )
            }
            val sentences = StringBuilder("분석 문장 ${stats.totalSentences}개")
            if (habit.streak > 0) sentences.append(" · 🔥 ").append(habit.streak).append("일")
            views.setTextViewText(
                R.id.widget_sentences,
                sentences.toString()
            )
            views.setTextViewText(
                R.id.widget_last_sync,
                "마지막 분석 ${formatTime(snapshot.lastSyncMs)}"
            )
            views.setInt(R.id.widget_progress, "setMax", 100)
            views.setInt(R.id.widget_progress, "setProgress", stats.levelProgressPercent)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, TypingDnaDashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        private fun formatTime(epochMs: Long): String {
            if (epochMs <= 0L) return "분석 전"
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }
}
