/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import kotlinx.coroutines.CancellationException

/** A guide dialog on screen; [onClosed] runs its action once the user closes it. */
internal fun interface ShownGuide {
    fun onClosed(action: () -> Unit)
}

/**
 * Orders the interstitial after a manual sync: the finished sync is counted toward the frequency
 * gate at once, while the ad itself waits for the enrichment guide to close so it never covers
 * the guide. Without a guide the ad follows the sync directly, as it always did.
 */
internal class PostSyncAdSequence(
    private val recordSync: () -> Unit,
    private val showAd: () -> Unit
) {
    /**
     * [showGuide] returns the guide it put on screen, or null when there is none. When it fails the
     * ad still shows right away; when the screen is going away (cancellation) nothing is shown.
     */
    suspend fun run(showGuide: suspend () -> ShownGuide?) {
        recordSync()
        val guide = try {
            showGuide()
        } catch (error: Throwable) {
            if (error !is CancellationException) showAd()
            throw error
        }
        if (guide == null) showAd() else guide.onClosed(showAd)
    }
}
