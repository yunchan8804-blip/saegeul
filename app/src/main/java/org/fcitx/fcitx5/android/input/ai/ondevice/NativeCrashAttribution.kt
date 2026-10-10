/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Decides which generation purpose was running when a previous process died of a native crash,
 * by matching the process exit history against the lease breadcrumb the dead process left.
 */
object NativeCrashAttribution {
    const val PURPOSE_NONE = "NONE"

    const val RELEASE_ATTRIBUTION_WINDOW_MS = 10_000L
    const val AFTER_RELEASE_SUFFIX = "_AFTER_RELEASE"

    private const val EXIT_HISTORY_SIZE = 10

    data class ExitRecord(val pid: Int, val timestampMs: Long, val reason: Int)

    /**
     * [purpose] is empty while no lease is held. [lastPurpose] is the last holder and [releasedMs]
     * when it let go (0 while held), so a crash right after a release can still be traced to it.
     */
    data class Breadcrumb(
        val purpose: String,
        val sinceMs: Long,
        val pid: Int,
        val lastPurpose: String = "",
        val releasedMs: Long = 0L
    )

    data class Crash(val timestampMs: Long, val purpose: String)

    data class Result(val crashes: List<Crash>, val checkedMs: Long)

    fun evaluate(exits: List<ExitRecord>, breadcrumb: Breadcrumb, lastCheckedMs: Long): Result {
        val crashes = exits
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestampMs > lastCheckedMs }
            .sortedBy { it.timestampMs }
            .map { exit ->
                Crash(exit.timestampMs, attribute(exit, breadcrumb))
            }
        return Result(crashes, crashes.maxOfOrNull { it.timestampMs } ?: lastCheckedMs)
    }

    private fun attribute(exit: ExitRecord, breadcrumb: Breadcrumb): String {
        if (breadcrumb.pid != exit.pid) return PURPOSE_NONE
        if (breadcrumb.purpose.isNotEmpty()) return breadcrumb.purpose
        val sinceRelease = exit.timestampMs - breadcrumb.releasedMs
        if (breadcrumb.lastPurpose.isNotEmpty() && sinceRelease in 0..RELEASE_ATTRIBUTION_WINDOW_MS) {
            return breadcrumb.lastPurpose + AFTER_RELEASE_SUFFIX
        }
        return PURPOSE_NONE
    }

    /**
     * Reads the main process's exit history once, records any native crash since the last check,
     * and clears the breadcrumb of the dead process. [previousBreadcrumb] must be read before this
     * process starts writing its own. Does nothing below API 30, where the exit history does not
     * exist. Blocking: call it off the main thread.
     */
    fun checkOnStart(
        context: Context,
        statusStore: AiRuntimeStatusStore,
        breadcrumbStore: GemmaNativeBreadcrumbStore,
        previousBreadcrumb: Breadcrumb
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mainProcessName = context.packageName
        val exitInfos = try {
            activityManager.getHistoricalProcessExitReasons(mainProcessName, 0, EXIT_HISTORY_SIZE)
        } catch (error: RuntimeException) {
            Timber.w(error, "Gemma native crash check could not read process exit reasons")
            return
        }
        val exits = exitInfos
            .filter { it.processName == mainProcessName }
            .map { ExitRecord(it.pid, it.timestamp, it.reason) }
        val result = evaluate(
            exits = exits,
            breadcrumb = previousBreadcrumb,
            lastCheckedMs = statusStore.snapshot().nativeCrashCheckedMs
        )
        if (result.crashes.isNotEmpty()) {
            statusStore.recordNativeCrashes(result.crashes, result.checkedMs)
            result.crashes.forEach { crash ->
                Timber.i("Gemma native crash detected at=%d purpose=%s", crash.timestampMs, crash.purpose)
            }
        }
        breadcrumbStore.clearOtherProcess()
    }
}
