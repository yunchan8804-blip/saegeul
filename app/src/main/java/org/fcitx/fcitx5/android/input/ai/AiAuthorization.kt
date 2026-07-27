/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import net.openid.appauth.NoClientAuthentication
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AiBearerTokenProvider {
    suspend fun authorizationHeader(profile: AiProviderProfile): String

    fun onUnauthorized(profile: AiProviderProfile): Exception =
        if (profile.authMode == AiAuthMode.OAuthPkce) {
            AiReauthenticationRequiredException()
        } else {
            AiApiKeyRejectedException()
        }
}

/** Default used by isolated clients and tests. Android runtime injects the OAuth-capable provider. */
object ProfileAiBearerTokenProvider : AiBearerTokenProvider {
    override suspend fun authorizationHeader(profile: AiProviderProfile): String {
        val validated = profile.validate()
        if (validated.authMode != AiAuthMode.ApiKey) {
            throw AiReauthenticationRequiredException()
        }
        return "Bearer ${validated.apiKey}"
    }
}

class AiReauthenticationRequiredException : Exception(
    "OAuth session expired or is unavailable. Sign in again in AI settings."
)

class AiApiKeyRejectedException : Exception("AI provider rejected the API key")

class AiHttpStatusException(
    val status: Int,
    message: String
) : Exception(message)

/**
 * Produces exactly one authentication mechanism per request. AppAuth refreshes an expiring token
 * before returning it; a refresh failure is terminal until the user explicitly signs in again.
 */
class AndroidAiBearerTokenProvider(context: Context) : AiBearerTokenProvider {
    private val appContext = context.applicationContext
    private val store = AiOAuthSessionStore(appContext)

    override suspend fun authorizationHeader(profile: AiProviderProfile): String {
        val validated = profile.validate()
        if (validated.authMode == AiAuthMode.ApiKey) return "Bearer ${validated.apiKey}"
        val state = store.load(validated) ?: throw AiReauthenticationRequiredException()
        val service = AuthorizationService(appContext)
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { service.dispose() }
                state.performActionWithFreshTokens(
                    service,
                    NoClientAuthentication.INSTANCE
                ) { accessToken, _, exception ->
                    if (!continuation.isActive) return@performActionWithFreshTokens
                    if (exception != null || accessToken.isNullOrBlank()) {
                        store.clear()
                        continuation.resumeWithException(AiReauthenticationRequiredException())
                    } else {
                        runCatching { store.save(validated, state) }
                            .onSuccess { continuation.resume("Bearer $accessToken") }
                            .onFailure {
                                store.clear()
                                continuation.resumeWithException(AiReauthenticationRequiredException())
                            }
                    }
                }
            }
        } finally {
            service.dispose()
        }
    }

    override fun onUnauthorized(profile: AiProviderProfile): Exception {
        if (profile.authMode == AiAuthMode.OAuthPkce) {
            store.clear()
            return AiReauthenticationRequiredException()
        }
        return super.onUnauthorized(profile)
    }
}

/** Encrypted, no-backup persistence for AppAuth state, including access and refresh tokens. */
class AiOAuthSessionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "ai/oauth-session.bin")
    private val atomicFile = AtomicFile(file)

    fun load(profile: AiProviderProfile): AuthState? {
        if (!file.isFile || profile.authMode != AiAuthMode.OAuthPkce) return null
        return runCatching {
            val encrypted = DataInputStream(ByteArrayInputStream(atomicFile.readFully())).use { input ->
                require(input.readInt() == MAGIC) { "Unexpected OAuth session format" }
                val ivSize = input.readInt()
                require(ivSize in 12..32) { "Invalid OAuth session IV" }
                val iv = ByteArray(ivSize).also(input::readFully)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "Invalid OAuth session size" }
                EncryptedPayload(iv, ByteArray(payloadSize).also(input::readFully))
            }
            val plaintext = decrypt(encrypted)
            try {
                val json = JSONObject(plaintext.toString(Charsets.UTF_8))
                require(json.getString("profile") == profileFingerprint(profile)) {
                    "OAuth session belongs to another provider"
                }
                AuthState.jsonDeserialize(json.getString("authState"))
            } finally {
                plaintext.fill(0)
                encrypted.payload.fill(0)
            }
        }.getOrNull()
    }

    fun save(profile: AiProviderProfile, authState: AuthState) {
        val validated = profile.validate()
        require(validated.authMode == AiAuthMode.OAuthPkce) { "OAuth profile is required" }
        val plaintext = JSONObject()
            .put("profile", profileFingerprint(validated))
            .put("authState", authState.jsonSerializeString())
            .toString()
            .toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val encrypted = try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val bytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(encrypted.iv.size)
                output.write(encrypted.iv)
                output.writeInt(encrypted.payload.size)
                output.write(encrypted.payload)
            }
            bytes.toByteArray()
        }
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        } finally {
            bytes.fill(0)
            encrypted.payload.fill(0)
        }
    }

    fun hasSession(profile: AiProviderProfile): Boolean = load(profile)?.isAuthorized == true

    fun clear() = atomicFile.delete()

    private fun profileFingerprint(profile: AiProviderProfile): String {
        val material = listOf(
            profile.baseUrl,
            profile.oauthAuthorizationEndpoint,
            profile.oauthTokenEndpoint,
            profile.oauthClientId,
            AiProviderProfile.oauthRedirectUri
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(material)
                .joinToString("") { "%02x".format(it) }
        } finally {
            material.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    private fun decrypt(encrypted: EncryptedPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.payload)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private data class EncryptedPayload(val iv: ByteArray, val payload: ByteArray)

    private companion object {
        const val MAGIC = 0x41494F31 // AIO1
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fcitx.ai.oauth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class AiOAuthSessionManager(context: Context) {
    private val store = AiOAuthSessionStore(context.applicationContext)

    suspend fun revokeAndClear(profile: AiProviderProfile): Boolean = withContext(Dispatchers.IO) {
        val validated = profile.validate()
        if (validated.authMode != AiAuthMode.OAuthPkce) {
            store.clear()
            return@withContext true
        }
        val state = store.load(validated)
        val token = state?.refreshToken ?: state?.accessToken
        val hint = if (state?.refreshToken != null) "refresh_token" else "access_token"
        try {
            if (token != null && validated.oauthRevocationEndpoint.isNotEmpty()) {
                revoke(validated.oauthRevocationEndpoint, validated.oauthClientId, token, hint)
            } else {
                true
            }
        } finally {
            store.clear()
        }
    }

    private fun revoke(endpoint: String, clientId: String, token: String, hint: String): Boolean {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        return try {
            val form = listOf(
                "client_id" to clientId,
                "token" to token,
                "token_type_hint" to hint
            ).joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }.toByteArray(Charsets.UTF_8)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(form.size)
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )
                connection.outputStream.use { it.write(form) }
                connection.responseCode in 200..299
            } finally {
                form.fill(0)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
