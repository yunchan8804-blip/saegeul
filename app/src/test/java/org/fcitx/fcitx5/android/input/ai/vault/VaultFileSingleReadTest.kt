/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VaultFileSingleReadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun missingFileReturnsNull() {
        val file = file("missing.dat")

        assertEquals(null, VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name)).readTextAndMigrate())
    }

    @Test
    fun currentCipherReadsAndDecryptsOnceWithoutEncrypting() {
        val file = file("current.dat")
        val aes = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val aad = VaultFile.aadFor(file.name)
        VaultFile(file, aes, aad).writeText("현재 암호화 데이터")
        val storage = CountingStorage()
        val cipher = CountingCipher(aes)

        assertEquals("현재 암호화 데이터", VaultFile(file, cipher, aad, storage).readTextAndMigrate())

        assertEquals(1, storage.readCount)
        assertEquals(1, cipher.decryptCount)
        assertEquals(0, cipher.encryptCount)
    }

    @Test
    fun headerlessPlaintextMigratesAfterOneRead() {
        val file = file("headerless.dat")
        val text = "헤더 없는 레거시 데이터"
        file.writeText(text, Charsets.UTF_8)
        val aes = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val storage = CountingStorage()
        val cipher = CountingCipher(aes)

        assertEquals(text, VaultFile(file, cipher, VaultFile.aadFor(file.name), storage).readTextAndMigrate())

        assertEquals(1, storage.readCount)
        assertEquals(1, cipher.encryptCount)
        assertEquals(0, cipher.decryptCount)
        assertEquals(text, VaultFile(file, aes, VaultFile.aadFor(file.name)).readText())
    }

    @Test
    fun plainHeaderMigratesAfterOneRead() {
        val file = file("plain-header.dat")
        val text = "plain 헤더 레거시 데이터"
        val aad = VaultFile.aadFor(file.name)
        VaultFile(file, PlainVaultCipher, aad).writeText(text)
        val aes = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val storage = CountingStorage()
        val cipher = CountingCipher(aes)

        assertEquals(text, VaultFile(file, cipher, aad, storage).readTextAndMigrate())

        assertEquals(1, storage.readCount)
        assertEquals(1, cipher.encryptCount)
        assertEquals(0, cipher.decryptCount)
        assertEquals(text, VaultFile(file, aes, aad).readText())
    }

    @Test
    fun backupOnlyIsReadAndTemporaryFileIsIgnored() {
        val file = file("backup-only.dat")
        val aad = VaultFile.aadFor(file.name)
        val aes = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        VaultFile(file, aes, aad).writeText("백업의 committed 데이터")
        val backup = File(file.parentFile, "${file.name}.bak")
        assertTrue(file.renameTo(backup))
        File(file.parentFile, "${file.name}.tmp").writeText("읽으면 안 되는 임시 데이터")

        assertEquals("백업의 committed 데이터", VaultFile(file, aes, aad).readTextAndMigrate())
        assertFalse(file.exists())
        assertTrue(backup.exists())
    }

    @Test
    fun migrationCommitFailurePreservesPreviousBytesAndPropagatesFailure() {
        val file = file("migration-failure.dat")
        val previous = "레거시 데이터"
        file.writeText(previous, Charsets.UTF_8)
        val before = file.readBytes()
        val storage = FailingCommitStorage(file)
        val vault = VaultFile(
            file,
            AesGcmVaultCipher(AesGcmVaultCipher.randomKey()),
            VaultFile.aadFor(file.name),
            storage
        )

        try {
            vault.readTextAndMigrate()
            fail("Expected IOException")
        } catch (_: IOException) {
        }

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(previous, VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name)).readText())
    }

    private fun file(name: String) = File(tempFolder.root, name)

    private class CountingCipher(private val delegate: VaultCipher) : VaultCipher {
        override val id: String = delegate.id
        var encryptCount = 0
        var decryptCount = 0

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            encryptCount += 1
            return delegate.encrypt(plain, aad)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            decryptCount += 1
            return delegate.decrypt(blob, aad)
        }
    }

    private class CountingStorage : VaultFileStorage {
        var readCount = 0

        override fun exists(file: File): Boolean = file.exists()

        override fun readBytes(file: File): ByteArray {
            readCount += 1
            return file.readBytes()
        }

        override fun writeBytesAndSync(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }

        override fun rename(source: File, target: File): Boolean = source.renameTo(target)

        override fun delete(file: File): Boolean = file.delete()
    }

    private class FailingCommitStorage(private val base: File) : VaultFileStorage {
        private val tmp = File(base.parentFile, "${base.name}.tmp")

        override fun exists(file: File): Boolean = file.exists()

        override fun readBytes(file: File): ByteArray = file.readBytes()

        override fun writeBytesAndSync(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }

        override fun rename(source: File, target: File): Boolean = when {
            source == tmp && target == base -> false
            else -> source.renameTo(target)
        }

        override fun delete(file: File): Boolean = file.delete()
    }
}
