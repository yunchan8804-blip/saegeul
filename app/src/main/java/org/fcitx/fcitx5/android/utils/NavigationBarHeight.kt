/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import splitties.dimensions.dp

private const val FALLBACK_NAVBAR_HEIGHT = 48

/**
 * android.R.dimen.navigation_bar_frame_height
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r48/services/core/java/com/android/server/wm/DisplayPolicy.java#3221
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r48/services/core/java/com/android/server/wm/DisplayPolicy.java#3059
 */
fun Context.navbarFrameHeight(): Int {
    @SuppressLint("DiscouragedApi")
    val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
    return try {
        resources.getDimensionPixelSize(resId)
    } catch (e: Resources.NotFoundException) {
        // 일부 OEM/버전에는 이 내부 dimen 리소스가 없다. 고정 대체값으로 넘어간다.
        dp(FALLBACK_NAVBAR_HEIGHT)
    }
}
