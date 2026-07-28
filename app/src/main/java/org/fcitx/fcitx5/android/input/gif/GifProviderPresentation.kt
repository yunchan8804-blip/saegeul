/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

/** A provider-neutral quick-search action rendered by the GIF surface. */
internal enum class GifQuickSuggestion {
    Trending,
    Meme,
    LeaveWork,
    Monday,
    Laugh,
    Awkward,
    Agree,
    Wow,
    Celebrate,
    Fighting,
    Love,
    Thanks,
    Angry,
    Sad
}

/**
 * Keeps the fallback surface honest about what its small open catalog can answer.
 *
 * Animated Noto Emoji is useful for reaction emoji, not a broad meme catalog. Wikimedia Commons
 * is searchable open media, but likewise is not a curated reaction/meme catalog. Their quick
 * actions therefore intentionally omit meme, commuting, and workday queries rather than
 * advertising a search experience that they cannot provide. The rich providers retain the full
 * chip set.
 */
internal data class GifProviderPresentation(
    val quickSuggestions: List<GifQuickSuggestion>,
    val showsMoreGifSettings: Boolean
)

internal object GifProviderPresentationPolicy {
    fun forProvider(kind: GifProviderKind): GifProviderPresentation = when (kind) {
        GifProviderKind.AnimatedNoto -> GifProviderPresentation(
            quickSuggestions = REACTION_SUGGESTIONS,
            showsMoreGifSettings = true
        )
        GifProviderKind.Commons -> GifProviderPresentation(
            quickSuggestions = REACTION_SUGGESTIONS,
            // Commons is an explicit keyless source; no setup CTA is needed.
            showsMoreGifSettings = false
        )
        GifProviderKind.Klipy,
        GifProviderKind.Giphy,
        GifProviderKind.GiphyUnavailable -> GifProviderPresentation(
            quickSuggestions = FULL_SUGGESTIONS,
            showsMoreGifSettings = false
        )
    }

    private val FULL_SUGGESTIONS = listOf(
        GifQuickSuggestion.Trending,
        GifQuickSuggestion.Meme,
        GifQuickSuggestion.LeaveWork,
        GifQuickSuggestion.Monday,
        GifQuickSuggestion.Laugh,
        GifQuickSuggestion.Awkward,
        GifQuickSuggestion.Agree,
        GifQuickSuggestion.Wow,
        GifQuickSuggestion.Celebrate,
        GifQuickSuggestion.Fighting,
        GifQuickSuggestion.Love,
        GifQuickSuggestion.Thanks,
        GifQuickSuggestion.Angry,
        GifQuickSuggestion.Sad
    )

    private val REACTION_SUGGESTIONS = listOf(
        GifQuickSuggestion.Trending,
        GifQuickSuggestion.Laugh,
        GifQuickSuggestion.Awkward,
        GifQuickSuggestion.Agree,
        GifQuickSuggestion.Wow,
        GifQuickSuggestion.Celebrate,
        GifQuickSuggestion.Fighting,
        GifQuickSuggestion.Love,
        GifQuickSuggestion.Thanks,
        GifQuickSuggestion.Angry,
        GifQuickSuggestion.Sad
    )
}
