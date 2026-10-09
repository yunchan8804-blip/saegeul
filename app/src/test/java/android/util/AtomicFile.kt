/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * JVM stand-in for the Android framework class, which the unit-test android.jar only stubs.
 * It follows the framework's legacy `.bak` behavior: a leftover backup is restored over the base
 * file before every read or write.
 */
class AtomicFile(private val baseName: File) {
    private val backupName = File(baseName.path + ".bak")

    fun startWrite(): FileOutputStream {
        restoreBackup()
        if (baseName.exists() && !baseName.renameTo(backupName)) {
            throw IOException("Couldn't rename $baseName to $backupName")
        }
        return FileOutputStream(baseName)
    }

    fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        backupName.delete()
    }

    fun failWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.close()
        baseName.delete()
        backupName.renameTo(baseName)
    }

    fun readFully(): ByteArray {
        restoreBackup()
        FileInputStream(baseName).use { return it.readBytes() }
    }

    private fun restoreBackup() {
        if (backupName.exists()) {
            baseName.delete()
            backupName.renameTo(baseName)
        }
    }
}
