/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context

enum class GifProviderKind {
    Klipy,
    AnimatedNoto
}

data class EffectiveGifProvider(
    val provider: GifProvider,
    val kind: GifProviderKind,
    val credentialState: GifProviderCredentialState,
    /** Partner approval is deliberately not represented as a local success flag. */
    val productionPartnerApprovalRequired: Boolean = true
)

object GifProviderResolver {
    fun resolve(context: Context): EffectiveGifProvider {
        val store = GifProviderCredentialStore(context)
        val key = store.loadKey()
        val state = when {
            key != null -> GifProviderCredentialState.Configured
            store.state() == GifProviderCredentialState.Unreadable -> {
                GifProviderCredentialState.Unreadable
            }
            else -> GifProviderCredentialState.Missing
        }
        return resolve(key, state)
    }

    internal fun resolve(
        apiKey: String?,
        state: GifProviderCredentialState = if (apiKey.isNullOrBlank()) {
            GifProviderCredentialState.Missing
        } else {
            GifProviderCredentialState.Configured
        }
    ): EffectiveGifProvider {
        val normalized = apiKey?.trim().orEmpty()
        return if (normalized.isEmpty()) {
            EffectiveGifProvider(
                provider = NotoAnimatedEmojiProvider(),
                kind = GifProviderKind.AnimatedNoto,
                credentialState = state
            )
        } else {
            EffectiveGifProvider(
                provider = KlipyGifProvider(normalized),
                kind = GifProviderKind.Klipy,
                credentialState = GifProviderCredentialState.Configured
            )
        }
    }
}
