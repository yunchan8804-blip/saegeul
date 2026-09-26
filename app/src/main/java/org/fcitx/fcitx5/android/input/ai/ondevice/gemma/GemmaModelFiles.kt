/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

object GemmaModelFiles {

    private val transferMutex = Mutex()

    const val MODEL_ID = "litert-community/gemma-4-E2B-it-litert-lm"
    const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val MODEL_BYTES = 2_588_147_712L
    const val REQUIRED_FREE_BYTES = MODEL_BYTES + 64L * 1024L * 1024L

    /**
     * Pinned to the exact commit whose `gemma-4-E2B-it.litertlm` blob matches [MODEL_SHA256] and
     * [MODEL_BYTES] (verified against the Hugging Face API's tree listing for this commit), rather
     * than `resolve/main`, so the repo owner republishing `main` under the same filename can never
     * silently change what this fixed URL serves.
     */
    const val MODEL_COMMIT = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$MODEL_COMMIT/" +
            "gemma-4-E2B-it.litertlm"

    data class ModelTransferResult(
        val modelFile: File,
        val reusedVerifiedModel: Boolean
    )

    /** Runs [block] under the transfer lock also held by [importFrom]/[deleteModel]/[requireVerifiedModel], so the resumable download worker never races a manual import or delete. */
    internal suspend fun <T> withTransferLock(block: suspend () -> T): T = transferMutex.withLock { block() }

    suspend fun importFrom(
        context: Context,
        uri: Uri,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit
    ): ModelTransferResult = transferMutex.withLock {
        withContext(Dispatchers.IO) {
        requireSupportedAbi()
        existingVerifiedModel(context)?.let { return@withContext ModelTransferResult(it, true) }
        requireFreeSpace(context)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("선택한 모델 파일을 열 수 없습니다.")
        input.use { stream ->
            writeVerifiedPart(context, stream, checkOfflineMode = false, onProgress)
        }
        }
    }

    suspend fun requireVerifiedModel(
        context: Context,
        isCancelled: () -> Boolean = { false }
    ): File = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            requireSupportedAbi()
            val model = modelFile(context)
            if (!model.isFile) throw IOException("검증된 Gemma 모델이 없습니다. 다운로드하거나 가져오세요.")
            verify(model, isCancelled)
            model
        }
    }

    suspend fun deleteModel(context: Context) = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            val model = modelFile(context)
            val part = partFile(context)
            if (model.exists() && !model.delete()) throw IOException("Gemma 모델을 삭제할 수 없습니다.")
            if (part.exists() && !part.delete()) throw IOException("Gemma 부분 모델 파일을 삭제할 수 없습니다.")
        }
    }

    fun modelFile(context: Context): File = File(context.noBackupFilesDir, "gemma/model.litertlm")

    fun partFile(context: Context): File = File(requireNotNull(modelFile(context).parentFile), "model.litertlm.part")

    private suspend fun writeVerifiedPart(
        context: Context,
        input: java.io.InputStream,
        checkOfflineMode: Boolean,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit
    ): ModelTransferResult {
        val model = modelFile(context)
        val directory = requireNotNull(model.parentFile)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Gemma 모델 저장 폴더를 만들 수 없습니다.")
        }
        val part = partFile(context)
        if (part.exists() && !part.delete()) throw IOException("이전 모델 부분 파일을 지울 수 없습니다.")
        var completed = false
        try {
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                var received = 0L
                var lastReportedBytes = 0L
                var lastReportedAt = SystemClock.elapsedRealtime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (checkOfflineMode) requireNetworkAllowed()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > MODEL_BYTES) {
                        throw IOException("모델 파일이 허용된 고정 크기를 초과했습니다.")
                    }
                    output.write(buffer, 0, count)
                    val now = SystemClock.elapsedRealtime()
                    if (
                        received - lastReportedBytes >= PROGRESS_BYTES_INTERVAL ||
                        now - lastReportedAt >= PROGRESS_TIME_INTERVAL_MS
                    ) {
                        onProgress(received, MODEL_BYTES)
                        lastReportedBytes = received
                        lastReportedAt = now
                    }
                }
                output.fd.sync()
                if (received != lastReportedBytes) onProgress(received, MODEL_BYTES)
            }
            verifyAndInstallPart(part, model)
            completed = true
            return ModelTransferResult(model, false)
        } finally {
            if (!completed && part.exists()) part.delete()
        }
    }

    /**
     * Checks [part] is exactly [MODEL_BYTES] and hashes to [MODEL_SHA256], then atomically renames
     * it to [model]. Shared by [writeVerifiedPart] (import) and [GemmaModelDownloadWorker] (resumable
     * download), which both hand this function a complete, on-disk part file. Never deletes [part]
     * itself on failure; callers decide what a failed verification means for the part file (import
     * discards it, the resumable download keeps it retryable unless the hash itself is wrong).
     */
    internal suspend fun verifyAndInstallPart(
        part: File,
        model: File,
        isCancelled: () -> Boolean = { false }
    ) {
        verify(part, isCancelled)
        Os.rename(part.absolutePath, model.absolutePath)
    }

    private suspend fun existingVerifiedModel(context: Context): File? {
        val model = modelFile(context)
        if (!model.isFile) return null
        verify(model)
        return model
    }

    internal suspend fun verify(
        file: File,
        isCancelled: () -> Boolean = { false }
    ) {
        if (file.length() != MODEL_BYTES) {
            throw IOException("모델 파일 크기가 고정 크기와 다릅니다: ${file.length()}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (isCancelled()) throw CancellationException("모델 SHA-256 검증이 취소되었습니다.")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != MODEL_SHA256) throw IOException("모델 SHA-256 검증에 실패했습니다.")
    }

    private fun requireFreeSpace(context: Context) {
        val available = StatFs(context.noBackupFilesDir.absolutePath).availableBytes
        if (available < REQUIRED_FREE_BYTES) {
            throw IOException("모델 다운로드·가져오기에 ${REQUIRED_FREE_BYTES}바이트의 여유 공간이 필요합니다.")
        }
    }

    private fun requireNetworkAllowed() {
        if (AppPrefs.getInstance().advanced.offlineMode.getValue()) {
            throw IOException("완전 오프라인 모드에서는 모델을 다운로드할 수 없습니다.")
        }
    }

    private fun requireSupportedAbi() {
        if (!OnDeviceAiSupport.isSupported) {
            throw IOException("이 기능은 arm64-v8a 또는 x86_64 ABI에서만 지원합니다.")
        }
    }

    private const val TRANSFER_BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_BYTES_INTERVAL = 1L * 1024L * 1024L
    private const val PROGRESS_TIME_INTERVAL_MS = 250L
}
