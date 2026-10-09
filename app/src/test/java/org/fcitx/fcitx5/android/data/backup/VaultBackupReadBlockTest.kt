/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaProfile
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.SwitchableVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A vault import replaces unreadable files with good ones, so their read blocks must not outlive it. */
class VaultBackupReadBlockTest {

    @get:Rule
    val filesDir = TemporaryFolder()

    @get:Rule
    val noBackupFilesDir = TemporaryFolder()

    private val corrections: File get() = File(filesDir.root, "personal_corrections.json")

    private fun vaultFile(name: String, cipher: org.fcitx.fcitx5.android.input.ai.vault.VaultCipher = PlainVaultCipher) =
        VaultFile(File(filesDir.root, name), cipher, VaultFile.aadFor(name))

    private fun export(): ByteArray {
        val out = ByteArrayOutputStream()
        val result = runBlocking {
            VaultBackup.exportInternal(
                filesDir = filesDir.root,
                noBackupFilesDir = noBackupFilesDir.root,
                appVersionName = "9.9.9-test",
                cipher = PlainVaultCipher,
                out = out,
                password = "test-password-123".toCharArray(),
                iterations = 4
            )
        }
        assertTrue("export should succeed: $result", result is ExportResult.Success)
        return out.toByteArray()
    }

    private fun import(encrypted: ByteArray, markLevelAlreadyRewarded: (Int) -> Unit = {}): ImportResult = runBlocking {
        VaultBackup.importInternal(
            filesDir = filesDir.root,
            noBackupFilesDir = noBackupFilesDir.root,
            cipher = PlainVaultCipher,
            markLevelAlreadyRewarded = markLevelAlreadyRewarded,
            input = ByteArrayInputStream(encrypted),
            password = "test-password-123".toCharArray()
        )
    }

    /** Replaces the device file with one this device's cipher cannot read, and lets the read fail once. */
    private fun makeCorrectionsUnreadable() {
        vaultFile("personal_corrections.json", SwitchableVaultCipher()).writeText("""{"pairs":["unreadable"]}""")
        try {
            vaultFile("personal_corrections.json").readText()
            fail("expected the file to be unreadable")
        } catch (_: java.security.GeneralSecurityException) {
        }
        try {
            vaultFile("personal_corrections.json").writeText("blocked")
            fail("expected the write to be refused")
        } catch (expected: IOException) {
            assertEquals("vault file is read-blocked", expected.message)
        }
    }

    @Test
    fun savingWorksAfterAnImportReplacesABlockedFile() {
        vaultFile("personal_corrections.json").writeText("""{"pairs":[]}""")
        val encrypted = export()
        makeCorrectionsUnreadable()

        val result = import(encrypted)

        assertTrue("import should succeed: $result", result is ImportResult.Success)
        val reopened = vaultFile("personal_corrections.json")
        assertEquals("""{"pairs":[]}""", reopened.readText())
        reopened.writeText("""{"pairs":["after import"]}""")
        assertEquals("""{"pairs":["after import"]}""", vaultFile("personal_corrections.json").readText())
        assertTrue(!File(corrections.path + ".readfail").exists())
    }

    @Test
    fun aRolledBackImportKeepsTheOriginalFileProtected() {
        vaultFile("personal_corrections.json").writeText("""{"pairs":[]}""")
        vaultFile("typing_dna.json").writeText(TypingDnaProfile(updatedAt = 1L, totalAnalyzedSentences = 5).toJson())
        val encrypted = export()
        makeCorrectionsUnreadable()
        val original = corrections.readBytes()

        val result = import(encrypted, markLevelAlreadyRewarded = { throw IllegalStateException("level lock failed") })

        assertTrue("import should fail: $result", result is ImportResult.Failure)
        assertArrayEquals(original, corrections.readBytes())
        try {
            vaultFile("personal_corrections.json", SwitchableVaultCipher()).writeText("overwritten")
            fail("the restored unreadable file must stay write-protected")
        } catch (expected: IOException) {
            assertEquals("vault file is read-blocked", expected.message)
        }
        assertArrayEquals(original, corrections.readBytes())
    }
}
