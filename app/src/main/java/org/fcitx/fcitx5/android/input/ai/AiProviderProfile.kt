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

enum class AiModelTier {
    Fast,
    Balanced,
    Quality
}

data class AiProviderProfile(
    val kind: AiProviderKind = AiProviderKind.OpenAI,
    val displayName: String = "OpenAI",
    val baseUrl: String = OPENAI_BASE_URL,
    val apiKey: String = "",
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
        fastModel = fastModel.trim().ifEmpty { "gpt-5.6-luna" }.take(120),
        balancedModel = balancedModel.trim().ifEmpty { "gpt-5.6-terra" }.take(120),
        qualityModel = qualityModel.trim().ifEmpty { "gpt-5.6-sol" }.take(120)
    )

    fun validate(): AiProviderProfile {
        val profile = normalized()
        require(profile.apiKey.isNotEmpty()) { "API key is empty" }
        val uri = URI(profile.baseUrl)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Provider URL must not contain credentials, query, or fragment"
        }
        val loopback = uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "::1"
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "Provider URL must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Provider URL has no host" }
        return profile
    }

    fun model(tier: AiModelTier): String = when (tier) {
        AiModelTier.Fast -> fastModel
        AiModelTier.Balanced -> balancedModel
        AiModelTier.Quality -> qualityModel
    }

    val responsesEndpoint: String
        get() = "${normalized().baseUrl}/responses"

    val isConfigured: Boolean
        get() = runCatching { validate() }.isSuccess

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"

        internal fun normalizeBaseUrl(value: String): String = value.trim()
            .ifEmpty { OPENAI_BASE_URL }
            .trimEnd('/')
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
