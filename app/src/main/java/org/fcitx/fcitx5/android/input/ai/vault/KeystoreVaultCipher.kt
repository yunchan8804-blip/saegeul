/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Device-bound AES-GCM cipher backed by the Android Keystore.
 *
 * The key never leaves secure hardware/TEE and is not extractable; Saegeul keeps no backdoor,
 * escrow, or logging path for it. Encryption/decryption is delegated to [AesGcmVaultCipher] once
 * the Keystore-backed key has been resolved or generated.
 *
 * Not exercised by JVM unit tests: `AndroidKeyStore` only exists on-device.
 */
class KeystoreVaultCipher(private val alias: String = DEFAULT_ALIAS) : VaultCipher {

    override val id: String = "aesgcm"

    @Volatile
    private var cachedKey: SecretKey? = null

    private val delegate: VaultCipher by lazy { AesGcmVaultCipher(keyOrCreate()) }

    /** True when the key lives inside a TEE or StrongBox; false if unknown/software-backed. */
    val isHardwareBacked: Boolean
        get() {
            val key = keyOrCreate()
            val level = securityLevelOf(key)
            return if (level != null) {
                level >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            } else {
                legacyInsideSecureHardware(key)
            }
        }

    /** True only when the key is confirmed StrongBox-backed (API 31+). */
    val isStrongBoxBacked: Boolean
        get() = securityLevelOf(keyOrCreate())?.let { it == KeyProperties.SECURITY_LEVEL_STRONGBOX } ?: false

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = delegate.encrypt(plain, aad)

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = delegate.decrypt(blob, aad)

    @Synchronized
    private fun keyOrCreate(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        val key = existing ?: generateKey()
        cachedKey = key
        return key
    }

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateWithSpec(buildKeySpec(strongBox = true))
            } catch (e: StrongBoxUnavailableException) {
                // StrongBox unavailable on this device; fall back to a TEE-backed key below.
            }
        }
        return generateWithSpec(buildKeySpec(strongBox = false))
    }

    private fun buildKeySpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun generateWithSpec(spec: KeyGenParameterSpec): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    /** [KeyInfo.getSecurityLevel] requires API 31+; returns null below that or on failure. */
    private fun securityLevelOf(key: SecretKey): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            info.securityLevel
        }.getOrNull()
    }

    /** Pre-API-31 fallback where only [KeyInfo.isInsideSecureHardware] is available. */
    private fun legacyInsideSecureHardware(key: SecretKey): Boolean = runCatching {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isInsideSecureHardware
    }.getOrDefault(false)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private const val DEFAULT_ALIAS = "saegeul.vault.v1"
    }
}
