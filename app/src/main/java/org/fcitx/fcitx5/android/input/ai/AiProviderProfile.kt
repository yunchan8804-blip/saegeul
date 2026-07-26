/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import org.fcitx.fcitx5.android.BuildConfig
import java.net.URI

enum class AiProviderKind {
    OpenAI,
    OpenAICompatible
}

enum class AiAuthMode {
    ApiKey,
    OAuthPkce
}

enum class AiModelTier {
    Fast,
    Balanced,
    Quality
}

data class AiProviderProfile(
    val kind: AiProviderKind = AiProviderKind.OpenAI,
    val displayName: String = "OpenAI",
    val baseUrl: String = OPENAI_BASE_URL,
    val authMode: AiAuthMode = AiAuthMode.ApiKey,
    val apiKey: String = "",
    val oauthAuthorizationEndpoint: String = "",
    val oauthTokenEndpoint: String = "",
    val oauthRevocationEndpoint: String = "",
    val oauthClientId: String = "",
    val oauthScopes: String = DEFAULT_OAUTH_SCOPES,
    val capabilities: Set<String> = DEFAULT_CAPABILITIES,
    val fastModel: String = "gpt-5.6-luna",
    val balancedModel: String = "gpt-5.6-terra",
    val qualityModel: String = "gpt-5.6-sol"
) {
    fun normalized(): AiProviderProfile = copy(
        displayName = displayName.trim().ifEmpty {
            if (kind == AiProviderKind.OpenAI) "OpenAI" else "OpenAI compatible"
        }.take(80),
        baseUrl = normalizeBaseUrl(baseUrl),
        apiKey = apiKey.trim(),
        oauthAuthorizationEndpoint = normalizeEndpoint(oauthAuthorizationEndpoint),
        oauthTokenEndpoint = normalizeEndpoint(oauthTokenEndpoint),
        oauthRevocationEndpoint = normalizeEndpoint(oauthRevocationEndpoint),
        oauthClientId = oauthClientId.trim().take(240),
        oauthScopes = oauthScopes.trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .take(1_000),
        capabilities = capabilities.map(String::trim)
            .filter(CAPABILITY_PATTERN::matches)
            .toSet(),
        fastModel = fastModel.trim().ifEmpty { "gpt-5.6-luna" }.take(120),
        balancedModel = balancedModel.trim().ifEmpty { "gpt-5.6-terra" }.take(120),
        qualityModel = qualityModel.trim().ifEmpty { "gpt-5.6-sol" }.take(120)
    )

    fun validate(): AiProviderProfile {
        val profile = normalized()
        AiEndpointPolicy.requireHttps(profile.baseUrl, "Provider URL")
        when (profile.authMode) {
            AiAuthMode.ApiKey -> {
                require(profile.apiKey.isNotEmpty()) { "API key is empty" }
                require(profile.oauthAuthorizationEndpoint.isEmpty()) {
                    "API key and OAuth configuration cannot be mixed"
                }
                require(profile.oauthTokenEndpoint.isEmpty()) {
                    "API key and OAuth configuration cannot be mixed"
                }
                require(profile.oauthRevocationEndpoint.isEmpty()) {
                    "API key and OAuth configuration cannot be mixed"
                }
                require(profile.oauthClientId.isEmpty()) {
                    "API key and OAuth configuration cannot be mixed"
                }
            }
            AiAuthMode.OAuthPkce -> {
                require(profile.kind == AiProviderKind.OpenAICompatible) {
                    "OAuth is supported only for OpenAI-compatible providers"
                }
                require(profile.apiKey.isEmpty()) { "API key and OAuth cannot be mixed" }
                AiEndpointPolicy.requireHttps(
                    profile.oauthAuthorizationEndpoint,
                    "OAuth authorization endpoint"
                )
                AiEndpointPolicy.requireHttps(profile.oauthTokenEndpoint, "OAuth token endpoint")
                if (profile.oauthRevocationEndpoint.isNotEmpty()) {
                    AiEndpointPolicy.requireHttps(
                        profile.oauthRevocationEndpoint,
                        "OAuth revocation endpoint"
                    )
                }
                require(profile.oauthClientId.isNotEmpty()) { "OAuth client ID is empty" }
                require(profile.oauthScopes.isNotEmpty()) { "OAuth scopes are empty" }
            }
        }
        require("responses" in profile.capabilities) {
            "Provider does not declare Responses support"
        }
        return profile
    }

    fun model(tier: AiModelTier): String = when (tier) {
        AiModelTier.Fast -> fastModel
        AiModelTier.Balanced -> balancedModel
        AiModelTier.Quality -> qualityModel
    }

    val responsesEndpoint: String
        get() = "${normalized().baseUrl}/responses"

    val supportsTranscription: Boolean
        get() = "transcription" in normalized().capabilities

    val isConfigured: Boolean
        get() = runCatching { validate() }.isSuccess

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OAUTH_SCOPES = "openid offline_access"
        val DEFAULT_CAPABILITIES = setOf("responses", "transcription")
        private val CAPABILITY_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")
        val oauthRedirectUri: String
            get() = BuildConfig.AI_OAUTH_REDIRECT_URI

        internal fun normalizeBaseUrl(value: String): String = value.trim()
            .ifEmpty { OPENAI_BASE_URL }
            .trimEnd('/')

        private fun normalizeEndpoint(value: String): String = value.trim().trimEnd('/')
    }
}

