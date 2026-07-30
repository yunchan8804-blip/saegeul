/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

data class OcrModelDescriptor(
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String
) {
    fun validate(): OcrModelDescriptor {
        val uri = URI(downloadUrl)
        require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com")
        require(uri.query == null && uri.fragment == null && uri.userInfo == null)
        require(sizeBytes in 1..OcrModelManager.MAX_MODEL_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        return this
    }
}

data class OcrModelResponse(
    val input: InputStream,
    val declaredSizeBytes: Long?
)

fun interface OcrModelTransport {
    fun open(descriptor: OcrModelDescriptor): OcrModelResponse

    fun cancel() = Unit
}

class HttpsOcrModelTransport : OcrModelTransport {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var activeInput: InputStream? = null

    override fun open(descriptor: OcrModelDescriptor): OcrModelResponse {
        val validated = descriptor.validate()
        val connection = URI(validated.downloadUrl).toURL().openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw OcrModelException("OCR model download failed")
            }
            val declaredSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }.takeIf { it >= 0L }
            if (declaredSize != null && declaredSize != validated.sizeBytes) {
                throw OcrModelException("OCR model size does not match")
            }
            val input = connection.inputStream
            activeInput = input
            return OcrModelResponse(object : FilterInputStream(input) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        activeInput = null
                        activeConnection = null
                        connection.disconnect()
                    }
                }
            }, declaredSize)
        } catch (exception: Exception) {
            connection.disconnect()
            activeConnection = null
            throw exception
        }
    }

    override fun cancel() {
        runCatching { activeInput?.close() }
        activeInput = null
        activeConnection?.disconnect()
        activeConnection = null
    }

    private companion object {
        const val USER_AGENT =
            "Saegeul-KoreanOCR/0.1 (https://github.com/yunchan8804-blip/saegeul)"
    }
}

class OcrModelException(message: String) : Exception(message)

class OcrModelManager(
    context: Context,
    private val transport: OcrModelTransport = HttpsOcrModelTransport()
) {
    private val modelRoot = File(context.noBackupFilesDir, "ocr/tesseract")
    val dataPath: String get() = modelRoot.absolutePath

    suspend fun hasValidModel(): Boolean = withContext(Dispatchers.IO) {
        val model = modelFile()
        model.isFile && model.length() == KOREAN_BEST_MODEL.sizeBytes &&
            sha256(model) == KOREAN_BEST_MODEL.sha256
    }

    suspend fun install(): Unit = withContext(Dispatchers.IO) {
        installModel(modelRoot, KOREAN_BEST_MODEL, transport)
    }

    fun cancel() {
        transport.cancel()
    }

    private fun modelFile(): File = File(File(modelRoot, "tessdata"), MODEL_FILE_NAME)

    companion object {
        const val MAX_MODEL_BYTES = 16_000_000L
        const val MODEL_FILE_NAME = "kor.traineddata"

        val KOREAN_BEST_MODEL = OcrModelDescriptor(
            downloadUrl = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/" +
                "e12c65a915945e4c28e237a9b52bc4a8f39a0cec/kor.traineddata",
            sizeBytes = 12_528_128L,
            sha256 = "f888d4038348a0c3d25151e7f452bda0d74ca275b18cab146798bcbb94084fff"
        )

        internal fun installModel(
            modelRoot: File,
            descriptor: OcrModelDescriptor,
            transport: OcrModelTransport
        ) {
            val validated = descriptor.validate()
            val tessdata = File(modelRoot, "tessdata")
            if (!tessdata.isDirectory && !tessdata.mkdirs()) {
                throw OcrModelException("OCR model directory could not be created")
            }
            val destination = File(tessdata, MODEL_FILE_NAME)
            val pending = File(tessdata, "$MODEL_FILE_NAME.new").apply { delete() }
            val backup = File(tessdata, "$MODEL_FILE_NAME.old")
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            if (destination.exists()) backup.delete()
            try {
                val response = transport.open(validated)
                response.input.use { input ->
                    if (response.declaredSizeBytes != null &&
                        response.declaredSizeBytes != validated.sizeBytes
                    ) throw OcrModelException("OCR model size does not match")
                    FileOutputStream(pending).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        try {
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > validated.sizeBytes || total > MAX_MODEL_BYTES) {
                                    throw OcrModelException("OCR model is larger than declared")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                            if (total != validated.sizeBytes || digest.hex() != validated.sha256) {
                                throw OcrModelException("OCR model integrity check failed")
                            }
                            output.fd.sync()
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
                if (destination.exists() && !destination.renameTo(backup)) {
                    throw OcrModelException("Previous OCR model could not be replaced")
                }
                if (!pending.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    throw OcrModelException("OCR model could not be installed")
                }
                backup.delete()
            } finally {
                pending.delete()
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
            return digest.hex()
        }

        private fun MessageDigest.hex(): String = digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
