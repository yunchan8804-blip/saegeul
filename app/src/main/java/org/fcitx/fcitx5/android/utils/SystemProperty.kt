/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.annotation.SuppressLint

@SuppressLint("PrivateApi")
fun getSystemProperty(key: String): String {
    return try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as String
    } catch (e: Exception) {
        // 숨은 API 접근이 막힌 기기/버전에서는 빈 값으로 취급해 OEM 감지 로직이 false로 떨어지게 한다.
        ""
    }
}
