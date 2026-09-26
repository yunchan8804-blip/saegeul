/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.KeyGenerator

/**
 * Device-bound AES-GCM cipher backed by the Android Keystore.
 *
 * The wrapping key never leaves secure hardware/TEE and is not extractable; Saegeul keeps no
 * backdoor, escrow, or logging path for it. Whole-file payload encryption runs on a random
 * software data key that the hardware key wraps once per process ([EnvelopeVaultCipher]), because
 * per-save hardware key operations are measured at seconds on some devices and stall typing.
 * Data at rest stays hardware-key protected: the wrapped data key is only recoverable through
 * the Keystore key.
 *
 * Key generations:
 * - `saegeul.vault.v1` was generated with a StrongBox preference. On devices where StrongBox
 *   (SPU) operations take seconds, loading or saving through that key froze input, so new keys
 *   use a plain TEE key under [DEFAULT_ALIAS] (v2) and StrongBox is no longer preferred.
 * - Blobs written by older generations keep decrypting: envelope blobs wrapped by the v1 key,
 *   and pre-envelope blobs encrypted directly by the v1 key.
 *
 * Not exercised by JVM unit tests: `AndroidKeyStore` only exists on-device. The envelope contract
 * itself is covered by `EnvelopeVaultCipherTest`.
 */
class KeystoreVaultCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val legacyAlias: String = LEGACY_ALIAS
) : VaultCipher {

    private val envelope: EnvelopeVaultCipher by lazy {
        EnvelopeVaultCipher(wrapping = AesGcmVaultCipher(keyOrCreate(alias)))
    }

    private val legacyEnvelope: EnvelopeVaultCipher? by lazy {
        if (hasKey(legacyAlias)) {
            EnvelopeVaultCipher(wrapping = AesGcmVaultCipher(keyOrCreate(legacyAlias)), magic = EnvelopeVaultCipher.MAGIC_PREVIOUS)
        } else {
            null
        }
    }

    private val legacyDirect: AesGcmVaultCipher? by lazy {
        if (hasKey(legacyAlias)) AesGcmVaultCipher(keyOrCreate(legacyAlias)) else null
    }

    override val id: String get() = envelope.id

    /** True when the active wrapping key lives inside a TEE or StrongBox; false if unknown/software-backed. */
    val isHardwareBacked: Boolean
        get() {
            val key = keyOrCreate(alias)
            val level = securityLevelOf(key)
            return if (level != null) {
                level >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            } else {
                legacyInsideSecureHardware(key)
            }
        }

    /** True only when the active wrapping key is confirmed StrongBox-backed (API 31+). */
    val isStrongBoxBacked: Boolean
        get() = securityLevelOf(keyOrCreate(alias))?.let { it == KeyProperties.SECURITY_LEVEL_STRONGBOX } ?: false

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = envelope.encrypt(plain, aad)

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = when {
        EnvelopeVaultCipher.hasEnvelopeHeader(blob) && matchesHeader(blob, EnvelopeVaultCipher.MAGIC_CURRENT) ->
            envelope.decrypt(blob, aad)
        legacyEnvelope != null ->
            legacyEnvelope!!.decrypt(blob, aad)
        else ->
            throw java.security.GeneralSecurityException("No vault key available to decrypt this blob")
    }

    private fun matchesHeader(blob: ByteArray, magic: ByteArray): Boolean {
        if (blob.size < magic.size) return false
        for (index in magic.indices) {
            if (blob[index] != magic[index]) return false
        }
        return true
    }

    private fun hasKey(targetAlias: String): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getEntry(targetAlias, null) is KeyStore.SecretKeyEntry
    }

    private fun keyOrCreate(targetAlias: String): SecretKey = keyed(targetAlias) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(targetAlias, null) as? SecretKey
        existing ?: generateKey()
    }

    private inline fun keyed(targetAlias: String, block: () -> SecretKey): SecretKey {
        val existing = cachedKeys[targetAlias]
        if (existing != null) return existing
        val key = block()
        cachedKeys[targetAlias] = key
        return key
    }

    private val cachedKeys = HashMap<String, SecretKey>()

    private fun generateKey(): SecretKey {
        // StrongBox는 의도적으로 요구하지 않는다. SPU 연산이 수 초로 측정된 기기에서 금고
        // 저장·조회가 입력을 멈춘다. TEE 하드웨어 키로 같은 추출 불가 경계를 유지한다.
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

        /** Current generation: plain TEE hardware key (no StrongBox preference). */
        const val DEFAULT_ALIAS = "saegeul.vault.v2"

        /** First generation alias, kept only for decrypting data written by older installs. */
        const val LEGACY_ALIAS = "saegeul.vault.v1"
    }
}
