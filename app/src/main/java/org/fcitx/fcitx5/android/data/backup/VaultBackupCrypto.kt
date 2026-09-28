/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Binary framing and password-based encryption for the `.saegeulbackup` file format.
 *
 * Layout: magic `SGBK` (4 bytes) + formatVersion (Int32 BE) + PBKDF2 iterations (Int32 BE) +
 * salt (16 bytes) + password-check value (32 bytes, HMAC-SHA256 of a fixed context string under
 * the derived key) + GCM nonce (12 bytes), all of which together form the AES-GCM AAD, followed
 * by the AES-256-GCM ciphertext (with its 16-byte tag appended) of the plaintext ZIP payload.
 *
 * The password-check value lets [decrypt] tell a wrong password apart from a tampered/corrupted
 * file: with plain AES-GCM alone both look identical (an authentication-tag failure), so a wrong
 * password is detected against this value *before* the main GCM tag is even checked, and any
 * failure after that point (header or ciphertext tampering, with the right password) is reported
 * as [DecryptResult.Corrupted].
 */
internal object VaultBackupCrypto {
    const val CURRENT_FORMAT_VERSION = 1
    const val DEFAULT_PBKDF2_ITERATIONS = 600_000

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val PASSWORD_CHECK_LENGTH = 32
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PASSWORD_CHECK_CONTEXT = "saegeul-vault-backup-password-check-v1"

    sealed interface DecryptResult {
        data class Ok(val plaintext: ByteArray) : DecryptResult
        data object WrongPassword : DecryptResult
        data object Corrupted : DecryptResult
        data object NewerVersion : DecryptResult
        data object NotABackup : DecryptResult
    }

    fun encrypt(plaintext: ByteArray, password: CharArray, iterations: Int = DEFAULT_PBKDF2_ITERATIONS): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, iterations)
        try {
            val check = passwordCheck(key)
            val header = buildHeader(CURRENT_FORMAT_VERSION, iterations, salt, check, nonce)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            return header + ciphertext
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, password: CharArray): DecryptResult {
        if (blob.size < MAGIC.size + 4) return DecryptResult.NotABackup
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return DecryptResult.NotABackup
        }
        var offset = MAGIC.size
        val formatVersion = readInt(blob, offset)
        offset += 4
        if (formatVersion > CURRENT_FORMAT_VERSION) return DecryptResult.NewerVersion
        if (formatVersion != CURRENT_FORMAT_VERSION) return DecryptResult.Corrupted
        if (blob.size < offset + 4) return DecryptResult.Corrupted
        val iterations = readInt(blob, offset)
        offset += 4
        if (iterations <= 0) return DecryptResult.Corrupted
        if (blob.size < offset + SALT_LENGTH + PASSWORD_CHECK_LENGTH + NONCE_LENGTH) return DecryptResult.Corrupted
        val salt = blob.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val storedCheck = blob.copyOfRange(offset, offset + PASSWORD_CHECK_LENGTH)
        offset += PASSWORD_CHECK_LENGTH
        val nonce = blob.copyOfRange(offset, offset + NONCE_LENGTH)
        offset += NONCE_LENGTH
        val headerLength = offset
        if (blob.size <= headerLength) return DecryptResult.Corrupted

        val key = deriveKey(password, salt, iterations)
        try {
            val expectedCheck = passwordCheck(key)
            if (!MessageDigest.isEqual(expectedCheck, storedCheck)) return DecryptResult.WrongPassword
            val header = blob.copyOfRange(0, headerLength)
            val ciphertext = blob.copyOfRange(headerLength, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            return try {
                DecryptResult.Ok(cipher.doFinal(ciphertext))
            } catch (e: GeneralSecurityException) {
                // GCM 인증 실패는 손상되었거나 다른 버전으로 만든 백업 파일에서 정상적으로 발생한다.
                DecryptResult.Corrupted
            }
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun passwordCheck(key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(PASSWORD_CHECK_CONTEXT.toByteArray(Charsets.UTF_8))
    }

    private fun buildHeader(
        formatVersion: Int,
        iterations: Int,
        salt: ByteArray,
        check: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val out = ByteArray(MAGIC.size + 4 + 4 + salt.size + check.size + nonce.size)
        var offset = 0
        System.arraycopy(MAGIC, 0, out, offset, MAGIC.size)
        offset += MAGIC.size
        writeInt(out, offset, formatVersion)
        offset += 4
        writeInt(out, offset, iterations)
        offset += 4
        System.arraycopy(salt, 0, out, offset, salt.size)
        offset += salt.size
        System.arraycopy(check, 0, out, offset, check.size)
        offset += check.size
        System.arraycopy(nonce, 0, out, offset, nonce.size)
        return out
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
