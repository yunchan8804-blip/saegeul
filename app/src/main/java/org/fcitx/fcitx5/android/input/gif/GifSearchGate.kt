/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

sealed interface GifSearchOutcome {
    data object Blocked : GifSearchOutcome
    data class Results(val items: List<GifResult>) : GifSearchOutcome
}

/** Keeps the privacy decision on the call boundary so a blocked editor cannot reach a provider. */
class GifSearchGate(private val provider: GifProvider) {
    suspend fun search(allowed: Boolean, query: String): GifSearchOutcome =
        if (allowed) {
            GifSearchOutcome.Results(provider.search(query))
        } else {
            GifSearchOutcome.Blocked
        }
}
