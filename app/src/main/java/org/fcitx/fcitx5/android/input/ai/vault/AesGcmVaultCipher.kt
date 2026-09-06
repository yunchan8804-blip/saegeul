/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM/NoPadding cipher for vault payloads.
 *
 * Works with both software [SecretKey]s and Android Keystore-backed keys: encryption never
 * specifies an IV up front (Keystore keys reject explicit IVs for randomized encryption), it is
 * always read back from [Cipher.getIV] after [Cipher.init]. Output layout is
 * `IV (12 bytes) || ciphertext+tag`.
 */
class AesGcmVaultCipher(private val key: SecretKey) : VaultCipher {

    override val id: String = "aesgcm"

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArray(iv.size + ciphertext.size).also { out ->
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < IV_LENGTH_BYTES) {
            throw GeneralSecurityException("Vault blob too short to contain an IV")
        }
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val KEY_SIZE_BITS = 256

        /** Generates a fresh random AES-256 key using a SecureRandom-backed key generator. */
        fun randomKey(): SecretKey {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(KEY_SIZE_BITS, SecureRandom())
            return generator.generateKey()
        }
    }
}
