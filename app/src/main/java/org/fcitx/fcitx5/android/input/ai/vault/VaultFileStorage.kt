/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal interface VaultFileStorage {
    fun exists(file: File): Boolean
    fun readBytes(file: File): ByteArray
    fun writeBytesAndSync(file: File, bytes: ByteArray)
    fun rename(source: File, target: File): Boolean
    fun delete(file: File): Boolean
    fun lastModified(file: File): Long = file.lastModified()
    fun length(file: File): Long = file.length()
}

internal object DefaultVaultFileStorage : VaultFileStorage {
    override fun exists(file: File): Boolean = file.exists()

    override fun readBytes(file: File): ByteArray = file.readBytes()

    override fun writeBytesAndSync(file: File, bytes: ByteArray) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create vault directory: ${parent.path}")
        }
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    override fun rename(source: File, target: File): Boolean = source.renameTo(target)

    override fun delete(file: File): Boolean = file.delete()
}

internal object VaultFilePathLocks {
    private class LockState {
        val lock = Any()
        var revision = 0L
    }

    private val locks = ConcurrentHashMap<String, LockState>()

    fun <T> withLock(canonicalPath: String, block: () -> T): T {
        val state = locks[canonicalPath] ?: LockState().let { created ->
            locks.putIfAbsent(canonicalPath, created) ?: created
        }
        return synchronized(state.lock, block)
    }

    fun revision(canonicalPath: String): Long = withLock(canonicalPath) {
        locks[canonicalPath]!!.revision
    }

    fun incrementRevision(canonicalPath: String) {
        withLock(canonicalPath) {
            locks[canonicalPath]!!.revision += 1L
        }
    }
}
