/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import androidx.core.content.edit

/**
 * The small amount of install-time preference [GemmaModelInstaller] needs to survive a process
 * restart alongside the `.part` file itself and `WorkManager`'s own persisted `WorkInfo`: whether the
 * user allowed mobile data, and the last response `ETag` (sent back as `If-Range` on resume, so a
 * server that rotated the underlying blob out from under a stale `.part` is detected instead of
 * silently appended-to).
 */
internal class GemmaModelInstallStore private constructor(private val prefs: android.content.SharedPreferences) {

    var allowMobileData: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_MOBILE_DATA, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_MOBILE_DATA, value) }

    var lastEtag: String?
        get() = prefs.getString(KEY_LAST_ETAG, null)
        set(value) = prefs.edit { putString(KEY_LAST_ETAG, value) }

    fun clear() = prefs.edit { remove(KEY_LAST_ETAG) }

    companion object {
        const val PREFERENCES = "gemma_model_install_state"
        private const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
        private const val KEY_LAST_ETAG = "last_etag"

        fun get(context: Context): GemmaModelInstallStore =
            GemmaModelInstallStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
    }
}
