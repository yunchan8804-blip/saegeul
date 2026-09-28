/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.widget.SeekBar

fun SeekBar.setOnChangeListener(listener: SeekBar.(progress: Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        // 의도적으로 비움: 이 헬퍼는 진행값 변경만 넘긴다.
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        // 의도적으로 비움: 이 헬퍼는 진행값 변경만 넘긴다.
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            listener.invoke(seekBar, progress)
        }
    })
}
