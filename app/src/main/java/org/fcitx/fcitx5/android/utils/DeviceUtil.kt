/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.os.Build

object DeviceUtil {

    val isMIUI: Boolean by lazy {
        getSystemProperty("ro.miui.ui.version.name").isNotEmpty()
    }

    /**
     * https://www.cnblogs.com/qixingchao/p/15899405.html
     */
    val isHMOS: Boolean by lazy {
        getSystemProperty("hw_sc.build.platform.version").isNotEmpty()
    }

    /**
     * https://stackoverflow.com/questions/60122037/how-can-i-detect-samsung-one-ui
     */
    val isSamsungOneUI: Boolean by lazy {
        try {
            val semPlatformInt = Build.VERSION::class.java
                .getDeclaredField("SEM_PLATFORM_INT")
                .getInt(null)
            semPlatformInt > 90000
        } catch (e: Exception) {
            // 리플렉션 대상 필드가 없는 기기(삼성이 아니거나 One UI 이전)는 One UI가 아닌 것으로 본다.
            false
        }
    }

    val isVivoOriginOS: Boolean by lazy {
        getSystemProperty("ro.vivo.os.version").isNotEmpty()
    }

    val isHonorMagicOS: Boolean by lazy {
        getSystemProperty("ro.magic.systemversion").isNotEmpty()
    }

    val isFlyme: Boolean by lazy {
        Build.DISPLAY.lowercase().contains("flyme")
    }

}
