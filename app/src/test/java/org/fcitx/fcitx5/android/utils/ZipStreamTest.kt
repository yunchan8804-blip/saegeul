/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipStreamTest {
    @Test
    fun extractsNestedFileWhenArchiveOmitsDirectoryEntries() {
        val destination = Files.createTempDirectory("zip-stream-test").toFile()
        try {
            ZipInputStream(ByteArrayInputStream(archive("nested/file.txt", "content"))).use {
                it.extract(destination)
            }

            assertEquals("content", destination.resolve("nested/file.txt").readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun rejectsEntryOutsideDestination() {
        val destination = Files.createTempDirectory("zip-stream-test").toFile()
        try {
            assertThrows(SecurityException::class.java) {
                ZipInputStream(ByteArrayInputStream(archive("../outside.txt", "content"))).use {
                    it.extract(destination)
                }
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    private fun archive(name: String, content: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
}
