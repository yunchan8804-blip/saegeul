/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    val canonicalDest = destDir.canonicalFile.apply { mkdirs() }
    val canonicalDestPrefix = canonicalDest.path + File.separator
    var entry = nextEntry
    while (entry != null) {
        val file = File(canonicalDest, entry.name).canonicalFile
        if (file.path != canonicalDest.path && !file.path.startsWith(canonicalDestPrefix)) {
            throw SecurityException("ZIP entry escapes destination: ${entry.name}")
        }
        if (entry.isDirectory) {
            file.mkdirs()
        } else {
            file.parentFile?.mkdirs()
            file.outputStream().use { output -> copyTo(output) }
        }
        closeEntry()
        entry = nextEntry
    }
    return canonicalDest.listFiles()?.toList() ?: emptyList()
}
