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
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VaultFileRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun existingFileUpdateCommitsNewBytesAndCleansBackup() {
        val file = file("update.dat")
        val vault = vault(file)
        vault.writeText("이전 데이터")
        val before = digest(file)

        vault.writeText("새 데이터")

        assertEquals("새 데이터", vault.readText())
        assertFalse(before == digest(file))
        assertFalse(backup(file).exists())
        assertFalse(temp(file).exists())
    }

    @Test
    fun legacyEmptyFileMigratesWithoutLosingItsEmptyPayload() {
        val file = file("legacy-empty.dat")
        file.writeBytes(byteArrayOf())
        val vault = vault(file)

        assertTrue(vault.isLegacyPlaintext())
        assertTrue(vault.migrateIfLegacy())
        assertEquals("", vault.readText())
        assertTrue(file.readBytes().copyOfRange(0, 4).contentEquals(byteArrayOf(83, 71, 86, 49)))
    }

    @Test
    fun temporaryWriteFailurePreservesCommittedBytes() {
        val file = file("write-failure.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("보존할 데이터")
        val before = digest(file)
        storage.failWrite = true

        assertIoFailure { vault.writeText("저장에 실패할 데이터") }

        assertEquals(before, digest(file))
        assertEquals("보존할 데이터", vault.readText())
    }

    @Test
    fun temporarySyncFailurePreservesCommittedBytesAndNeverReadsTmp() {
        val file = file("sync-failure.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("보존할 데이터")
        val before = digest(file)
        storage.failSync = true

        assertIoFailure { vault.writeText("동기화 실패 데이터") }

        assertEquals(before, digest(file))
        assertEquals("보존할 데이터", vault.readText())
        assertTrue(temp(file).exists())
    }

    @Test
    fun baseToBackupRenameFailurePreservesCommittedBytes() {
        val file = file("preserve-failure.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("보존할 데이터")
        val before = digest(file)
        storage.failBaseToBackup = true

        assertIoFailure { vault.writeText("교체할 데이터") }

        assertEquals(before, digest(file))
        assertEquals("보존할 데이터", vault.readText())
    }

    @Test
    fun commitAndRollbackFailureLeavesBackupReadable() {
        val file = file("rollback-failure.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("보존할 데이터")
        val before = digest(file)
        storage.failTmpToBase = true
        storage.failBackupToBase = true

        assertIoFailure { vault.writeText("교체할 데이터") }

        assertFalse(file.exists())
        assertEquals(before, digest(backup(file)))
        assertEquals("보존할 데이터", vault(file, storage).readText())
    }

    @Test
    fun rollbackExceptionDoesNotReplaceTheOriginalCommitFailure() {
        val file = file("rollback-throw.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("보존할 데이터")
        storage.throwTmpToBase = true
        storage.throwBackupToBase = true

        val failure = ioFailure { vault.writeText("교체할 데이터") }

        assertEquals("Failed to commit vault file: ${file.path}", failure.message)
        assertEquals("Injected commit failure", failure.cause?.message)
        assertEquals("Injected rollback failure", failure.suppressed.single().message)
        assertFalse(file.exists())
        assertEquals("보존할 데이터", vault(file, storage).readText())
    }

    @Test
    fun backupOnlyRestartReadsBackupAndFailedRestoreKeepsIt() {
        val file = file("backup-only.dat")
        val initial = vault(file)
        initial.writeText("복구할 데이터")
        assertTrue(file.renameTo(backup(file)))
        assertFalse(file.exists())

        val storage = FaultingStorage(file).apply { failBackupToBase = true }
        val restored = vault(file, storage)
        val before = digest(backup(file))
        assertEquals("복구할 데이터", restored.readText())

        assertIoFailure { restored.writeText("새 데이터") }

        assertFalse(file.exists())
        assertEquals(before, digest(backup(file)))
        assertEquals("복구할 데이터", vault(file).readText())
    }

    @Test
    fun backupOnlyResaveWriteFailureRestoresCommittedBytesToBase() {
        val file = file("backup-only-write-failure.dat")
        vault(file).writeText("복구할 데이터")
        assertTrue(file.renameTo(backup(file)))
        val storage = FaultingStorage(file).apply { failWrite = true }

        assertIoFailure { vault(file, storage).writeText("저장에 실패할 데이터") }

        assertTrue(file.exists())
        assertFalse(backup(file).exists())
        assertEquals("복구할 데이터", vault(file).readText())
    }

    @Test
    fun staleBackupWithBasePrefersBaseAndBlocksNewWriteWhenCleanupFails() {
        val file = file("stale-backup.dat")
        val vault = vault(file)
        vault.writeText("현재 데이터")
        val baseDigest = digest(file)
        val staleFile = file("stale-backup-source.dat")
        vault(staleFile).writeText("이전 데이터")
        backup(file).writeBytes(staleFile.readBytes())
        val storage = FaultingStorage(file).apply { failDeleteBackup = true }

        assertEquals("현재 데이터", vault(file, storage).readText())
        assertIoFailure { vault(file, storage).writeText("새 데이터") }

        assertEquals(baseDigest, digest(file))
        assertTrue(backup(file).exists())
    }

    @Test
    fun committedWriteSucceedsWhenBackupCleanupFails() {
        val file = file("backup-cleanup.dat")
        val storage = FaultingStorage(file)
        val vault = vault(file, storage)
        vault.writeText("이전 데이터")
        storage.throwDeleteBackup = true

        vault.writeText("새 데이터")

        assertEquals("새 데이터", vault.readText())
        assertTrue(backup(file).exists())
        assertEquals("새 데이터", vault(file).readText())
    }

    @Test
    fun deleteKeepsBaseWhenBackupCleanupFailsThenRemovesAllCommittedState() {
        val file = file("delete-backup.dat")
        val vault = vault(file)
        vault.writeText("삭제 전 데이터")
        backup(file).writeBytes(file.readBytes())
        val storage = FaultingStorage(file).apply { throwDeleteBackup = true }
        val faulted = vault(file, storage)

        assertFalse(faulted.delete())
        assertTrue(file.exists())
        assertEquals("삭제 전 데이터", faulted.readText())

        storage.throwDeleteBackup = false
        assertTrue(faulted.delete())
        assertFalse(file.exists())
        assertFalse(backup(file).exists())
        assertFalse(temp(file).exists())
        assertNull(faulted.readText())
    }

    @Test
    fun deleteMissingBaseRetainsItsFalseContract() {
        assertFalse(vault(file("missing-delete.dat")).delete())
    }

    @Test
    fun canonicalPathLockSerializesWritesFromSeparateInstances() {
        val file = file("shared-lock.dat")
        val storage = BlockingStorage()
        val first = vault(file, storage)
        val second = vault(file, storage)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)

        val firstThread = thread {
            try {
                first.writeText("첫 번째 데이터")
            } catch (failure: Throwable) {
                firstFailure.set(failure)
            }
        }
        lateinit var secondThread: Thread
        try {
            assertTrue(storage.firstWriteEntered.await(2, TimeUnit.SECONDS))
            secondThread = thread {
                try {
                    secondStarted.countDown()
                    second.writeText("두 번째 데이터")
                    secondFinished.countDown()
                } catch (failure: Throwable) {
                    secondFailure.set(failure)
                }
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(storage.secondWriteEntered.await(150, TimeUnit.MILLISECONDS))
            assertFalse(secondFinished.await(150, TimeUnit.MILLISECONDS))
        } finally {
            storage.allowFirstWrite.countDown()
        }
        firstThread.join(2_000)
        secondThread.join(2_000)
        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals(null, firstFailure.get())
        assertEquals(null, secondFailure.get())
        assertEquals("두 번째 데이터", vault(file).readText())
    }

    private fun file(name: String) = File(tempFolder.root, name)

    private fun temp(file: File) = File(file.parentFile, "${file.name}.tmp")

    private fun backup(file: File) = File(file.parentFile, "${file.name}.bak")

    private fun vault(file: File, storage: VaultFileStorage = DefaultVaultFileStorage) =
        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name), storage)

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun assertIoFailure(block: () -> Unit) {
        ioFailure(block)
    }

    private fun ioFailure(block: () -> Unit): IOException {
        try {
            block()
            fail("Expected IOException")
            throw AssertionError("Unreachable")
        } catch (failure: IOException) {
            return failure
        }
    }

    private class FaultingStorage(private val base: File) : VaultFileStorage {
        var failWrite = false
        var failSync = false
        var failBaseToBackup = false
        var failTmpToBase = false
        var failBackupToBase = false
        var throwTmpToBase = false
        var throwBackupToBase = false
        var failDeleteBackup = false
        var throwDeleteBackup = false

        private val tmp = File(base.parentFile, "${base.name}.tmp")
        private val backup = File(base.parentFile, "${base.name}.bak")

        override fun exists(file: File): Boolean = file.exists()

        override fun readBytes(file: File): ByteArray = file.readBytes()

        override fun writeBytesAndSync(file: File, bytes: ByteArray) {
            if (failWrite) throw IOException("Injected temporary write failure")
            FileOutputStream(file).use { output ->
                output.write(bytes)
                if (failSync) throw IOException("Injected temporary sync failure")
                output.fd.sync()
            }
        }

        override fun rename(source: File, target: File): Boolean = when {
            source == base && target == backup && failBaseToBackup -> false
            source == tmp && target == base && throwTmpToBase -> throw IOException("Injected commit failure")
            source == tmp && target == base && failTmpToBase -> false
            source == backup && target == base && throwBackupToBase -> throw IOException("Injected rollback failure")
            source == backup && target == base && failBackupToBase -> false
            else -> source.renameTo(target)
        }

        override fun delete(file: File): Boolean = when {
            file == backup && throwDeleteBackup -> throw IOException("Injected backup delete failure")
            file == backup && failDeleteBackup -> false
            else -> file.delete()
        }
    }

    private class BlockingStorage : VaultFileStorage {
        val firstWriteEntered = CountDownLatch(1)
        val allowFirstWrite = CountDownLatch(1)
        val secondWriteEntered = CountDownLatch(1)
        private var shouldBlock = true

        override fun exists(file: File): Boolean = file.exists()

        override fun readBytes(file: File): ByteArray = file.readBytes()

        override fun writeBytesAndSync(file: File, bytes: ByteArray) {
            if (shouldBlock) {
                shouldBlock = false
                firstWriteEntered.countDown()
                if (!allowFirstWrite.await(2, TimeUnit.SECONDS)) {
                    throw AssertionError("First write was not released")
                }
            } else {
                secondWriteEntered.countDown()
            }
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }

        override fun rename(source: File, target: File): Boolean = source.renameTo(target)

        override fun delete(file: File): Boolean = file.delete()
    }
}
