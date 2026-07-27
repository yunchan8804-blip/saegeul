/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.input.EncryptedProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfilePayload
import org.fcitx.fcitx5.android.input.EncryptedProfileStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores BYOK credentials outside SharedPreferences, Android backup, and user ZIP exports. */
class AiProviderCredentialStore internal constructor(
    root: File,
    private val cipher: EncryptedProfileCipher
) {
    constructor(context: Context) : this(
        context.noBackupFilesDir,
        AndroidKeystoreAiProviderCipher()
    )

    private val file = File(root, RELATIVE_PATH)
    private val storage = EncryptedProfileStorage(file)

    fun load(): AiProviderProfile? {
        if (!storage.exists()) return null
        return runCatching {
            val bytes = storage.read(MAX_FILE_BYTES) ?: return null
            val encrypted = try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readInt() == MAGIC) {
                        "Unexpected AI provider profile format"
                    }
                    val ivSize = input.readInt()
                    require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES) {
                        "Invalid AI provider profile IV"
                    }
                    val iv = ByteArray(ivSize).also(input::readFully)
                    val payloadSize = input.readInt()
                    require(payloadSize in 1..MAX_PAYLOAD_BYTES) {
                        "Invalid AI provider profile size"
                    }
                    val payload = ByteArray(payloadSize).also(input::readFully)
                    require(input.read() == -1) { "Trailing AI provider profile data" }
                    EncryptedProfilePayload(iv, payload)
                }
            } finally {
                bytes.fill(0)
            }
            try {
                val plaintext = cipher.decrypt(encrypted)
                try {
                    decode(plaintext).validate()
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                encrypted.payload.fill(0)
            }
        }.getOrNull()
    }

    fun save(profile: AiProviderProfile) {
        val validated = profile.validate()
        val plaintext = encode(validated)
        val encrypted = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) {
            "Invalid encrypted AI provider profile IV"
        }
        require(encrypted.payload.size in 1..MAX_PAYLOAD_BYTES) {
            "Invalid encrypted AI provider profile size"
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
        try {
            storage.write(bytes)
        } finally {
            bytes.fill(0)
            encrypted.payload.fill(0)
        }
    }

    fun clear() {
        storage.delete()
        runCatching(cipher::clear)
    }

    fun hasCustomProfile(): Boolean = storage.exists()

    private fun encode(profile: AiProviderProfile): ByteArray = buildJsonObject {
        put("kind", profile.kind.name)
        put("displayName", profile.displayName)
        put("baseUrl", profile.baseUrl)
        put("authMode", profile.authMode.name)
        put("apiKey", profile.apiKey)
        put("oauthAuthorizationEndpoint", profile.oauthAuthorizationEndpoint)
        put("oauthTokenEndpoint", profile.oauthTokenEndpoint)
        put("oauthRevocationEndpoint", profile.oauthRevocationEndpoint)
        put("oauthClientId", profile.oauthClientId)
        put("oauthScopes", profile.oauthScopes)
        put("capabilities", buildJsonArray {
            profile.capabilities.sorted().forEach(::add)
        })
        put("fastModel", profile.fastModel)
        put("balancedModel", profile.balancedModel)
        put("qualityModel", profile.qualityModel)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): AiProviderProfile {
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val kind = runCatching { AiProviderKind.valueOf(json.string("kind")) }
            .getOrDefault(AiProviderKind.OpenAICompatible)
        val authMode = runCatching { AiAuthMode.valueOf(json.string("authMode")) }
            .getOrDefault(AiAuthMode.ApiKey)
        val capabilities = json["capabilities"]?.jsonArray?.mapNotNull { value ->
            value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
        }?.toSet() ?: if (authMode == AiAuthMode.OAuthPkce) {
            setOf("responses")
        } else {
            AiProviderProfile.DEFAULT_CAPABILITIES
        }
        return AiProviderProfile(
            kind = kind,
            displayName = json.string("displayName"),
            baseUrl = json.string("baseUrl"),
            authMode = authMode,
            apiKey = json.string("apiKey"),
            oauthAuthorizationEndpoint = json.string("oauthAuthorizationEndpoint"),
            oauthTokenEndpoint = json.string("oauthTokenEndpoint"),
            oauthRevocationEndpoint = json.string("oauthRevocationEndpoint"),
            oauthClientId = json.string("oauthClientId"),
            oauthScopes = json.string(
                "oauthScopes",
                AiProviderProfile.DEFAULT_OAUTH_SCOPES
            ),
            capabilities = capabilities,
            fastModel = json.string("fastModel"),
            balancedModel = json.string("balancedModel"),
            qualityModel = json.string("qualityModel")
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(
        name: String,
        default: String = ""
    ): String = get(name)?.jsonPrimitive?.contentOrNull ?: default

    companion object {
        internal const val RELATIVE_PATH = "ai/provider.bin"
        internal const val KEY_ALIAS = "fcitx.ai.provider.v1"
        private const val MAGIC = 0x41495031 // AIP1
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = MAX_PAYLOAD_BYTES + MAX_IV_BYTES + 16L
    }
}

private class AndroidKeystoreAiProviderCipher : EncryptedProfileCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedProfilePayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedProfilePayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: EncryptedProfilePayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.payload)
    }

    override fun clear() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val keyStore = keyStore()
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

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = AiProviderCredentialStore.KEY_ALIAS
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
