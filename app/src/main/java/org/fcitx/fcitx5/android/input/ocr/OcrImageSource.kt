/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

class OcrImageException(message: String) : Exception(message)

data class OcrImageMetadata(
    val mimeType: String,
    val declaredSizeBytes: Long?
)

interface OcrImageSource {
    val metadata: OcrImageMetadata
    suspend fun decode(): Bitmap
}

internal data class OcrImageTransform(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false
)

internal object OcrExifOrientation {
    fun transformFor(orientation: Int): OcrImageTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OcrImageTransform(flipHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_180 -> OcrImageTransform(rotationDegrees = 180)
        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            OcrImageTransform(rotationDegrees = 180, flipHorizontal = true)
        ExifInterface.ORIENTATION_TRANSPOSE ->
            OcrImageTransform(rotationDegrees = 90, flipHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_90 -> OcrImageTransform(rotationDegrees = 90)
        ExifInterface.ORIENTATION_TRANSVERSE ->
            OcrImageTransform(rotationDegrees = -90, flipHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_270 -> OcrImageTransform(rotationDegrees = -90)
        else -> OcrImageTransform()
    }

    fun apply(bitmap: Bitmap, orientation: Int): Bitmap {
        val transform = transformFor(orientation)
        if (transform == OcrImageTransform()) return bitmap
        val matrix = Matrix().apply {
            if (transform.rotationDegrees != 0) {
                setRotate(transform.rotationDegrees.toFloat())
            }
            if (transform.flipHorizontal) {
                postScale(-1f, 1f)
            }
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (transformed !== bitmap && !bitmap.isRecycled) {
            if (bitmap.isMutable) bitmap.eraseColor(0)
            bitmap.recycle()
        }
        return transformed
    }
}

object OcrImagePolicy {
    const val MAX_ENCODED_BYTES = 15_000_000L
    const val MAX_SOURCE_PIXELS = 100_000_000L
    const val MAX_SOURCE_DIMENSION = 20_000
    const val MAX_DECODED_PIXELS = 4_194_304L
    const val MAX_DECODED_DIMENSION = 2_560

    private val supportedMimeTypes = setOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    )

    fun validateMetadata(mimeType: String?, declaredSizeBytes: Long?): OcrImageMetadata? {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (normalized !in supportedMimeTypes) return null
        if (declaredSizeBytes != null &&
            (declaredSizeBytes <= 0L || declaredSizeBytes > MAX_ENCODED_BYTES)
        ) return null
        return OcrImageMetadata(normalized, declaredSizeBytes)
    }

    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION ||
            height > MAX_SOURCE_DIMENSION || width.toLong() * height > MAX_SOURCE_PIXELS
        ) return null
        var sample = 1
        while (width / sample > MAX_DECODED_DIMENSION ||
            height / sample > MAX_DECODED_DIMENSION ||
            (width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }
}

object ContentUriOcrImageSource {
    suspend fun inspect(context: Context, uri: Uri): OcrImageSource = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Only a user-selected content URI is accepted" }
        val resolver = context.contentResolver
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                declaredSize = cursor.getLong(0).takeIf { it >= 0L }
            }
        }
        val inspectedMetadata = OcrImagePolicy.validateMetadata(resolver.getType(uri), declaredSize)
            ?: throw OcrImageException("Selected image type or size is unsupported")
        object : OcrImageSource {
            override val metadata = inspectedMetadata

            override suspend fun decode(): Bitmap = withContext(Dispatchers.IO) {
                val orientation = try {
                    openBounded(resolver.openInputStream(uri)).use { input ->
                        ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }
                } catch (_: IOException) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openBounded(resolver.openInputStream(uri))
                    .use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = OcrImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                    ?: throw OcrImageException("Selected image dimensions are unsupported")
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val decoded = openBounded(resolver.openInputStream(uri))
                    .use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw OcrImageException("Selected image could not be decoded")
                OcrExifOrientation.apply(decoded, orientation)
            }
        }
    }

    private fun openBounded(input: InputStream?): InputStream = object : FilterInputStream(
        input ?: throw OcrImageException("Selected image could not be opened")
    ) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read)
            return read
        }

        private fun account(read: Int) {
            total += read
            if (total > OcrImagePolicy.MAX_ENCODED_BYTES) {
                throw OcrImageException("Selected image exceeds the size limit")
            }
        }
    }
}
