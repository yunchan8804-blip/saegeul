/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

/**
 * Unit tests for VaultFile.
 * Verifies the encrypted-at-rest format, legacy plaintext detection/migration, AAD binding,
 * and atomic replace-on-write behavior.
 */
class VaultFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val aad = VaultFile.aadFor("test.dat")

    @Test
    fun testMissingFileReadsNull() {
        val file = File(tempFolder.root, "missing.dat")
        val vaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)

        assertNull(vaultFile.readText())
        assertFalse(vaultFile.exists())
    }

    @Test
    fun testWriteThenReadRoundtripWithMagicHeader() {
        val file = File(tempFolder.root, "vault.dat")
        val vaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)
        val text = "개인화 n-gram 모델 데이터"

        vaultFile.writeText(text)

        val raw = file.readBytes()
        assertEquals('S'.code.toByte(), raw[0])
        assertEquals('G'.code.toByte(), raw[1])
        assertEquals('V'.code.toByte(), raw[2])
        assertEquals('1'.code.toByte(), raw[3])
        assertEquals(text, vaultFile.readText())
    }

    @Test
    fun testLegacyPlaintextDetectedReadAndMigrated() {
        val file = File(tempFolder.root, "legacy.dat")
        val plainText = "레거시 평문 데이터"
        file.writeText(plainText, Charsets.UTF_8)

        val vaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)

        assertTrue(vaultFile.isLegacyPlaintext())
        assertEquals(plainText, vaultFile.readText())

        val migrated = vaultFile.migrateIfLegacy()

        assertTrue(migrated)
        assertFalse(vaultFile.isLegacyPlaintext())
        val raw = file.readBytes()
        assertEquals('S'.code.toByte(), raw[0])
        assertEquals(plainText, vaultFile.readText())
    }

    @Test
    fun testDifferentAadFailsToRead() {
        val file = File(tempFolder.root, "vault-aad.dat")
        val key = AesGcmVaultCipher.randomKey()
        val vaultFile = VaultFile(file, AesGcmVaultCipher(key), VaultFile.aadFor("a.dat"))
        vaultFile.writeText("AAD 바인딩 테스트")

        val otherVaultFile = VaultFile(file, AesGcmVaultCipher(key), VaultFile.aadFor("b.dat"))

        try {
            otherVaultFile.readText()
            fail("Expected a GeneralSecurityException for mismatched AAD")
        } catch (expected: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun testPlainVaultCipherReadsBackWithPlainId() {
        val file = File(tempFolder.root, "plain.dat")
        val vaultFile = VaultFile(file, PlainVaultCipher, aad)
        val text = "평문 cipher로 저장"

        vaultFile.writeText(text)

        val raw = file.readBytes()
        val idLength = raw[4].toInt() and 0xFF
        val id = String(raw.copyOfRange(5, 5 + idLength), Charsets.US_ASCII)
        assertEquals("plain", id)
        assertEquals(text, vaultFile.readText())
    }

    @Test
    fun testReadsPlainCipherFileWithNonPlainVaultCipher() {
        val file = File(tempFolder.root, "cross-cipher.dat")
        val plainVaultFile = VaultFile(file, PlainVaultCipher, aad)
        val text = "다른 cipher로 저장된 평문 헤더 파일"
        plainVaultFile.writeText(text)

        val aesGcmVaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)

        assertEquals(text, aesGcmVaultFile.readText())
    }

    @Test
    fun testMigrateIfLegacyReencryptsFromStoredPlainCipherToWriteCipher() {
        val file = File(tempFolder.root, "migrate-cross-cipher.dat")
        val plainVaultFile = VaultFile(file, PlainVaultCipher, aad)
        val text = "plain에서 aesgcm으로 마이그레이션될 데이터"
        plainVaultFile.writeText(text)

        val aesGcmKey = AesGcmVaultCipher.randomKey()
        val aesGcmVaultFile = VaultFile(file, AesGcmVaultCipher(aesGcmKey), aad)

        val migrated = aesGcmVaultFile.migrateIfLegacy()

        assertTrue(migrated)
        assertEquals(text, aesGcmVaultFile.readText())

        // The file is now stored under the aesgcm id: a fresh PlainVaultCipher VaultFile can no
        // longer decode it (unrecognized cipher id from its point of view).
        try {
            VaultFile(file, PlainVaultCipher, aad).readText()
            fail("Expected a GeneralSecurityException reading an aesgcm-stored file as plain")
        } catch (expected: GeneralSecurityException) {
            // expected
        }

        // But a fresh VaultFile sharing the same aesgcm key reads it back correctly.
        val freshAesGcmVaultFile = VaultFile(file, AesGcmVaultCipher(aesGcmKey), aad)
        assertEquals(text, freshAesGcmVaultFile.readText())
    }

    @Test
    fun testMigrateIfLegacyIsNoOpWhenAlreadyStoredWithCurrentCipher() {
        val file = File(tempFolder.root, "already-current.dat")
        val key = AesGcmVaultCipher.randomKey()
        val vaultFile = VaultFile(file, AesGcmVaultCipher(key), aad)
        vaultFile.writeText("이미 현재 cipher로 저장된 데이터")

        val migrated = vaultFile.migrateIfLegacy()

        assertFalse(migrated)
    }

    @Test
    fun testNoLeftoverTmpFileAfterWrite() {
        val file = File(tempFolder.root, "notmp.dat")
        val vaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)

        vaultFile.writeText("첫 번째 쓰기")
        vaultFile.writeText("두 번째 쓰기, 기존 파일을 교체한다")

        assertFalse(File(tempFolder.root, "notmp.dat.tmp").exists())
        assertTrue(file.exists())
    }

    @Test
    fun testDeleteRemovesFile() {
        val file = File(tempFolder.root, "todelete.dat")
        val vaultFile = VaultFile(file, AesGcmVaultCipher(AesGcmVaultCipher.randomKey()), aad)
        vaultFile.writeText("삭제될 데이터")

        assertTrue(vaultFile.exists())
        vaultFile.delete()

        assertFalse(vaultFile.exists())
    }
}
