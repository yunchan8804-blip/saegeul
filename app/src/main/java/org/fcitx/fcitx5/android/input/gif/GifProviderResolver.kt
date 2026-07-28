/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context

enum class GifProviderKind {
    Klipy,
    AnimatedNoto,
    Commons,
    Giphy,
    GiphyUnavailable
}

data class EffectiveGifProvider(
    val provider: GifProvider,
    val kind: GifProviderKind,
    val credentialState: GifProviderCredentialState,
    val selection: GifProviderSelection = GifProviderSelection.Standard,
    val giphyCredentialState: GiphyCredentialState = GiphyCredentialState.Missing,
    val networkReady: Boolean = true,
    val giphyMediaCachingApproved: Boolean = false,
    /** True until the selected provider's externally granted production approval is confirmed. */
    val productionPartnerApprovalRequired: Boolean = true
)

object GifProviderResolver {
    fun resolve(context: Context): EffectiveGifProvider {
        val klipyStore = GifProviderCredentialStore(context)
        val key = klipyStore.loadKey()
        val state = when {
            key != null -> GifProviderCredentialState.Configured
            klipyStore.state() == GifProviderCredentialState.Unreadable -> {
                GifProviderCredentialState.Unreadable
            }
            else -> GifProviderCredentialState.Missing
        }
        val selection = GifProviderSelectionStore(context).load()
        val giphyStore = GiphyProviderCredentialStore(context)
        val giphyConfiguration = giphyStore.load()
        val giphyState = when {
            giphyConfiguration?.productionApproved == true -> GiphyCredentialState.Ready
            giphyConfiguration != null -> GiphyCredentialState.KeyOnly
            else -> giphyStore.state()
        }
        return resolve(
            selection = selection,
            klipyApiKey = key,
            klipyState = state,
            giphyConfiguration = giphyConfiguration,
            giphyState = giphyState
        )
    }

    internal fun resolve(
        apiKey: String?,
        state: GifProviderCredentialState = if (apiKey.isNullOrBlank()) {
            GifProviderCredentialState.Missing
        } else {
            GifProviderCredentialState.Configured
        }
    ): EffectiveGifProvider = resolveStandard(apiKey, state)

    internal fun resolve(
        selection: GifProviderSelection,
        klipyApiKey: String?,
        klipyState: GifProviderCredentialState,
        giphyConfiguration: GiphyProviderConfiguration?,
        giphyState: GiphyCredentialState
    ): EffectiveGifProvider {
        if (selection == GifProviderSelection.Commons) {
            return EffectiveGifProvider(
                provider = WikimediaCommonsGifProvider(),
                kind = GifProviderKind.Commons,
                credentialState = klipyState,
                selection = selection,
                giphyCredentialState = giphyState,
                giphyMediaCachingApproved = giphyConfiguration?.mediaCachingApproved == true,
                productionPartnerApprovalRequired = false
            )
        }
        if (selection == GifProviderSelection.Giphy) {
            val configuration = giphyConfiguration
            val ready = giphyState == GiphyCredentialState.Ready &&
                configuration?.productionApproved == true &&
                configuration.apiKey.isNotBlank()
            return if (ready) {
                checkNotNull(configuration)
                EffectiveGifProvider(
                    provider = GiphyGifProvider(
                        apiKey = configuration.apiKey,
                        mediaCachingApproved = configuration.mediaCachingApproved
                    ),
                    kind = GifProviderKind.Giphy,
                    credentialState = klipyState,
                    selection = selection,
                    giphyCredentialState = giphyState,
                    giphyMediaCachingApproved = configuration.mediaCachingApproved,
                    productionPartnerApprovalRequired = false
                )
            } else {
                EffectiveGifProvider(
                    provider = UnavailableGiphyProvider,
                    kind = GifProviderKind.GiphyUnavailable,
                    credentialState = klipyState,
                    selection = selection,
                    giphyCredentialState = giphyState,
                    networkReady = false,
                    productionPartnerApprovalRequired = true
                )
            }
        }
        return resolveStandard(
            apiKey = klipyApiKey,
            state = klipyState,
            giphyState = giphyState,
            giphyMediaCachingApproved = giphyConfiguration?.mediaCachingApproved == true
        )
    }

    private fun resolveStandard(
        apiKey: String?,
        state: GifProviderCredentialState,
        giphyState: GiphyCredentialState = GiphyCredentialState.Missing,
        giphyMediaCachingApproved: Boolean = false
    ): EffectiveGifProvider {
        val normalized = apiKey?.trim().orEmpty()
        return if (normalized.isEmpty()) {
            EffectiveGifProvider(
                provider = NotoAnimatedEmojiProvider(),
                kind = GifProviderKind.AnimatedNoto,
                credentialState = state,
                selection = GifProviderSelection.Standard,
                giphyCredentialState = giphyState,
                giphyMediaCachingApproved = giphyMediaCachingApproved
            )
        } else {
            EffectiveGifProvider(
                provider = KlipyGifProvider(normalized),
                kind = GifProviderKind.Klipy,
                credentialState = GifProviderCredentialState.Configured,
                selection = GifProviderSelection.Standard,
                giphyCredentialState = giphyState,
                giphyMediaCachingApproved = giphyMediaCachingApproved
            )
        }
    }
}

private data object UnavailableGiphyProvider : GifProvider {
    override val displayName: String = GiphyGifProvider.POWERED_BY_GIPHY

    /** Resolver/UI guards this object; this fallback still guarantees zero network calls. */
    override suspend fun search(query: String, limit: Int): List<GifResult> = emptyList()
}
