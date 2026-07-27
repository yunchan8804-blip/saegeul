/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface KoreanOcrEngine {
    suspend fun recognize(image: Bitmap): String
    fun cancel() = Unit
}

internal data class OcrRecognitionCandidate(
    val text: String,
    val confidence: Int,
    val rotationDegrees: Int,
    val pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_AUTO
)

internal object OcrOrientationPolicy {
    private val fallbackRotations = listOf(90, -90, 180)
    private val fallbackPageSegModes = listOf(
        TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
    )

    fun needsFallback(candidate: OcrRecognitionCandidate): Boolean {
        val meaningful = candidate.text.count(Char::isLetterOrDigit)
        val hangul = candidate.text.count(::isHangul)
        val lines = candidate.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val fragmented = lines.size >= 3 &&
            lines.count { line -> line.count(Char::isLetterOrDigit) <= 2 } * 2 >= lines.size
        return meaningful < 6 || hangul < 4 || hangul * 2 < meaningful ||
            candidate.confidence < 65 || fragmented
    }

    fun fallbackRotations(): List<Int> = fallbackRotations

    fun fallbackPageSegModes(): List<Int> = fallbackPageSegModes

    fun best(candidates: List<OcrRecognitionCandidate>): OcrRecognitionCandidate =
        candidates.maxByOrNull(::score) ?: OcrRecognitionCandidate("", 0, 0)

    private fun score(candidate: OcrRecognitionCandidate): Int {
        val meaningful = candidate.text.count(Char::isLetterOrDigit)
        val hangul = candidate.text.count(::isHangul)
        return candidate.confidence.coerceIn(0, 100) * 8 + hangul * 15 + meaningful
    }

    private fun isHangul(char: Char): Boolean = char.code in 0x1100..0x11ff ||
        char.code in 0x3130..0x318f ||
        char.code in 0xa960..0xa97f ||
        char.code in 0xac00..0xd7af
}

class TesseractKoreanOcrEngine(
    private val dataPath: String
) : KoreanOcrEngine {
    @Volatile
    private var activeApi: TessBaseAPI? = null

    override suspend fun recognize(image: Bitmap): String = withContext(Dispatchers.IO) {
        val api = TessBaseAPI()
        activeApi = api
        try {
            if (!api.init(dataPath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
                throw OcrModelException("Korean OCR model could not be initialized")
            }
            api.setDebug(false)
            val candidates = recognizeOrientation(api, image, rotationDegrees = 0).toMutableList()
            if (OcrOrientationPolicy.needsFallback(OcrOrientationPolicy.best(candidates))) {
                for (degrees in OcrOrientationPolicy.fallbackRotations()) {
                    coroutineContext.ensureActive()
                    val rotated = Bitmap.createBitmap(
                        image,
                        0,
                        0,
                        image.width,
                        image.height,
                        Matrix().apply { setRotate(degrees.toFloat()) },
                        true
                    )
                    try {
                        candidates += recognizeOrientation(api, rotated, degrees)
                    } finally {
                        if (rotated !== image && !rotated.isRecycled) {
                            if (rotated.isMutable) rotated.eraseColor(0)
                            rotated.recycle()
                        }
                    }
                    if (!OcrOrientationPolicy.needsFallback(OcrOrientationPolicy.best(candidates))) {
                        break
                    }
                }
            }
            OcrOrientationPolicy.best(candidates).text
        } finally {
            if (activeApi === api) activeApi = null
            api.recycle()
        }
    }

    override fun cancel() {
        runCatching { activeApi?.stop() }
    }

    private suspend fun recognizeOrientation(
        api: TessBaseAPI,
        image: Bitmap,
        rotationDegrees: Int
    ): List<OcrRecognitionCandidate> {
        val candidates = mutableListOf(
            recognize(
                api,
                image,
                rotationDegrees,
                TessBaseAPI.PageSegMode.PSM_AUTO
            )
        )
        if (OcrOrientationPolicy.needsFallback(candidates.first())) {
            OcrOrientationPolicy.fallbackPageSegModes().forEach { pageSegMode ->
                candidates += recognize(api, image, rotationDegrees, pageSegMode)
            }
        }
        return candidates
    }

    private suspend fun recognize(
        api: TessBaseAPI,
        image: Bitmap,
        rotationDegrees: Int,
        pageSegMode: Int
    ): OcrRecognitionCandidate = try {
        api.setPageSegMode(pageSegMode)
        api.setImage(image)
        // hOCR recognition is the interruptible entry point. Plain text is read only after it
        // finishes, so cancel() can stop native work before any result becomes insertable.
        api.getHOCRText(0)
        coroutineContext.ensureActive()
        OcrRecognitionCandidate(
            text = api.getUTF8Text().orEmpty(),
            confidence = api.meanConfidence(),
            rotationDegrees = rotationDegrees,
            pageSegMode = pageSegMode
        )
    } finally {
        api.clear()
    }

    private companion object {
        const val LANGUAGE = "kor"
    }
}
