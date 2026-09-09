/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

object AiOAuthSessionIdentity {
    fun canPreserveSession(previous: AiProviderProfile?, next: AiProviderProfile): Boolean {
        if (previous?.authMode != AiAuthMode.OAuthPkce || next.authMode != AiAuthMode.OAuthPkce) {
            return false
        }
        if (scopeSet(previous) != scopeSet(next)) return false
        val previousMaterial = identityMaterial(previous)
        val nextMaterial = identityMaterial(next)
        return try {
            previousMaterial.contentEquals(nextMaterial)
        } finally {
            previousMaterial.fill(0)
            nextMaterial.fill(0)
        }
    }

    fun identityMaterial(profile: AiProviderProfile): ByteArray = listOf(
        profile.baseUrl,
        profile.oauthAuthorizationEndpoint,
        profile.oauthTokenEndpoint,
        profile.oauthClientId,
        AiProviderProfile.oauthRedirectUri
    ).joinToString("\u0000").toByteArray(Charsets.UTF_8)

    private fun scopeSet(profile: AiProviderProfile): Set<String> = profile.oauthScopes
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .toSet()
}
