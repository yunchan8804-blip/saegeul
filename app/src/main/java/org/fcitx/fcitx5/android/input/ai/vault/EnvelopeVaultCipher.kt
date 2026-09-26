/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * 봉투 암호. 파일 암호화는 프로세스당 한 번 생성한 무작위 소프트웨어 데이터 키(DEK)로 수행하고,
 * DEK는 [wrapping] 하드웨어 바인딩 암호로 한 번 감싸 블록마다 함께 저장한다.
 *
 * Keystore(특히 StrongBox) 키는 추출 불가라 저장마다 하드웨어 연산이 필요하다. 기기에 따라 그
 * 연산이 수 초로 측정되어 입력 중 멈춤을 만들므로(SPU 기동 2.3초 + 32KB당 1.2초, keystore2
 * watchdog 실측), 하드웨어 연산을 DEK 랩핑·언랩 1회로 한정한다. 저장 boundary는 동일하게 유지된다:
 * 디스크에는 DEK가 감싸진 형태로만 존재하고, 감싼 DEK는 하드웨어 키 없이는 회복할 수 없다.
 *
 * 블록 형식: [magic] (4 bytes) + wrapped DEK length (2 bytes, big-endian) + wrapped DEK +
 * payload cipher blob (`IV || ciphertext+tag`). 봉투 접두사가 없는 블록은 봉투 도입 이전의
 * 직접 암호화 형식으로 [wrapping]이 바로 복호화해 기존 사용자 데이터를 보존한다.
 */
class EnvelopeVaultCipher(
    private val wrapping: VaultCipher,
    private val magic: ByteArray = MAGIC_CURRENT,
    private val payloadCipherFactory: (SecretKey) -> VaultCipher = ::AesGcmVaultCipher
) : VaultCipher {

    init {
        require(magic.size == MAGIC_LENGTH) { "Vault envelope magic must be $MAGIC_LENGTH bytes" }
    }

    override val id: String get() = wrapping.id

    private val lock = Any()
    private var currentDataKey: SecretKey? = null
    private var currentWrappedDataKey: ByteArray? = null
    private var unwrappedCache: MutableMap<String, SecretKey> = HashMap()

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val (dataKey, wrapped) = dataKeyOrWrap()
        val payload = payloadCipherFactory(dataKey).encrypt(plain, aad)
        return ByteArray(magic.size + 2 + wrapped.size + payload.size).also { out ->
            var offset = 0
            System.arraycopy(magic, 0, out, offset, magic.size)
            offset += magic.size
            out[offset] = (wrapped.size ushr 8).toByte()
            out[offset + 1] = wrapped.size.toByte()
            offset += 2
            System.arraycopy(wrapped, 0, out, offset, wrapped.size)
            offset += wrapped.size
            System.arraycopy(payload, 0, out, offset, payload.size)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (!hasHeader(blob, magic)) {
            return wrapping.decrypt(blob, aad)
        }
        val wrappedLen = wrappedLengthAt(blob)
        val payloadOffset = magic.size + 2 + wrappedLen
        val payloadSize = blob.size - payloadOffset
        if (payloadSize < MIN_PAYLOAD_BYTES) {
            throw GeneralSecurityException("Vault envelope payload too short to contain IV and auth tag")
        }
        val dataKey = dataKeyForBlob(blob)
        val payload = blob.copyOfRange(payloadOffset, blob.size)
        return payloadCipherFactory(dataKey).decrypt(payload, aad)
    }

    private fun dataKeyOrWrap(): Pair<SecretKey, ByteArray> {
        synchronized(lock) {
            val key = currentDataKey
            val wrapped = currentWrappedDataKey
            if (key != null && wrapped != null) return key to wrapped
            val newDataKey = AesGcmVaultCipher.randomKey()
            val newWrapped = wrapping.encrypt(newDataKey.encoded, DEK_WRAPPING_AAD)
            currentDataKey = newDataKey
            currentWrappedDataKey = newWrapped
            return newDataKey to newWrapped
        }
    }

    private fun dataKeyForBlob(blob: ByteArray): SecretKey {
        val wrappedLength = wrappedLengthAt(blob)
        val wrappedStart = magic.size + 2
        val wrapped = blob.copyOfRange(wrappedStart, wrappedStart + wrappedLength)
        val wrappedKey = wrapped.toString(Charsets.ISO_8859_1)
        synchronized(lock) {
            unwrappedCache[wrappedKey]?.let { return it }
            val dataKey = SecretKeyCodec.secretKeyFrom(wrapping.decrypt(wrapped, DEK_WRAPPING_AAD))
            if (unwrappedCache.size >= MAX_CACHED_WRAPPED_KEYS) unwrappedCache.clear()
            unwrappedCache[wrappedKey] = dataKey
            return dataKey
        }
    }

    private fun wrappedLengthAt(blob: ByteArray): Int {
        if (blob.size < magic.size + 2) {
            throw GeneralSecurityException("Vault envelope blob too short for a wrapped key header")
        }
        val high = blob[magic.size].toInt() and 0xFF
        val low = blob[magic.size + 1].toInt() and 0xFF
        val wrappedLength = (high shl 8) or low
        if (wrappedLength <= 0 || blob.size < magic.size + 2 + wrappedLength) {
            throw GeneralSecurityException("Vault envelope blob has an invalid wrapped key length")
        }
        return wrappedLength
    }

    private object SecretKeyCodec {
        fun secretKeyFrom(encoded: ByteArray): SecretKey =
            javax.crypto.spec.SecretKeySpec(encoded, "AES")
    }

    companion object {
        private const val MAGIC_LENGTH = 4
        private const val MIN_PAYLOAD_BYTES = 28
        private val DEK_WRAPPING_AAD = "saegeul-vault-data-key-v1".toByteArray(Charsets.US_ASCII)
        private const val MAX_CACHED_WRAPPED_KEYS = 8

        /** 현재 형식: TEE에서 생성한 v2 금고 키로 감싼 DEK. */
        val MAGIC_CURRENT = "SGW2".toByteArray(Charsets.US_ASCII)

        /** 이전 형식(호환 읽기 전용): StrongBox 우선 v1 금고 키로 감싼 DEK. */
        val MAGIC_PREVIOUS = "SGW1".toByteArray(Charsets.US_ASCII)

        private val ENVELOPE_MAGICS = listOf(MAGIC_CURRENT, MAGIC_PREVIOUS)

        /** 봉투 형식 블록 판별. 직접 암호화(구버전) 블록은 false라 상위에서 키별로 분기한다. */
        fun hasEnvelopeHeader(blob: ByteArray): Boolean =
            ENVELOPE_MAGICS.any { magic -> hasHeader(blob, magic) }

        private fun hasHeader(blob: ByteArray, magic: ByteArray): Boolean {
            if (blob.size < magic.size) return false
            for (index in magic.indices) {
                if (blob[index] != magic[index]) return false
            }
            return true
        }
    }
}
