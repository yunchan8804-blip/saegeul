/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.Context
import android.content.SharedPreferences

/** Receives the generation lease holder each time it changes. [purpose] is null when nobody holds the lease. */
fun interface GemmaBreadcrumbSink {
    fun onHolderChanged(purpose: String?, sinceMs: Long)
}

/**
 * Remembers which generation purpose held the lease, so the next start can tell what the process
 * was doing when a native thread killed it. Writes are `apply()` and stay off the disk path of
 * the caller.
 */
class GemmaNativeBreadcrumbStore private constructor(
    private val prefs: SharedPreferences,
    private val pid: () -> Int
) : GemmaBreadcrumbSink {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        android.os.Process::myPid
    )

    internal constructor(prefs: SharedPreferences, pid: () -> Int, @Suppress("UNUSED_PARAMETER") forTesting: Unit) :
        this(prefs, pid)

    @Synchronized
    override fun onHolderChanged(purpose: String?, sinceMs: Long) {
        val editor = prefs.edit()
            .putString(KEY_PURPOSE, purpose.orEmpty())
            .putInt(KEY_PID, pid())
        if (purpose == null) {
            editor.putLong(KEY_SINCE_MS, 0L).putLong(KEY_RELEASED_MS, sinceMs)
        } else {
            editor.putLong(KEY_SINCE_MS, sinceMs).putString(KEY_LAST_PURPOSE, purpose).putLong(KEY_RELEASED_MS, 0L)
        }
        editor.apply()
    }

    @Synchronized
    fun read(): NativeCrashAttribution.Breadcrumb = NativeCrashAttribution.Breadcrumb(
        purpose = prefs.getString(KEY_PURPOSE, null).orEmpty(),
        sinceMs = prefs.getLong(KEY_SINCE_MS, 0L),
        pid = prefs.getInt(KEY_PID, 0),
        lastPurpose = prefs.getString(KEY_LAST_PURPOSE, null).orEmpty(),
        releasedMs = prefs.getLong(KEY_RELEASED_MS, 0L)
    )

    /** Drops a breadcrumb left by an earlier process; the running process's own entry is kept. */
    @Synchronized
    fun clearOtherProcess() {
        if (prefs.getInt(KEY_PID, 0) != pid()) {
            prefs.edit().clear().apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "gemma_native_breadcrumb"
        const val KEY_PURPOSE = "purpose"
        const val KEY_SINCE_MS = "since_ms"
        const val KEY_PID = "pid"
        const val KEY_LAST_PURPOSE = "last_purpose"
        const val KEY_RELEASED_MS = "released_ms"
    }
}
