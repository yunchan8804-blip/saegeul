/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.sentencepack

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class SentencePackStatus(
    val builtinCount: Int = 0,
    val installedCount: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val revision: Long = 0
)

class SentencePackRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val networkAllowed: () -> Boolean
) {
    private val operationMutex = Mutex()
    private val state = MutableStateFlow(SentencePackStatus())
    private val storage = SentencePackStorage(File(context.noBackupFilesDir, "sentence-packs"))

    @Volatile
    private var snapshot = Snapshot(null, null)

    @Volatile
    private var prepareStarted = false

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeConnection: HttpsURLConnection? = null

    @Volatile
    var revision: Long = 0
        private set

    val status: StateFlow<SentencePackStatus> = state.asStateFlow()

    fun prepare() {
        synchronized(this) {
            if (prepareStarted) return
            prepareStarted = true
        }
        scope.launch {
            state.update { it.copy(isLoading = true, error = null) }
            try {
                val builtin = withContext(Dispatchers.IO) {
                    context.assets.open(BUILTIN_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.mapNotNull(SentencePackText::normalizeAccepted).toList()
                    }
                }
                val builtinIndex = withContext(Dispatchers.Default) { SentencePackIndex.build(builtin) }
                operationMutex.withLock {
                    publishLocked(builtinIndex, snapshot.installed, null)
                    when (val installed = withContext(Dispatchers.IO) { storage.readInstalled() }) {
                        is SentencePackStorage.ReadResult.Valid -> publishLocked(builtinIndex, installed.index, null)
                        SentencePackStorage.ReadResult.Absent -> Unit
                        SentencePackStorage.ReadResult.Invalid -> publishLocked(builtinIndex, null, "선택 예문 팩을 확인할 수 없습니다.")
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                operationMutex.withLock {
                    publishLocked(snapshot.builtin, snapshot.installed, "기본 예문 팩을 불러오지 못했습니다.")
                }
            } finally {
                state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun complete(context: String, limit: Int): List<SentencePackMatch> {
        if (limit <= 0) return emptyList()
        val current = snapshot
        return mergeSentencePackMatches(
            builtin = current.builtin?.complete(context, limit).orEmpty(),
            installed = current.installed?.complete(context, limit).orEmpty(),
            limit = limit
        )
    }

    fun download() {
        synchronized(this) {
            if (downloadJob?.isActive == true) return
            downloadJob = scope.launch(Dispatchers.IO) {
                operationMutex.withLock {
                    downloadLocked()
                }
            }
        }
    }

    fun cancelDownload() {
        val connectionToCancel = activeConnection
        downloadJob?.cancel()
        if (connectionToCancel != null) {
            scope.launch(Dispatchers.IO) {
                connectionToCancel.disconnect()
            }
        }
    }

    fun removeDownloaded() {
        cancelDownload()
        scope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                try {
                    storage.remove()
                    publishLocked(snapshot.builtin, null, null)
                } catch (_: Exception) {
                    state.update { it.copy(error = "선택 예문 팩을 삭제하지 못했습니다.") }
                }
            }
        }
    }

    private suspend fun downloadLocked() {
        state.update {
            it.copy(
                downloadedBytes = 0,
                totalBytes = SentencePackCatalog.DOWNLOAD_BYTES,
                isDownloading = true,
                error = null
            )
        }
        try {
            if (!networkAllowed()) throw SentencePackDownloadException("네트워크 사용이 허용되지 않았습니다.")
            val downloaded = fetchToTemporaryFile()
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) throw SentencePackDownloadException("네트워크 사용이 허용되지 않았습니다.")
            val index = storage.verifyAndBuild(downloaded)
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) throw SentencePackDownloadException("네트워크 사용이 허용되지 않았습니다.")
            storage.commit(downloaded)
            publishLocked(snapshot.builtin, index, null)
        } catch (exception: CancellationException) {
            cleanupTemporaryAfterDownload()?.let { error -> state.update { it.copy(error = error) } }
            throw exception
        } catch (exception: SentencePackDownloadException) {
            val error = cleanupTemporaryAfterDownload() ?: exception.message
            state.update { it.copy(error = error) }
        } catch (_: Exception) {
            val error = cleanupTemporaryAfterDownload() ?: "선택 예문 팩을 내려받지 못했습니다."
            state.update { it.copy(error = error) }
        } finally {
            state.update { it.copy(isDownloading = false) }
        }
    }

    private suspend fun fetchToTemporaryFile(): File {
        val url = URL(SentencePackCatalog.SOURCE_URL)
        if (url.protocol != "https" || url.host != "raw.githubusercontent.com") {
            throw SentencePackDownloadException("허용되지 않은 내려받기 주소입니다.")
        }
        val connection = (url.openConnection() as? HttpsURLConnection)
            ?: throw SentencePackDownloadException("보안 연결을 만들지 못했습니다.")
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Cookie", "")
        connection.setRequestProperty("Authorization", "")
        activeConnection = connection
        try {
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) throw SentencePackDownloadException("네트워크 사용이 허용되지 않았습니다.")
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw SentencePackDownloadException("내려받기 응답이 올바르지 않습니다.")
            if (connection.contentLengthLong >= 0 && connection.contentLengthLong != SentencePackCatalog.DOWNLOAD_BYTES) {
                throw SentencePackDownloadException("내려받기 크기가 올바르지 않습니다.")
            }
            val temporary = storage.temporaryFile()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!networkAllowed()) throw SentencePackDownloadException("네트워크 사용이 허용되지 않았습니다.")
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesRead += read
                        if (bytesRead > SentencePackCatalog.DOWNLOAD_BYTES) throw SentencePackDownloadException("내려받기 크기가 올바르지 않습니다.")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        state.update { it.copy(downloadedBytes = bytesRead) }
                    }
                    output.fd.sync()
                }
            }
            if (bytesRead != SentencePackCatalog.DOWNLOAD_BYTES || digest.hex() != SentencePackCatalog.SHA256) {
                throw SentencePackDownloadException("내려받기 검증에 실패했습니다.")
            }
            return temporary
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun publishLocked(builtin: SentencePackIndex?, installed: SentencePackIndex?, error: String?) {
        snapshot = Snapshot(builtin, installed)
        revision++
        state.update {
            it.copy(
                builtinCount = builtin?.sentenceCount ?: 0,
                installedCount = installed?.sentenceCount ?: 0,
                error = error,
                revision = revision
            )
        }
    }

    private fun cleanupTemporaryAfterDownload(): String? = try {
        storage.deleteTemporary()
        null
    } catch (_: Exception) {
        "임시 파일을 삭제하지 못했습니다."
    }

    private data class Snapshot(
        val builtin: SentencePackIndex?,
        val installed: SentencePackIndex?
    )

    private companion object {
        const val BUILTIN_ASSET = "sentence-packs/ko-basic-v1.txt"
        const val TIMEOUT_MILLIS = 30_000
        const val BUFFER_SIZE = 8 * 1024
    }
}

