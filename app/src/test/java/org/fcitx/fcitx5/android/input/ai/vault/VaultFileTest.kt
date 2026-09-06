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
