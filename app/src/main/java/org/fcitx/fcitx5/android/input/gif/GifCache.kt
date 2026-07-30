/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GifCache(private val context: Context) {

    private val directory = File(context.cacheDir, DIRECTORY_NAME)

    suspend fun getOrDownload(result: GifResult): File = withContext(Dispatchers.IO) {
        cleanupExpired()
        check(result.attachmentDownloadAllowed) {
            "Provider media-copy approval is required"
        }
        check(isDeclaredSizeAllowed(result.byteSize)) {
            "GIF size is outside the allowed range"
        }
        val providerDirectory = File(directory, safeProviderId(result.providerId)).apply { mkdirs() }
        val target = File(providerDirectory, "${sha256(result.mediaUrl)}.gif")
        if (target.isFile && target.length() in 1..MAX_BYTES) {
            val bytes = target.readBytes()
            if (GifFileInspector.isAnimated(bytes)) return@withContext target
            target.delete()
        }
        val partial = File(directory, "${target.name}.partial")
        partial.delete()
        try {
            val bytes = download(result.mediaUrl)
            if (!GifFileInspector.isAnimated(bytes)) {
                throw InvalidGifException("Downloaded file is not an animated GIF")
            }
            partial.outputStream().use { it.write(bytes) }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        } catch (exception: Exception) {
            partial.delete()
            throw exception
        }
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { providerDirectory ->
            if (!providerDirectory.isDirectory) {
                providerDirectory.delete()
                return@forEach
            }
            providerDirectory.listFiles()?.forEach { file ->
                if (!file.isFile || file.name.endsWith(".partial") || now - file.lastModified() > TTL_MS) {
                    file.delete()
                }
            }
            if (providerDirectory.listFiles().isNullOrEmpty()) providerDirectory.delete()
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { providerDirectory ->
            providerDirectory.listFiles()?.forEach(File::delete)
            providerDirectory.delete()
        }
    }

    private fun safeProviderId(providerId: String): String = providerId
        .lowercase()
        .map { character -> if (character.isLetterOrDigit() || character == '-') character else '_' }
        .joinToString("")
        .take(48)
        .ifEmpty { "unknown" }

    private fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/gif")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) throw GifNetworkException("GIF download HTTP $status")
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > MAX_BYTES) throw InvalidGifException("GIF exceeds 20 MiB")
            val output = ByteArrayOutputStream(
                contentLength.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: DEFAULT_BUFFER_SIZE
            )
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw InvalidGifException("GIF exceeds 20 MiB")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val DIRECTORY_NAME = "gif-share"
        const val TTL_MS = 24L * 60L * 60L * 1000L
        const val UNKNOWN_SIZE = 0L
        const val MAX_BYTES = 20L * 1024L * 1024L
        internal fun isDeclaredSizeAllowed(size: Long): Boolean =
            size == UNKNOWN_SIZE || size in 1..MAX_BYTES

        private const val USER_AGENT =
            "Saegeul-GifSearch/0.2 (https://github.com/yunchan8804-blip/saegeul)"
    }
}

class InvalidGifException(message: String) : Exception(message)
