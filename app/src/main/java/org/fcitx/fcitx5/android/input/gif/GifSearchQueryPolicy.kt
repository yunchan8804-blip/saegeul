/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

/** One query boundary shared by the canonical prompt and every GIF provider. */
object GifSearchQueryPolicy {
    const val DEFAULT_MAX_CHARACTERS = 80
    const val GIPHY_MAX_CHARACTERS = 50

    fun maxCharacters(provider: GifProviderKind): Int = when (provider) {
        GifProviderKind.Giphy,
        GifProviderKind.GiphyUnavailable -> GIPHY_MAX_CHARACTERS
        GifProviderKind.Klipy,
        GifProviderKind.AnimatedNoto,
        GifProviderKind.Commons -> DEFAULT_MAX_CHARACTERS
    }

    fun normalize(query: String, provider: GifProviderKind): String =
        query.trim().take(maxCharacters(provider))
}
