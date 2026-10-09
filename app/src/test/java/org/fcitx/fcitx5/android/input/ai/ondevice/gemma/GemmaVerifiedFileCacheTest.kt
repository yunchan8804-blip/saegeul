/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GemmaVerifiedFileCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cache = GemmaVerifiedFileCache()
    private var verifications = 0

    private fun modelFile(): File = tempFolder.newFile("model.litertlm").apply {
        writeBytes(ByteArray(16) { it.toByte() })
        setLastModified(1_700_000_000_000L)
    }

    private fun verifyOnce(file: File, verify: suspend () -> Unit = { verifications += 1 }) =
        runBlocking { cache.verifyOnce(file, verify) }

    @Test
    fun `the same file is hashed once across repeated verifications`() {
        val file = modelFile()

        verifyOnce(file)
        verifyOnce(file)
        verifyOnce(file)

        assertEquals(1, verifications)
    }

    @Test
    fun `a changed modification time verifies again`() {
        val file = modelFile()
        verifyOnce(file)

        assertTrue(file.setLastModified(1_700_000_005_000L))
        verifyOnce(file)

        assertEquals(2, verifications)
    }

    @Test
    fun `a changed length verifies again`() {
        val file = modelFile()
        verifyOnce(file)

        file.appendBytes(byteArrayOf(1))
        file.setLastModified(1_700_000_000_000L)
        verifyOnce(file)

        assertEquals(2, verifications)
    }

    @Test
    fun `invalidate makes the next verification hash again`() {
        val file = modelFile()
        verifyOnce(file)

        cache.invalidate()
        verifyOnce(file)
        verifyOnce(file)

        assertEquals(2, verifications)
    }

    @Test
    fun `a different path verifies again`() {
        val first = modelFile()
        val second = tempFolder.newFile("other.litertlm").apply {
            writeBytes(ByteArray(16) { it.toByte() })
            setLastModified(1_700_000_000_000L)
        }

        verifyOnce(first)
        verifyOnce(second)

        assertEquals(2, verifications)
    }

    @Test
    fun `a failed verification is never remembered`() {
        val file = modelFile()

        val failure = runCatching {
            verifyOnce(file) {
                verifications += 1
                throw IOException("모델 SHA-256 검증에 실패했습니다.")
            }
        }.exceptionOrNull()
        verifyOnce(file)

        assertTrue(failure is IOException)
        assertEquals(2, verifications)
    }

    @Test
    fun `a cancelled verification is never remembered`() {
        val file = modelFile()

        val failure = runCatching {
            verifyOnce(file) {
                verifications += 1
                throw CancellationException("모델 SHA-256 검증이 취소되었습니다.")
            }
        }.exceptionOrNull()
        verifyOnce(file)

        assertTrue(failure is CancellationException)
        assertEquals(2, verifications)
    }

    @Test
    fun `a file that changes while it is being verified is not remembered`() {
        val file = modelFile()

        verifyOnce(file) {
            verifications += 1
            file.setLastModified(1_700_000_009_000L)
        }
        verifyOnce(file)

        assertEquals(2, verifications)
    }
}
