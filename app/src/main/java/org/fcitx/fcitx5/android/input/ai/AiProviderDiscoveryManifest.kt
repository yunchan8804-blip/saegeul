/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** A provider profile obtained from a trusted HTTPS discovery manifest. */
data class VerifiedAiProviderManifest(
    val providerId: String,
    val manifestUrl: String,
    val profile: AiProviderProfile,
    val capabilities: Set<String>
)

/**
 * Versioned wire contract used by the desktop companion and the Android setup wizard.
 *
 * Discovery over mDNS is only a hint. This codec accepts configuration solely from an HTTPS
 * manifest, rejects credential material, and pins the advertised redirect URI to this build.
 */
object AiProviderDiscoveryManifestCodec {
    const val PROTOCOL_VERSION = 1
    const val WELL_KNOWN_PATH = "/.well-known/fcitx-ai-provider"
    const val MAX_MANIFEST_BYTES = 128 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val providerIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val capabilityPattern = Regex("[a-z][a-z0-9._-]{0,63}")
    private val forbiddenKeys = setOf(
        "api_key",
        "client_secret",
        "access_token",
        "refresh_token",
        "authorization"
    )

    fun decode(payload: String, manifestUrl: String): VerifiedAiProviderManifest {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "Discovery manifest is too large"
        }
        val normalizedManifestUrl = manifestUrl.trim()
        AiEndpointPolicy.requireHttps(normalizedManifestUrl, "Discovery manifest URL")
        val manifestUri = URI(normalizedManifestUrl)
        require(manifestUri.path == WELL_KNOWN_PATH) {
            "Discovery manifest must use $WELL_KNOWN_PATH"
        }

        val tree = json.parseToJsonElement(payload)
        rejectCredentialMaterial(tree)
        val wire = json.decodeFromJsonElement(AiProviderDiscoveryManifest.serializer(), tree)
        require(wire.protocolVersion == PROTOCOL_VERSION) {
            "Unsupported discovery protocol version"
        }
        require(providerIdPattern.matches(wire.providerId)) { "Invalid provider ID" }
        require(wire.displayName.trim().isNotEmpty()) { "Provider name is empty" }
        require(wire.oauth.redirectUri == AiProviderProfile.oauthRedirectUri) {
            "This computer has not registered the app redirect URI"
        }
        val capabilities = wire.capabilities.map(String::trim).filter(String::isNotEmpty).toSet()
        require(capabilities.isNotEmpty() && capabilities.all(capabilityPattern::matches)) {
            "Invalid provider capabilities"
        }
        require("responses" in capabilities) { "Provider does not advertise Responses support" }
        require(
            wire.models.fast.isNotBlank() &&
                wire.models.balanced.isNotBlank() &&
                wire.models.quality.isNotBlank()
        ) { "Provider model mapping is incomplete" }

        val profile = AiProviderProfile(
            kind = AiProviderKind.OpenAICompatible,
            displayName = wire.displayName,
            baseUrl = wire.baseUrl,
            authMode = AiAuthMode.OAuthPkce,
            apiKey = "",
            oauthAuthorizationEndpoint = wire.oauth.authorizationEndpoint,
            oauthTokenEndpoint = wire.oauth.tokenEndpoint,
            oauthRevocationEndpoint = wire.oauth.revocationEndpoint,
            oauthClientId = wire.oauth.clientId,
            oauthScopes = wire.oauth.scopes.joinToString(" "),
            fastModel = wire.models.fast,
            balancedModel = wire.models.balanced,
            qualityModel = wire.models.quality
        ).validate()

        return VerifiedAiProviderManifest(
            providerId = wire.providerId,
            manifestUrl = normalizedManifestUrl,
            profile = profile,
            capabilities = capabilities
        )
    }

    private fun rejectCredentialMaterial(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                require(key.lowercase() !in forbiddenKeys) {
                    "Discovery manifests cannot contain credentials"
                }
                rejectCredentialMaterial(value)
            }
            else -> element.children().forEach(::rejectCredentialMaterial)
        }
    }

    private fun JsonElement.children(): Collection<JsonElement> = when (this) {
        is kotlinx.serialization.json.JsonArray -> this
        else -> emptyList()
    }
}

@Serializable
private data class AiProviderDiscoveryManifest(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("provider_id") val providerId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String,
    val oauth: OAuth,
    val models: Models,
    val capabilities: List<String> = listOf("responses")
) {
    @Serializable
    data class OAuth(
        @SerialName("authorization_endpoint") val authorizationEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
        @SerialName("revocation_endpoint") val revocationEndpoint: String = "",
        @SerialName("client_id") val clientId: String,
        val scopes: List<String>,
        @SerialName("redirect_uri") val redirectUri: String
    )

    @Serializable
    data class Models(
        val fast: String,
        val balanced: String,
        val quality: String
    )
}
