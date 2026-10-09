/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

/**
 * Fail-closed offline check shared by every ad placement.
 * When the offline switch cannot be read, the app is treated as offline so no ad request is sent.
 */
internal object AdOfflineGate {
    fun isOffline(): Boolean = isOffline { AppPrefs.getInstance().advanced.offlineMode.getValue() }

    internal fun isOffline(read: () -> Boolean): Boolean =
        try {
            read()
        } catch (e: Exception) {
            Timber.w(e, "Offline mode unreadable; treating as offline for ads")
            true
        }
}
