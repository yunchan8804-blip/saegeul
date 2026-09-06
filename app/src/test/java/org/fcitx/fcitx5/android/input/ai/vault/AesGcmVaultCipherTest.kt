/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Random

/**
 * Unit tests for AesGcmVaultCipher.
 * Verifies AES/GCM roundtrip correctness, IV randomization, and AEAD authentication failures.
 */
class AesGcmVaultCipherTest {

    private val aad = "net.chanpaca.saegeul|test".toByteArray()

    @Test
    fun testRoundtrip() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = "안녕하세요, 새글 개인화 볼트 테스트입니다.".toByteArray()

        val blob = cipher.encrypt(plain, aad)
        val decrypted = cipher.decrypt(blob, aad)

        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun testDifferentIvEachEncryption() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = "같은 평문을 두 번 암호화한다.".toByteArray()

        val blobOne = cipher.encrypt(plain, aad)
        val blobTwo = cipher.encrypt(plain, aad)

        assertFalse(blobOne.contentEquals(blobTwo))
        // IV occupies the first 12 bytes of the blob and must differ between calls.
        assertNotEquals(
            blobOne.copyOfRange(0, 12).toList(),
            blobTwo.copyOfRange(0, 12).toList()
        )
    }

    @Test
    fun testWrongAadFailsDecryption() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = "AAD가 다르면 복호화가 실패해야 한다.".toByteArray()
        val blob = cipher.encrypt(plain, aad)

        try {
            cipher.decrypt(blob, "different-aad".toByteArray())
            fail("Expected a GeneralSecurityException for mismatched AAD")
        } catch (expected: GeneralSecurityException) {
            // expected: AEADBadTagException is a GeneralSecurityException subtype
        }
    }

    @Test
    fun testWrongKeyFailsDecryption() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val otherCipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = "다른 키로는 복호화할 수 없다.".toByteArray()
        val blob = cipher.encrypt(plain, aad)

        try {
            otherCipher.decrypt(blob, aad)
            fail("Expected a GeneralSecurityException for a mismatched key")
        } catch (expected: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun testEmptyPlaintextRoundtrip() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = ByteArray(0)

        val blob = cipher.encrypt(plain, aad)
        val decrypted = cipher.decrypt(blob, aad)

        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun testOneMegabytePlaintextRoundtrip() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val plain = ByteArray(1024 * 1024)
        Random(42L).nextBytes(plain)

        val blob = cipher.encrypt(plain, aad)
        val decrypted = cipher.decrypt(blob, aad)

        assertArrayEquals(plain, decrypted)
    }
}
