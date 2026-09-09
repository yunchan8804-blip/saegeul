/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackRepository
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SentencePackDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun offlineBuiltinPackCompletesExpectedSuffixAndReportsQueryCost() {
        withTestStorage { context ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = SentencePackRepository(context, scope) { false }
            try {
                val prepared = prepare(repository)
                assertEquals(BUILTIN_SENTENCE_COUNT, prepared.builtinCount)
                assertEquals(0, prepared.installedCount)

                assertBuiltinCompletion(repository)
                val startedAt = SystemClock.elapsedRealtimeNanos()
                repeat(QUERY_ITERATIONS) {
                    assertBuiltinCompletion(repository)
                }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                reportQueryCost(prepared.builtinCount, elapsedMs)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun fixedPublicPackDownloadsThenRemovesWithoutAffectingBuiltinPack() {
        withTestStorage { context ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = SentencePackRepository(context, scope) { true }
            try {
                val prepared = prepare(repository)
                assertEquals(BUILTIN_SENTENCE_COUNT, prepared.builtinCount)
                assertEquals(0, prepared.installedCount)
                val revisionAfterPrepare = repository.revision

                repository.download()
                val downloaded = waitForDownloaded(repository, revisionAfterPrepare)
                assertEquals(BUILTIN_SENTENCE_COUNT, downloaded.builtinCount)
                assertTrue("A downloaded pack must contain sentences.", downloaded.installedCount > 0)
                assertBuiltinCompletion(repository)
                reportInstalledPackCoverage(repository)

                verifyNetworkDeniedDownloadPreservesInstalledPack(context, downloaded.installedCount)

                val revisionAfterDownload = repository.revision
                repository.removeDownloaded()
                val removed = waitForRemoval(repository, revisionAfterDownload)
                assertEquals(BUILTIN_SENTENCE_COUNT, removed.builtinCount)
                assertEquals(0, removed.installedCount)
                assertBuiltinCompletion(repository)
                reportDownloadCycle(
                    installedCount = downloaded.installedCount,
                    revision = repository.revision
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private fun verifyNetworkDeniedDownloadPreservesInstalledPack(context: Context, expectedInstalledCount: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = SentencePackRepository(context, scope) { false }
        try {
            val prepared = prepare(repository)
            assertEquals(expectedInstalledCount, prepared.installedCount)
            repository.download()
            val denied = waitForDownloadToEnd(repository)
            assertEquals(expectedInstalledCount, denied.installedCount)
            assertEquals("네트워크 사용이 허용되지 않았습니다.", denied.error)
            assertBuiltinCompletion(repository)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun corruptedInstalledPackIsRemovedAndNextRepositoryKeepsBuiltinOnly() {
        withTestStorage { context ->
            val installedFile = File(context.noBackupFilesDir, DOWNLOADED_PACK_PATH)
            assertTrue("test sentence-pack storage directory was not created", requireNotNull(installedFile.parentFile).mkdirs())
            installedFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02))
            assertTrue("test corrupted sentence pack was not written", installedFile.isFile)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = SentencePackRepository(context, scope) { false }
            try {
                val corrupted = waitForCorruptedPackError(repository)
                assertEquals(BUILTIN_SENTENCE_COUNT, corrupted.builtinCount)
                assertEquals(0, corrupted.installedCount)
                assertBuiltinCompletion(repository)

                val revisionBeforeRemoval = repository.revision
                repository.removeDownloaded()
                val removed = waitForRemoval(repository, revisionBeforeRemoval, corrupted.error)
                assertEquals(BUILTIN_SENTENCE_COUNT, removed.builtinCount)
                assertEquals(0, removed.installedCount)
                assertEquals(null, removed.error)
                assertTrue("The corrupted sentence pack was not removed.", !installedFile.exists())
                assertBuiltinCompletion(repository)
            } finally {
                scope.cancel()
            }

            val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val restartedRepository = SentencePackRepository(context, restartedScope) { false }
            try {
                val restarted = prepare(restartedRepository)
                assertEquals(BUILTIN_SENTENCE_COUNT, restarted.builtinCount)
                assertEquals(0, restarted.installedCount)
                assertBuiltinCompletion(restartedRepository)
            } finally {
                restartedScope.cancel()
            }
        }
    }

    private fun prepare(repository: SentencePackRepository): SentencePackStatus {
        repository.prepare()
        val deadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = repository.status.value
            failForError(status)
            if (status.builtinCount == BUILTIN_SENTENCE_COUNT && !status.isLoading) return status
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The built-in sentence pack did not become ready within $PREPARE_TIMEOUT_MS ms.")
    }

    private fun waitForDownloaded(repository: SentencePackRepository, previousRevision: Long): SentencePackStatus {
        val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = repository.status.value
            failForError(status)
            if (
                !status.isDownloading &&
                status.installedCount > 0 &&
                repository.revision > previousRevision
            ) {
                return status
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The fixed public sentence pack did not download within $DOWNLOAD_TIMEOUT_MS ms.")
    }

    private fun waitForDownloadToEnd(repository: SentencePackRepository): SentencePackStatus {
        val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = repository.status.value
            if (!status.isDownloading && status.error != null) {
                return status
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The network-denied sentence-pack download did not end within $DOWNLOAD_TIMEOUT_MS ms.")
    }

    private fun waitForCorruptedPackError(repository: SentencePackRepository): SentencePackStatus {
        repository.prepare()
        val deadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = repository.status.value
            if (!status.isLoading && status.error != null) return status
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The corrupted sentence pack did not expose a prepare error within $PREPARE_TIMEOUT_MS ms.")
    }

    private fun waitForRemoval(
        repository: SentencePackRepository,
        previousRevision: Long,
        previousError: String? = null
    ): SentencePackStatus {
        val deadline = SystemClock.elapsedRealtime() + REMOVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = repository.status.value
            status.error?.let { error ->
                if (error != previousError) {
                    throw AssertionError("Sentence-pack operation failed: $error")
                }
            }
            if (
                status.installedCount == 0 &&
                repository.revision > previousRevision &&
                !status.isLoading &&
                status.error == null
            ) {
                return status
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The downloaded sentence pack was not removed within $REMOVE_TIMEOUT_MS ms.")
    }

    private fun assertBuiltinCompletion(repository: SentencePackRepository) {
        val match = repository.complete(BUILTIN_QUERY, QUERY_LIMIT).firstOrNull()
        assertNotNull("The built-in sentence pack must complete the fixed query.", match)
        requireNotNull(match).also {
            assertEquals(BUILTIN_SUFFIX, it.suffix)
            assertEquals(ContextualAppend.JoinMode.NEXT_WORD, it.joinMode)
            assertEquals(BUILTIN_MATCHED_TOKENS, it.matchedTokens)
        }
    }

    private fun failForError(status: SentencePackStatus) {
        status.error?.let { error -> throw AssertionError("Sentence-pack operation failed: $error") }
    }

    private fun reportQueryCost(builtinCount: Int, elapsedMs: Long) {
        Log.i(
            LOG_TAG,
            "builtinCount=$builtinCount queryIterations=$QUERY_ITERATIONS queryElapsedMs=$elapsedMs"
        )
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("builtinCount", builtinCount)
                putInt("queryIterations", QUERY_ITERATIONS)
                putLong("queryElapsedMs", elapsedMs)
            }
        )
    }

    private fun reportDownloadCycle(
        installedCount: Int,
        revision: Long
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("installedCount", installedCount)
                putLong("revision", revision)
            }
        )
    }

    private fun reportInstalledPackCoverage(repository: SentencePackRepository) {
        val observations = ArrayList<String>(COVERAGE_QUERIES.size)
        COVERAGE_QUERIES.forEach { query ->
            val matches = repository.complete(query, COVERAGE_RESULT_LIMIT)
            val suffixes = matches.joinToString(",") { it.suffix }
            val joinModes = matches.joinToString(",") { it.joinMode.name }
            val observation = "query=$query suffix=$suffixes joinMode=$joinModes count=${matches.size}"
            Log.i(LOG_TAG, observation)
            observations += observation
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("coverageQueryCount", COVERAGE_QUERIES.size)
                putStringArrayList("coverageObservations", observations)
            }
        )
    }

    private fun withTestStorage(block: (Context) -> Unit) {
        val targetContext = instrumentation.targetContext
        val temporaryRoot = File(targetContext.cacheDir, "sentence-pack-device-test-${UUID.randomUUID()}")
        assertTrue("test sentence-pack directory was not created", temporaryRoot.mkdirs())
        try {
            block(SentencePackTestContext(targetContext, temporaryRoot))
        } finally {
            deleteTemporaryRoot(targetContext.cacheDir, temporaryRoot)
        }
    }

    private fun deleteTemporaryRoot(cacheDirectory: File, temporaryRoot: File) {
        val canonicalCacheDirectory = cacheDirectory.canonicalFile
        val canonicalTemporaryRoot = temporaryRoot.canonicalFile
        check(canonicalTemporaryRoot.parentFile == canonicalCacheDirectory) {
            "test sentence-pack directory is outside cacheDir"
        }
        assertTrue("test sentence-pack directory was not removed", temporaryRoot.deleteRecursively())
    }

    private class SentencePackTestContext(
        base: Context,
        private val storageRoot: File
    ) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = storageRoot
    }

    private companion object {
        const val LOG_TAG = "SentencePackDeviceTest"
        const val BUILTIN_SENTENCE_COUNT = 216
        const val BUILTIN_QUERY = "오늘 회의 "
        const val BUILTIN_SUFFIX = "끝나고 다시 연락드릴게요."
        const val BUILTIN_MATCHED_TOKENS = 2
        const val DOWNLOADED_PACK_PATH = "sentence-packs/ko-selected-v1.csv"
        const val QUERY_LIMIT = 3
        const val QUERY_ITERATIONS = 200
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PREPARE_TIMEOUT_MS = 15_000L
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val REMOVE_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 50L
        const val COVERAGE_RESULT_LIMIT = 2

        val COVERAGE_QUERIES = listOf(
            "약속을",
            "약속을 ",
            "내일 회의",
            "내일 회의 ",
            "회의 자료를",
            "점심 뭐",
            "저녁 먹",
            "문의드립니다",
            "친구야 오늘",
            "친구야 오늘 ",
            "안녕하세요",
            "안녕하세요 ",
            "오늘 날씨가",
            "나는 지금",
            "나는 지금 ",
            "이전 문장입니다. 다음",
            "이전 문장입니다. 다음 ",
            "미완성어절",
            "도착했",
            "도착했 ",
            "업무 공유",
            "업무 공유 ",
            "주말에 뭐",
            "확인 부탁",
            "확인 부탁 ",
            "잘 지내",
            "지금 뭐해",
            "지금 뭐해 ",
            "오늘 저녁",
            "오늘 저녁에는",
            "회의 자료를 ",
            "나는 지금 ",
            "오늘 저녁 "
        )
    }
}
