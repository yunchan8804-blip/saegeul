/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import java.io.File
import java.io.FileOutputStream

internal data class EncryptedProfilePayload(
    val iv: ByteArray,
    val payload: ByteArray
)

/** Test seam for encrypted no-backup profiles; production implementations use Android Keystore. */
internal interface EncryptedProfileCipher {
    fun encrypt(plaintext: ByteArray): EncryptedProfilePayload

    fun decrypt(encrypted: EncryptedProfilePayload): ByteArray

    fun clear()
}

/**
 * File-only atomic replacement shared by credential stores and local JVM tests.
 * The previous ciphertext remains recoverable until the replacement is fully synced and renamed.
 */
internal class EncryptedProfileStorage(private val file: File) {
    fun read(maxBytes: Long): ByteArray? {
        val source = readableSource() ?: return null
        require(source.length() in 1..maxBytes) { "Invalid encrypted profile file size" }
        return source.readBytes()
    }

    fun exists(): Boolean = file.isFile || backupFile().isFile

    fun write(bytes: ByteArray) {
        readableSource()
        file.parentFile?.mkdirs()
        val pending = pendingFile().apply { delete() }
        val backup = backupFile().apply { delete() }
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the previous encrypted profile")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store the encrypted profile")
            }
            backup.delete()
        } catch (error: Throwable) {
            pending.delete()
            if (!file.exists() && backup.isFile) backup.renameTo(file)
            throw error
        }
    }

    fun delete() {
        listOf(file, pendingFile(), backupFile()).forEach(File::delete)
    }

    private fun readableSource(): File? {
        if (file.isFile) return file
        val backup = backupFile().takeIf(File::isFile) ?: return null
        file.parentFile?.mkdirs()
        return if (backup.renameTo(file)) file else backup
    }

    private fun pendingFile(): File = File(file.parentFile, "${file.name}.new")

    private fun backupFile(): File = File(file.parentFile, "${file.name}.bak")
}
