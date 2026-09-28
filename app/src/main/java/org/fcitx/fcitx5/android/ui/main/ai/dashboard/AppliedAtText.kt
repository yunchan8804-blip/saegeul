/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.content.Context
import androidx.annotation.StringRes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** String set for one "when was it applied" line: today with time, yesterday with time, or month and day. */
internal data class AppliedAtStrings(
    @StringRes val today: Int,
    @StringRes val yesterday: Int,
    @StringRes val date: Int
)

/** "오늘 HH:mm" / "어제 HH:mm" / "M월 d일" (or the English equivalent) in [strings]' wording. */
internal fun Context.formatAppliedAt(ms: Long, strings: AppliedAtStrings): String {
    val nowMs = System.currentTimeMillis()
    val target = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(target.time)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
    return when {
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) && dayDiff == 0 ->
            getString(strings.today, time)
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) && dayDiff == 1 ->
            getString(strings.yesterday, time)
        else -> getString(
            strings.date,
            target.get(Calendar.MONTH) + 1,
            target.get(Calendar.DAY_OF_MONTH)
        )
    }
}
