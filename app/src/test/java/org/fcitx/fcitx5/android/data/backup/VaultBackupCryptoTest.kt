/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast PBKDF2 iteration count for tests; production always uses [VaultBackupCrypto.DEFAULT_PBKDF2_ITERATIONS]. */
private const val TEST_ITERATIONS = 4

class VaultBackupCryptoTest {

    @Test
    fun defaultProductionIterationsAreFixedAtSixHundredThousand() {
        assertEquals(600_000, VaultBackupCrypto.DEFAULT_PBKDF2_ITERATIONS)
    }

    @Test
    fun roundTripWithCorrectPasswordReturnsOriginalPlaintext() {
        val plaintext = "hello vault".toByteArray(Charsets.UTF_8)
        val encrypted = VaultBackupCrypto.encrypt(plaintext, "correct horse".toCharArray(), TEST_ITERATIONS)

        val result = VaultBackupCrypto.decrypt(encrypted, "correct horse".toCharArray())

        val ok = result as? VaultBackupCrypto.DecryptResult.Ok
            ?: throw AssertionError("expected Ok, got $result")
        assertArrayEquals(plaintext, ok.plaintext)
    }

    @Test
    fun wrongPasswordIsReportedDistinctlyFromCorruption() {
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), "right-password".toCharArray(), TEST_ITERATIONS)

        val result = VaultBackupCrypto.decrypt(encrypted, "wrong-password".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.WrongPassword, result)
    }

    @Test
    fun tamperedCiphertextWithTheCorrectPasswordIsCorrupted() {
        val password = "right-password".toCharArray()
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), password, TEST_ITERATIONS)
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        val result = VaultBackupCrypto.decrypt(tampered, "right-password".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.Corrupted, result)
    }

    @Test
    fun tamperedHeaderAadWithTheCorrectPasswordIsCorrupted() {
        val password = "right-password".toCharArray()
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), password, TEST_ITERATIONS)
        // Header layout: magic(4) + formatVersion(4) + iterations(4) + salt(16) + passwordCheck(32)
        // + nonce(12). Flip a byte inside the nonce - part of the GCM AAD, but not the salt the
        // password-check value is derived from - so the password-check still matches and this
        // exercises the GCM-tag-over-tampered-AAD path specifically, distinct from the
        // ciphertext-tamper test above.
        val nonceOffset = 4 + 4 + 4 + 16 + 32
        val tampered = encrypted.copyOf()
        tampered[nonceOffset] = (tampered[nonceOffset] + 1).toByte()

        val result = VaultBackupCrypto.decrypt(tampered, "right-password".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.Corrupted, result)
    }

    @Test
    fun magicMismatchIsNotABackup() {
        val notABackup = "definitely not a saegeul backup file".toByteArray(Charsets.UTF_8)

        val result = VaultBackupCrypto.decrypt(notABackup, "anything".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.NotABackup, result)
    }

    @Test
    fun tooShortToContainAHeaderIsNotABackup() {
        val result = VaultBackupCrypto.decrypt(byteArrayOf('S'.code.toByte(), 'G'.code.toByte()), "x".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.NotABackup, result)
    }

    @Test
    fun hostileIterationCountIsCorruptedWithoutRunningTheKeyDerivation() {
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), "pw123456".toCharArray(), TEST_ITERATIONS)
        val hostile = encrypted.copyOf()
        // iterations is the Int32 right after magic(4) + formatVersion(4).
        hostile[8] = 0x7F
        hostile[9] = 0xFF.toByte()
        hostile[10] = 0xFF.toByte()
        hostile[11] = 0xFF.toByte()

        val startedAt = System.nanoTime()
        val result = VaultBackupCrypto.decrypt(hostile, "pw123456".toCharArray())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(VaultBackupCrypto.DecryptResult.Corrupted, result)
        assertTrue("decrypt must reject before deriving the key, took ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun iterationCountJustOverTheCapIsCorrupted() {
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), "pw123456".toCharArray(), TEST_ITERATIONS)
        val tooMany = VaultBackupCrypto.MAX_PBKDF2_ITERATIONS + 1
        val hostile = encrypted.copyOf()
        hostile[8] = (tooMany ushr 24).toByte()
        hostile[9] = (tooMany ushr 16).toByte()
        hostile[10] = (tooMany ushr 8).toByte()
        hostile[11] = tooMany.toByte()

        assertEquals(VaultBackupCrypto.DecryptResult.Corrupted, VaultBackupCrypto.decrypt(hostile, "pw123456".toCharArray()))
    }

    @Test
    fun maxEncryptedBytesCoversHeaderAndGcmTag() {
        val encrypted = VaultBackupCrypto.encrypt(ByteArray(100), "pw123456".toCharArray(), TEST_ITERATIONS)

        assertEquals(encrypted.size.toLong(), VaultBackupCrypto.maxEncryptedBytes(100))
    }

    @Test
    fun formatVersionNewerThanSupportedIsNewerVersion() {
        val encrypted = VaultBackupCrypto.encrypt("payload".toByteArray(), "pw123456".toCharArray(), TEST_ITERATIONS)
        val bumped = encrypted.copyOf()
        // formatVersion is the 4 bytes right after the 4-byte "SGBK" magic (big-endian Int32).
        bumped[7] = (VaultBackupCrypto.CURRENT_FORMAT_VERSION + 1).toByte()

        val result = VaultBackupCrypto.decrypt(bumped, "pw123456".toCharArray())

        assertEquals(VaultBackupCrypto.DecryptResult.NewerVersion, result)
    }
}
