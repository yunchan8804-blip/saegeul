/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TypingDnaStagingLatencyDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun stagingWritesOnMainThreadPersistAcrossVaultRecreation() {
        val targetContext = instrumentation.targetContext
        val temporaryRoot = File(
            targetContext.cacheDir,
            "typing-dna-staging-latency-${UUID.randomUUID()}"
        )
        assertTrue("test staging directory was not created", temporaryRoot.mkdirs())
        val stagingFile = File(temporaryRoot, "typing_dna_pending.json")
        val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "typing-dna-staging-latency-io")
        }

        try {
            val app = FcitxApplication.getInstance()
            val vault = createVault(ioExecutor, stagingFile, app)
            val durationsMs = ArrayList<Long>(SYNTHETIC_SENTENCES.size)
            var processedCount = 0

            SYNTHETIC_SENTENCES.forEach { sentence ->
                val measurement = onMain {
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    val batchDispatched = vault.recordSentence(SYNTHETIC_WORK_PACKAGE, sentence)
                    RecordMeasurement(
                        durationMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND,
                        batchDispatched = batchDispatched
                    )
                }
                durationsMs += measurement.durationMs
                assertFalse("staging sample must not reach the batch threshold", measurement.batchDispatched)
                processedCount += 1
            }

            assertEquals(SYNTHETIC_SENTENCES.size, snapshotCount(vault))
            assertTrue("test staging file was not written", stagingFile.isFile)

            val restoredVault = createVault(ioExecutor, stagingFile, app)
            assertEquals(SYNTHETIC_SENTENCES.size, snapshotCount(restoredVault))

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("recordDurationsMs", durationsMs.joinToString(prefix = "[", postfix = "]"))
                    putLong("maxDurationMs", durationsMs.maxOrNull() ?: 0L)
                    putLong("totalDurationMs", durationsMs.sum())
                    putInt("processedCount", processedCount)
                    putBoolean("stagingFileExists", stagingFile.isFile)
                }
            )
        } finally {
            ioExecutor.shutdownNow()
            deleteTemporaryRoot(targetContext.cacheDir, temporaryRoot)
        }
    }

    private fun createVault(
        ioExecutor: ExecutorService,
        stagingFile: File,
        app: FcitxApplication
    ): TypingDnaVault = ioExecutor.submit<TypingDnaVault> {
        TypingDnaVault(
            thresholdPerCategory = BATCH_THRESHOLD,
            stagingFile = stagingFile,
            cipher = app.vaultCipher
        )
    }.get(VAULT_CREATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private fun snapshotCount(vault: TypingDnaVault): Int = vault.snapshot().values.sumOf { it.size }

    private data class RecordMeasurement(
        val durationMs: Long,
        val batchDispatched: Boolean
    )

    private fun deleteTemporaryRoot(cacheDirectory: File, temporaryRoot: File) {
        val canonicalCacheDirectory = cacheDirectory.canonicalFile
        val canonicalTemporaryRoot = temporaryRoot.canonicalFile
        check(canonicalTemporaryRoot.parentFile == canonicalCacheDirectory) {
            "test temporary directory is outside cacheDir"
        }
        assertTrue("test temporary directory was not removed", temporaryRoot.deleteRecursively())
    }

    private companion object {
        const val BATCH_THRESHOLD = 100
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val VAULT_CREATION_TIMEOUT_SECONDS = 45L
        const val SYNTHETIC_WORK_PACKAGE = "org.fcitx.fcitx5.android.stagingwork"

        val SYNTHETIC_SENTENCES = listOf(
            "합성 업무 기록 첫째 안건을 확인합니다.",
            "합성 업무 기록 둘째 일정을 조정합니다.",
            "합성 업무 기록 셋째 자료를 정리합니다.",
            "합성 업무 기록 넷째 검토를 요청합니다.",
            "합성 업무 기록 다섯째 결과를 공유합니다.",
            "합성 업무 기록 여섯째 계획을 갱신합니다.",
            "합성 업무 기록 일곱째 회의를 준비합니다.",
            "합성 업무 기록 여덟째 작업을 완료합니다.",
            "합성 업무 기록 아홉째 확인을 남깁니다.",
            "합성 업무 기록 열째 보고를 마칩니다."
        )
    }
}