internal fun mergeSentencePackMatches(
    builtin: List<SentencePackMatch>,
    installed: List<SentencePackMatch>,
    limit: Int
): List<SentencePackMatch> {
    if (limit <= 0) return emptyList()
    val allMatches = builtin + installed
    val strongestMatchedTokens = allMatches.maxOfOrNull(SentencePackMatch::matchedTokens) ?: return emptyList()
    val strongestMatches = allMatches.filter { it.matchedTokens == strongestMatchedTokens }
    val strongestEvidence = strongestMatches.minOf { it.evidence.sortOrder }
    return strongestMatches
        .asSequence()
        .filter { it.evidence.sortOrder == strongestEvidence }
        .distinctBy { it.suffix to it.joinMode }
        .take(limit)
        .toList()
}

private val MatchEvidence.sortOrder: Int
    get() = when (this) {
        MatchEvidence.PREFIX -> 0
        MatchEvidence.CONTEXT_SUFFIX -> 1
        MatchEvidence.LAST_WORD -> 2
    }

private class SentencePackStorage(private val directory: File) {
    private val downloadedFile = File(directory, "ko-selected-v1.csv")
    private val temporaryFile = File(directory, "ko-selected-v1.csv.new")

    fun readInstalled(): ReadResult {
        if (!downloadedFile.isFile) return ReadResult.Absent
        return try {
            ReadResult.Valid(verifyAndBuild(downloadedFile))
        } catch (_: Exception) {
            ReadResult.Invalid
        }
    }

    fun temporaryFile(): File {
        if (!directory.exists() && !directory.mkdirs()) throw SentencePackDownloadException("저장 공간을 만들지 못했습니다.")
        deleteTemporary()
        return temporaryFile
    }

    fun verifyAndBuild(file: File): SentencePackIndex {
        if (file.length() != SentencePackCatalog.DOWNLOAD_BYTES || file.sha256() != SentencePackCatalog.SHA256) {
            throw SentencePackDownloadException("내려받기 검증에 실패했습니다.")
        }
        val text = file.readText(Charsets.UTF_8)
        return SentencePackIndex.build(SentencePackCsv.parseSelected(text))
    }

    fun commit(temporary: File) {
        try {
            Os.rename(temporary.absolutePath, downloadedFile.absolutePath)
        } catch (exception: Exception) {
            throw SentencePackDownloadException("선택 예문 팩을 저장하지 못했습니다.").also { it.initCause(exception) }
        }
    }

    fun remove() {
        deleteTemporary()
        if (downloadedFile.exists() && !downloadedFile.delete()) throw SentencePackDownloadException("선택 예문 팩을 삭제하지 못했습니다.")
    }

    fun deleteTemporary() {
        if (temporaryFile.exists() && !temporaryFile.delete()) throw SentencePackDownloadException("임시 파일을 삭제하지 못했습니다.")
    }

    sealed class ReadResult {
        data class Valid(val index: SentencePackIndex) : ReadResult()
        data object Absent : ReadResult()
        data object Invalid : ReadResult()
    }
}

private fun File.sha256(): String = FileInputStream(this).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.hex()
}

private fun MessageDigest.hex(): String = digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private class SentencePackDownloadException(message: String) : IllegalStateException(message)
