/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import androidx.annotation.StringRes

/**
 * One integer setting: its stored key and default, plus how the settings UI edits it.
 * A range of 240 steps or more is edited as text; a smaller one gets a SeekBar.
 */
data class IntPreferenceSpec(
    @StringRes val title: Int,
    val key: String,
    val defaultValue: Int,
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val unit: String = "",
    val step: Int = 1,
    /** Shown instead of the value when the setting holds its default, e.g. "system default". */
    @StringRes val defaultLabel: Int? = null
)

/** One of the two stored values edited together by a twin SeekBar, e.g. portrait and landscape. */
data class TwinIntSide(
    @StringRes val label: Int,
    val key: String,
    val defaultValue: Int
)

/** Two integer settings sharing one title, range, unit, and step in a single twin SeekBar. */
data class TwinIntPreferenceSpec(
    @StringRes val title: Int,
    val primary: TwinIntSide,
    val secondary: TwinIntSide,
    val min: Int,
    val max: Int,
    val unit: String = "",
    val step: Int = 1,
    /** Shown instead of the value when a side holds its default, e.g. "system default". */
    @StringRes val defaultLabel: Int? = null
)
