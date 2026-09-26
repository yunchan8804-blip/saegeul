/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.EnvelopeVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * Red Team Adversarial Unit Tests: Hardware DoS Protection and Cryptographic Integrity
 * in EnvelopeVaultCipher and AesGcmVaultCipher.
 *
 * Verifies that:
 * 1. Short payloads (< 28 bytes) abort BEFORE unwrapping DEK via hardware Keystore to prevent DoS.
 * 2. AesGcmVaultCipher rejects blobs smaller than IV (12B) + GCM Auth Tag (16B).
 * 3. Bit-flipped authentication tags fail-closed without leaking plaintext.
 * 4. AAD tampering fails-closed.
 * 5. Legacy direct ciphertexts without envelope magic headers seamlessly decrypt via wrapping cipher.
 */
class RedTeamVaultCryptoHardwareDosTest {

    private class SpyVaultCipher(private val delegate: VaultCipher) : VaultCipher {
        var unwrapCalls = 0
            private set
        var wrapCalls = 0
            private set

        override val id: String = delegate.id

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            wrapCalls++
            return delegate.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            unwrapCalls++
            return delegate.decrypt(blob, aad)
        }
    }

    /**
     * Attack Vector 1: Hardware HSM / Keystore Unwrapping DoS via Short Envelope Payloads.
     *
     * Invariant: If payload bytes are shorter than the minimum AES-GCM requirement
     * (IV 12 bytes + Auth Tag 16 bytes = 28 bytes), the cipher must abort immediately with
     * GeneralSecurityException BEFORE executing hardware unwrap on the wrapping cipher (unwrapCalls == 0).
     */
    @Test
    fun `envelope cipher short payload aborts before hardware unwrapping dek`() {
        val spyWrapping = SpyVaultCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val envelopeCipher = EnvelopeVaultCipher(spyWrapping)
        val aad = "vault-file-aad".toByteArray(Charsets.UTF_8)

        // Envelope block layout:
        // magic (4 bytes) + wrappedLength (2 bytes, 32) + wrappedDEK (32 bytes) + shortPayload (10 bytes: < 28 bytes)
        val magic = EnvelopeVaultCipher.MAGIC_CURRENT
        val wrappedLen = 32
        val wrappedDek = ByteArray(wrappedLen) { 0x7E.toByte() }
        val shortPayload = ByteArray(10) { 0x3C.toByte() }

        val blob = ByteArray(magic.size + 2 + wrappedLen + shortPayload.size).also { out ->
            var offset = 0
            System.arraycopy(magic, 0, out, offset, magic.size)
            offset += magic.size
            out[offset] = (wrappedLen ushr 8).toByte()
            out[offset + 1] = wrappedLen.toByte()
            offset += 2
            System.arraycopy(wrappedDek, 0, out, offset, wrappedLen)
            offset += wrappedLen
            System.arraycopy(shortPayload, 0, out, offset, shortPayload.size)
        }

        assertThrows(GeneralSecurityException::class.java) {
            envelopeCipher.decrypt(blob, aad)
        }

        assertEquals(
            "Hardware DEK unwrap must not be invoked when payload length is insufficient (< 28 bytes)",
            0,
            spyWrapping.unwrapCalls
        )
    }

    /**
     * Attack Vector 2: AES-GCM Cipher Bounds Violation with Blobs Shorter than IV + Auth Tag.
     *
     * Invariant: Any ciphertext blob shorter than 28 bytes (IV 12B + Tag 16B) must be
     * rejected with GeneralSecurityException.
     */
    @Test
    fun `aes gcm cipher rejects blob shorter than iv plus auth tag`() {
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val aad = "auth-aad".toByteArray(Charsets.UTF_8)
        val invalidLengths = listOf(0, 1, 11, 12, 13, 27)

        for (len in invalidLengths) {
            val shortBlob = ByteArray(len) { (it and 0xFF).toByte() }
            assertThrows(
                "Blob of length $len (< 28 bytes: IV 12 + GCM Tag 16) must throw GeneralSecurityException",
                GeneralSecurityException::class.java
            ) {
                cipher.decrypt(shortBlob, aad)
            }
        }
    }

    /**
     * Attack Vector 3: Authentication Tag Bit-Flipping Integrity Attack.
     *
     * Invariant: Corrupting any bit in the AES-GCM authentication tag must result in
     * GeneralSecurityException and must never yield partial or decrypted plaintext.
     */
    @Test
    fun `corrupted auth tag in payload prevents plaintext leak`() {
        val wrapping = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val cipher = EnvelopeVaultCipher(wrapping)
        val plain = "TypingDna-Sensitive-Habit-Data-Payload-Protection".toByteArray(Charsets.UTF_8)
        val aad = "vault-security-boundary-aad".toByteArray(Charsets.UTF_8)

        val blob = cipher.encrypt(plain, aad)
        // Flip the last byte (part of the 16-byte AES-GCM authentication tag)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()

        assertThrows(
            "Decryption with corrupted authentication tag must throw GeneralSecurityException and never return plaintext",
            GeneralSecurityException::class.java
        ) {
            cipher.decrypt(blob, aad)
        }
    }

    /**
     * Attack Vector 4: Associated Authenticated Data (AAD) Substitution Attack.
     *
     * Invariant: AAD mismatch must strictly fail-closed with GeneralSecurityException.
     */
    @Test
    fun `aad mismatch rejects decryption fail-closed`() {
        val wrapping = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val cipher = EnvelopeVaultCipher(wrapping)
        val plain = "Confidential typing biometric habit vector".toByteArray(Charsets.UTF_8)
        val legitimateAad = "device-scoped-keystore-aad".toByteArray(Charsets.UTF_8)
        val spoofedAad = "wrong-aad".toByteArray(Charsets.UTF_8)

        val blob = cipher.encrypt(plain, legitimateAad)

        assertThrows(
            "Decryption with mismatched AAD must fail-closed with GeneralSecurityException",
            GeneralSecurityException::class.java
        ) {
            cipher.decrypt(blob, spoofedAad)
        }
    }

    /**
     * Attack Vector 5: Backward Compatibility Routing for Legacy Direct Encryption Blobs.
     *
     * Invariant: Blobs lacking the SGW envelope header must seamlessly delegate to the
     * wrapping cipher and correctly restore original plaintext.
     */
    @Test
    fun `legacy direct ciphertext seamlessly decrypts through wrapping cipher`() {
        val hardwareKey = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val legacyPlain = "Legacy v1 raw encrypted payload before envelope format introduction".toByteArray(Charsets.UTF_8)
        val aad = "legacy-storage-aad".toByteArray(Charsets.UTF_8)

        // Directly encrypted by hardware wrapping cipher without SGW envelope magic
        val legacyDirectBlob = hardwareKey.encrypt(legacyPlain, aad)

        val envelopeCipher = EnvelopeVaultCipher(hardwareKey)
        val decrypted = envelopeCipher.decrypt(legacyDirectBlob, aad)

        assertArrayEquals(
            "Legacy direct ciphertext must seamlessly decrypt through wrapping cipher",
            legacyPlain,
            decrypted
        )
        assertEquals(
            "Legacy v1 raw encrypted payload before envelope format introduction",
            String(decrypted, Charsets.UTF_8)
        )
    }
}
