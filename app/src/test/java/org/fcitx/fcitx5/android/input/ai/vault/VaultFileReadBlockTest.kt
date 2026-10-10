/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

class VaultFileReadBlockTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val retryRule = ImmediateReadRetryRule()

    private val aad = VaultFile.aadFor("vault.dat")

    private fun vault(name: String = "vault.dat") = File(tempFolder.root, name)

    private fun unreadableCopies(base: File) =
        base.parentFile!!.listFiles { _, name -> name.startsWith("${base.name}.unreadable-") }.orEmpty().toList()

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals("vault file is read-blocked", expected.message)
        }
    }

    private fun assertThrowsSecurity(block: () -> Unit) {
        try {
            block()
            fail("expected GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun failedReadBlocksWritesAndDeleteAndKeepsEveryByte() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val before = file.readBytes()
        val reopened = VaultFile(file, SwitchableVaultCipher(failReads = true), aad)

        assertThrowsSecurity { reopened.readText() }
        assertThrowsIo { reopened.writeText("replacement") }
        assertThrowsIo { reopened.delete() }

        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".bak").exists())
        assertEquals("1", File(file.path + ".readfail").readText())
    }

    @Test
    fun blockAppliesToEveryVaultFileOnTheSamePath() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val before = file.readBytes()

        assertThrowsSecurity { VaultFile(file, SwitchableVaultCipher(failReads = true), aad).readText() }
        assertThrowsIo { VaultFile(file, SwitchableVaultCipher(), aad).writeText("replacement") }

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun keyMismatchedEnvelopeFileIsNeverOverwritten() {
        val file = vault()
        val writer = EnvelopeVaultCipher(wrapping = AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        VaultFile(file, writer, aad).writeText("encrypted with key A")
        val before = file.readBytes()
        val otherKey = EnvelopeVaultCipher(wrapping = AesGcmVaultCipher(AesGcmVaultCipher.randomKey()))
        val reopened = VaultFile(file, otherKey, aad)

        assertThrowsSecurity { reopened.readTextAndMigrate() }
        assertThrowsIo { reopened.writeText("new data") }

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun thirdConsecutiveFailurePreservesFileAndBackupAndStartsFresh() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val backup = File(file.path + ".bak")
        file.copyTo(backup)
        val originalBytes = file.readBytes()
        val failing = SwitchableVaultCipher(failReads = true)

        assertThrowsSecurity { VaultFile(file, failing, aad).readText() }
        assertThrowsSecurity { VaultFile(file, failing, aad).readText() }
        val third = VaultFile(file, failing, aad)

        assertNull(third.readText())

        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(File(file.path + ".readfail").exists())
        val preservedBase = unreadableCopies(file)
        assertEquals(1, preservedBase.size)
        assertArrayEquals(originalBytes, preservedBase.single().readBytes())
        val stamp = preservedBase.single().name.removePrefix("${file.name}.unreadable-")
        val preservedBackup = File(tempFolder.root, "${backup.name}.unreadable-$stamp")
        assertArrayEquals(originalBytes, preservedBackup.readBytes())

        third.writeText("fresh start")
        assertEquals("fresh start", VaultFile(file, SwitchableVaultCipher(), aad).readText())
        assertEquals(1, unreadableCopies(file).size)
    }

    @Test
    fun successfulReadAfterTwoFailuresRestoresDataAndRemovesCounter() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val failing = SwitchableVaultCipher(failReads = true)
        assertThrowsSecurity { VaultFile(file, failing, aad).readText() }
        assertThrowsSecurity { VaultFile(file, failing, aad).readText() }
        assertEquals("2", File(file.path + ".readfail").readText())

        val healthy = VaultFile(file, SwitchableVaultCipher(), aad)

        assertEquals("original", healthy.readText())
        assertFalse(File(file.path + ".readfail").exists())
        assertTrue(unreadableCopies(file).isEmpty())
        healthy.writeText("updated")
        assertEquals("updated", healthy.readText())
    }

    @Test
    fun afterTheFirstFailureReadsAreRefusedWithoutDecryptingUntilTheRetryTimeHasPassed() {
        var now = 1_000L
        VaultReadBlocks.clock = { now }
        VaultReadBlocks.retryIntervalMs = 300_000L
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val failing = SwitchableVaultCipher(failReads = true)
        val reopened = VaultFile(file, failing, aad)

        assertThrowsSecurity { reopened.readText() }
        assertEquals(1, failing.decryptCalls.get())
        assertThrowsSecurity { reopened.readText() }
        assertEquals(2, failing.decryptCalls.get())
        assertEquals("2", File(file.path + ".readfail").readText())

        assertThrowsIo { reopened.readText() }
        assertThrowsIo { reopened.readTextAndMigrate() }
        assertEquals(2, failing.decryptCalls.get())
        assertEquals("2", File(file.path + ".readfail").readText())

        now += 300_000L
        assertNull(reopened.readText())
        assertEquals(3, failing.decryptCalls.get())
        assertEquals(1, unreadableCopies(file).size)
    }

    @Test
    fun aDifferentCipherMayReadWhileTheFailedOneIsWaiting() {
        VaultReadBlocks.clock = { 1_000L }
        VaultReadBlocks.retryIntervalMs = 300_000L
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val failing = VaultFile(file, SwitchableVaultCipher(failReads = true), aad)
        assertThrowsSecurity { failing.readText() }
        assertThrowsSecurity { failing.readText() }
        assertThrowsIo { failing.readText() }

        assertEquals("original", VaultFile(file, SwitchableVaultCipher(), aad).readText())
        assertFalse(File(file.path + ".readfail").exists())
    }

    @Test
    fun parseFailuresReportedByTheStoreCountTowardPreservation() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("not what the store expects")
        val originalBytes = file.readBytes()

        val first = VaultFile(file, SwitchableVaultCipher(), aad)
        assertEquals("not what the store expects", first.readText())
        assertFalse(first.markUnreadable())
        assertThrowsIo { first.writeText("replacement") }
        assertArrayEquals(originalBytes, file.readBytes())
        assertEquals("1", File(file.path + ".readfail").readText())

        val second = VaultFile(file, SwitchableVaultCipher(), aad)
        assertEquals("not what the store expects", second.readText())
        assertFalse(second.markUnreadable())
        assertEquals("2", File(file.path + ".readfail").readText())

        val third = VaultFile(file, SwitchableVaultCipher(), aad)
        assertEquals("not what the store expects", third.readText())
        assertTrue(third.markUnreadable())

        assertFalse(file.exists())
        assertFalse(File(file.path + ".readfail").exists())
        assertArrayEquals(originalBytes, unreadableCopies(file).single().readBytes())
        third.writeText("fresh start")
        assertEquals("fresh start", third.readText())
    }

    @Test
    fun deleteForUserRemovesEverythingEvenWhileBlocked() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("old data")
        val failing = SwitchableVaultCipher(failReads = true)
        repeat(3) { runCatching { VaultFile(file, failing, aad).readText() } }
        assertEquals(1, unreadableCopies(file).size)
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("newer data")
        file.copyTo(File(file.path + ".bak"))
        val blocked = VaultFile(file, failing, aad)
        assertThrowsSecurity { blocked.readText() }
        assertThrowsIo { blocked.delete() }
        assertTrue(File(file.path + ".readfail").exists())

        assertTrue(blocked.deleteForUser())

        assertTrue("left: ${tempFolder.root.list()?.toList()}", tempFolder.root.list().isNullOrEmpty())
        blocked.writeText("after wipe")
        assertEquals("after wipe", VaultFile(file, SwitchableVaultCipher(), aad).readText())
    }

    @Test
    fun releaseLiftsTheBlockAndRemovesTheCounterWithoutTouchingTheFile() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val failing = VaultFile(file, SwitchableVaultCipher(failReads = true), aad)
        assertThrowsSecurity { failing.readText() }
        assertTrue(VaultReadBlocks.isBlocked(file))

        VaultReadBlocks.release(file)

        assertFalse(VaultReadBlocks.isBlocked(file))
        assertFalse(File(file.path + ".readfail").exists())
        failing.writeText("replaced on purpose")
        assertEquals("replaced on purpose", VaultFile(file, SwitchableVaultCipher(), aad).readText())
    }

    @Test
    fun missingFileIsNotBlocked() {
        val file = vault()
        val vaultFile = VaultFile(file, SwitchableVaultCipher(failReads = true), aad)

        assertNull(vaultFile.readText())
        vaultFile.writeText("first data")

        assertEquals("first data", VaultFile(file, SwitchableVaultCipher(), aad).readText())
    }

    @Test
    fun staleBlockIsReleasedWhenTheFileIsGone() {
        val file = vault()
        VaultFile(file, SwitchableVaultCipher(), aad).writeText("original")
        val failing = VaultFile(file, SwitchableVaultCipher(failReads = true), aad)
        assertThrowsSecurity { failing.readText() }
        assertTrue(file.delete())

        assertNull(failing.readText())
        failing.writeText("fresh")

        assertEquals("fresh", VaultFile(file, SwitchableVaultCipher(), aad).readText())
        assertFalse(File(file.path + ".readfail").exists())
    }
}
