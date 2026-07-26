/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class OcrModelManagerTest {
    private val bytes = "hello".toByteArray()
    private val descriptor = OcrModelDescriptor(
        downloadUrl = "https://raw.githubusercontent.com/owner/repository/commit/kor.traineddata",
        sizeBytes = bytes.size.toLong(),
        sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    )

    @Test
    fun `verified model installs atomically under tessdata`() {
        withTempDirectory { root ->
            OcrModelManager.installModel(root, descriptor) {
                OcrModelResponse(ByteArrayInputStream(bytes), bytes.size.toLong())
            }

            assertArrayEquals(
                bytes,
                File(File(root, "tessdata"), OcrModelManager.MODEL_FILE_NAME).readBytes()
            )
            assertFalse(File(File(root, "tessdata"), "${OcrModelManager.MODEL_FILE_NAME}.new").exists())
        }
    }

    @Test
    fun `hash mismatch leaves an existing model untouched`() {
        withTempDirectory { root ->
            val tessdata = File(root, "tessdata").apply { mkdirs() }
            val destination = File(tessdata, OcrModelManager.MODEL_FILE_NAME).apply {
                writeText("existing")
            }
            val failure = runCatching {
                OcrModelManager.installModel(root, descriptor) {
                    OcrModelResponse(ByteArrayInputStream("wrong".toByteArray()), 5L)
                }
            }.exceptionOrNull()

            assertTrue(failure is OcrModelException)
            assertArrayEquals("existing".toByteArray(), destination.readBytes())
            assertFalse(File(tessdata, "${OcrModelManager.MODEL_FILE_NAME}.new").exists())
        }
    }

    @Test
    fun `model source contract rejects non official hosts`() {
        val failure = runCatching {
            descriptor.copy(downloadUrl = "https://example.test/kor.traineddata").validate()
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `interrupted replacement restores the previous verified file`() {
        withTempDirectory { root ->
            val tessdata = File(root, "tessdata").apply { mkdirs() }
            val backup = File(tessdata, "${OcrModelManager.MODEL_FILE_NAME}.old").apply {
                writeText("existing")
            }

            runCatching {
                OcrModelManager.installModel(root, descriptor) {
                    OcrModelResponse(ByteArrayInputStream("wrong".toByteArray()), 5L)
                }
            }

            assertFalse(backup.exists())
            assertArrayEquals(
                "existing".toByteArray(),
                File(tessdata, OcrModelManager.MODEL_FILE_NAME).readBytes()
            )
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ocr-model-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
