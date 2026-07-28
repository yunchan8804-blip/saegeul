/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

class MeetingAudioException(message: String) : Exception(message)

data class MeetingAudioMetadata(
    val contentType: String,
    val uploadFileName: String,
    val declaredSizeBytes: Long?,
    val durationMillis: Long
)

interface MeetingAudioSource {
    val metadata: MeetingAudioMetadata
    fun openStream(): InputStream
}

object MeetingAudioPolicy {
    /** Kept below OpenAI's current 25 MB upload limit to leave multipart overhead headroom. */
    const val MAX_FILE_BYTES = 24_000_000L
    const val MAX_DURATION_MILLIS = 60L * 60L * 1_000L

    private val typesByExtension = mapOf(
        "flac" to "audio/flac",
        "mp3" to "audio/mpeg",
        "mp4" to "audio/mp4",
        "mpeg" to "audio/mpeg",
        "mpga" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "webm" to "audio/webm"
    )

    private val canonicalTypesByMime = mapOf(
        "audio/flac" to "audio/flac",
        "audio/mpeg" to "audio/mpeg",
        "audio/mp4" to "audio/mp4",
        "audio/ogg" to "audio/ogg",
        "application/ogg" to "audio/ogg",
        "audio/wav" to "audio/wav",
        "audio/x-wav" to "audio/wav",
        "audio/webm" to "audio/webm"
    )

    fun validate(
        mimeType: String?,
        displayName: String?,
        declaredSizeBytes: Long?,
        durationMillis: Long?
    ): MeetingAudioMetadata? {
        val extension = displayName.orEmpty().substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf(typesByExtension::containsKey)
        val extensionType = extension?.let(typesByExtension::getValue)
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val mimeTypeCanonical = normalizedMime?.let(canonicalTypesByMime::get)
        if (mimeTypeCanonical != null && extensionType != null && mimeTypeCanonical != extensionType) {
            return null
        }
        val contentType = when {
            mimeTypeCanonical != null -> mimeTypeCanonical
            extensionType != null -> extensionType
            else -> return null
        }
        val safeExtension = extension ?: when (contentType) {
            "audio/flac" -> "flac"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/wav" -> "wav"
            "audio/webm" -> "webm"
            else -> return null
        }
        if (declaredSizeBytes != null &&
            (declaredSizeBytes <= 0L || declaredSizeBytes > MAX_FILE_BYTES)
        ) return null
        val duration = durationMillis ?: return null
        if (duration <= 0L || duration > MAX_DURATION_MILLIS) return null
        return MeetingAudioMetadata(
            contentType = contentType,
            uploadFileName = "meeting.$safeExtension",
            declaredSizeBytes = declaredSizeBytes,
            durationMillis = duration
        )
    }
}

object ContentUriMeetingAudioSource {
    suspend fun inspect(context: Context, uri: Uri): MeetingAudioSource = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Only a user-selected content URI is accepted" }
        val resolver = context.contentResolver
        var displayName: String? = null
        var declaredSize: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    displayName = cursor.getString(it)
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                    if (!cursor.isNull(it)) {
                        declaredSize = cursor.getLong(it).takeIf { size -> size >= 0L }
                    }
                }
            }
        }
        val duration = runCatching {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
        val inspectedMetadata = MeetingAudioPolicy.validate(
            mimeType = resolver.getType(uri),
            displayName = displayName,
            declaredSizeBytes = declaredSize,
            durationMillis = duration
        ) ?: throw MeetingAudioException("Selected audio is unsupported, too large, or too long")
        object : MeetingAudioSource {
            override val metadata: MeetingAudioMetadata = inspectedMetadata
            override fun openStream(): InputStream = resolver.openInputStream(uri)
                ?: throw MeetingAudioException("Selected audio could not be opened")
        }
    }
}