object AiOAuthCallbackContract {
    fun matchesCurrentProfile(
        profile: AiProviderProfile,
        clientId: String,
        authorizationEndpoint: String,
        tokenEndpoint: String,
        redirectUri: String
    ): Boolean {
        val validated = runCatching(profile::validate).getOrNull() ?: return false
        return validated.authMode == AiAuthMode.OAuthPkce &&
            clientId == validated.oauthClientId &&
            authorizationEndpoint == validated.oauthAuthorizationEndpoint &&
            tokenEndpoint == validated.oauthTokenEndpoint &&
            redirectUri == AiProviderProfile.oauthRedirectUri
    }
}

/** HTTPS is mandatory even on RFC1918, loopback, and Tailscale/MagicDNS addresses. */
object AiEndpointPolicy {
    fun requireHttps(value: String, label: String) {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("$label is invalid") }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "$label must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "$label has no host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$label must not contain credentials, query, or fragment"
        }
    }
}

data class EffectiveAiProfile(
    val profile: AiProviderProfile?,
    val source: Source
) {
    enum class Source {
        Custom,
        BundledDebug,
        Missing
    }
}

object AiProviderResolver {
    fun resolve(context: Context): EffectiveAiProfile {
        AiProviderCredentialStore(context).load()?.takeIf(AiProviderProfile::isConfigured)?.let {
            return EffectiveAiProfile(it, EffectiveAiProfile.Source.Custom)
        }
        val bundled = AiProviderProfile(
            kind = if (BuildConfig.AI_PROVIDER_BASE_URL == AiProviderProfile.OPENAI_BASE_URL) {
                AiProviderKind.OpenAI
            } else {
                AiProviderKind.OpenAICompatible
            },
            displayName = BuildConfig.AI_PROVIDER_NAME,
            baseUrl = BuildConfig.AI_PROVIDER_BASE_URL,
            apiKey = BuildConfig.AI_PROVIDER_API_KEY,
            fastModel = BuildConfig.AI_FAST_MODEL,
            balancedModel = BuildConfig.AI_BALANCED_MODEL,
            qualityModel = BuildConfig.AI_QUALITY_MODEL
        ).takeIf(AiProviderProfile::isConfigured)
        return if (bundled != null) {
            EffectiveAiProfile(bundled, EffectiveAiProfile.Source.BundledDebug)
        } else {
            EffectiveAiProfile(null, EffectiveAiProfile.Source.Missing)
        }
    }
}
