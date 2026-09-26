/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * 봉투 암호 계약. 하드웨어(Keystore) 키 연산은 데이터 키 랩핑에만 쓰고, 실제 파일 암호화는
 * 소프트웨어 데이터 키로 수행해 입력 중 하드웨어 연산 폭풍(기기별 초 단위 지연)을 막는다.
 */
class EnvelopeVaultCipherTest {

    private class CountingCipher(private val delegate: VaultCipher) : VaultCipher {
        var encryptCalls = 0
            private set
        var decryptCalls = 0
            private set

        override val id: String = delegate.id

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            encryptCalls++
            return delegate.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            decryptCalls++
            return delegate.decrypt(blob, aad)
        }
    }

    private val aad = "vault-file-aad".toByteArray()

    @Test
    fun roundtripsPayloadThroughSoftwareDataKey() {
        val wrapping = CountingCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val cipher = EnvelopeVaultCipher(wrapping)
        val plain = """{"pairs":["감사합니다","회의 참석하세요"]}""".toByteArray()

        val blob = cipher.encrypt(plain, aad)

        assertArrayEquals(plain, cipher.decrypt(blob, aad))
        assertEquals(1, wrapping.encryptCalls)
        assertEquals(1, wrapping.decryptCalls)
        assertNotEquals(plain, blob)
        assertTrue(cipher.id == "aesgcm")
    }

    @Test
    fun wrapsDataKeyOnlyOnceAcrossSaves() {
        val wrapping = CountingCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val cipher = EnvelopeVaultCipher(wrapping)

        val first = cipher.encrypt("첫 저장".toByteArray(), aad)
        val second = cipher.encrypt("둘째 저장".toByteArray(), aad)

        assertEquals(1, wrapping.encryptCalls)
        assertEquals("첫 저장", String(cipher.decrypt(first, aad), Charsets.UTF_8))
        assertEquals("둘째 저장", String(cipher.decrypt(second, aad), Charsets.UTF_8))
        assertEquals(1, wrapping.decryptCalls)
    }

    @Test
    fun readsLegacyDirectEncryptionBlobs() {
        val hardwareKey = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val legacyBlob = hardwareKey.encrypt("이전 형식 데이터".toByteArray(), aad)
        val cipher = EnvelopeVaultCipher(hardwareKey)

        assertEquals("이전 형식 데이터", String(cipher.decrypt(legacyBlob, aad), Charsets.UTF_8))
    }

    @Test
    fun unwrapsDataKeyOncePerProcessWhenReadingOlderFiles() {
        val hardwareKey = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val previousProcess = EnvelopeVaultCipher(hardwareKey)
        val oldBlob = previousProcess.encrypt("이전 세션 저장".toByteArray(), aad)

        val wrapping = CountingCipher(hardwareKey)
        val restarted = EnvelopeVaultCipher(wrapping)

        assertEquals("이전 세션 저장", String(restarted.decrypt(oldBlob, aad), Charsets.UTF_8))
        assertEquals(1, wrapping.decryptCalls)
        restarted.decrypt(oldBlob, aad)
        assertEquals(1, wrapping.decryptCalls)
    }

    @Test
    fun rejectsAadMismatch() {
        val cipher = EnvelopeVaultCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val blob = cipher.encrypt("바인딩 검증".toByteArray(), aad)

        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(blob, "다른 파일".toByteArray())
        }
    }

    @Test
    fun rejectsCorruptedWrappedDataKey() {
        val cipher = EnvelopeVaultCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val blob = cipher.encrypt("무결성 검증".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x41).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(blob, aad)
        }
    }

    @Test
    fun writesCurrentEnvelopeHeader() {
        val cipher = EnvelopeVaultCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val blob = cipher.encrypt("형식 검증".toByteArray(), aad)

        assertArrayEquals(EnvelopeVaultCipher.MAGIC_CURRENT, blob.copyOfRange(0, 4))
    }

    @Test
    fun readsBlobsWrittenInLegacyEnvelopeFormat() {
        val wrapping = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val legacyFormat = EnvelopeVaultCipher(wrapping, magic = EnvelopeVaultCipher.MAGIC_PREVIOUS)
        val legacyBlob = legacyFormat.encrypt("이전 봉투 형식".toByteArray(), aad)

        assertArrayEquals(EnvelopeVaultCipher.MAGIC_PREVIOUS, legacyBlob.copyOfRange(0, 4))
        assertEquals("이전 봉투 형식", String(legacyFormat.decrypt(legacyBlob, aad), Charsets.UTF_8))

        val current = EnvelopeVaultCipher(wrapping, magic = EnvelopeVaultCipher.MAGIC_CURRENT)
        assertThrows(GeneralSecurityException::class.java) {
            current.decrypt(legacyBlob, aad)
        }
    }

    @Test
    fun exposesBlobFormatProbeForRouting() {
        val cipher = EnvelopeVaultCipher(AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val envelopeBlob = cipher.encrypt("접두 판별".toByteArray(), aad)
        val legacyDirectBlob = AesGcmVaultCipher(AesGcmVaultCipher.randomKey()).encrypt("직접 형식".toByteArray(), aad)

        assertTrue(EnvelopeVaultCipher.hasEnvelopeHeader(envelopeBlob))
        assertTrue(!EnvelopeVaultCipher.hasEnvelopeHeader(legacyDirectBlob))
    }
}
