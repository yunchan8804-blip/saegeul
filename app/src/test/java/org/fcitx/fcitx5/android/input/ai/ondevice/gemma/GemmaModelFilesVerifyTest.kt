/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [GemmaModelFiles.verify] checks the file's exact size before hashing anything, so a file that
 * isn't [GemmaModelFiles.MODEL_BYTES] long fails fast here without needing a multi-gigabyte fixture -
 * this is exactly the shape of a genuinely corrupt/truncated `.part` file. See
 * [GemmaModelDownloadDecisionsTest] for the download worker's own hash/size-mismatch-vs-retryable
 * classification.
 */
class GemmaModelFilesVerifyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun rejectsAFileWhoseSizeDoesNotMatchTheFixedModelSize() = runBlocking {
        val file = tempFolder.newFile("model.litertlm.part")
        file.writeBytes(ByteArray(1024))
        val error = runCatching { GemmaModelFiles.verify(file) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message?.contains("크기가 고정 크기와 다릅니다") == true)
    }

    @Test
    fun aFullSizePartWithTheWrongHashGoesToVerificationAndFailsAsAVerifyFailure() = runBlocking {
        val part = tempFolder.newFile("model.litertlm.part")
        RandomAccessFile(part, "rw").use { it.setLength(GemmaModelFiles.MODEL_BYTES) }
        val model = File(tempFolder.root, "model.litertlm")

        assertEquals(
            GemmaPartDisposition.VERIFY_COMPLETE,
            decidePartDisposition(part.length(), GemmaModelFiles.MODEL_BYTES)
        )
        val error = runCatching { GemmaModelFiles.verifyAndInstallPart(part, model) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(
            GemmaDownloadFailureClassification.VERIFY_FAILED,
            classifyDownloadFailure(error?.message)
        )
        assertTrue(part.exists())
        assertFalse(model.exists())
    }
}
