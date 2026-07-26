/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface KoreanOcrEngine {
    suspend fun recognize(image: Bitmap): String
    fun cancel() = Unit
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
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(image)
            // hOCR recognition is the interruptible entry point. Plain text is read only after it
            // finishes, so cancel() can stop native work before any result becomes insertable.
            api.getHOCRText(0)
            coroutineContext.ensureActive()
            api.getUTF8Text().orEmpty()
        } finally {
            if (activeApi === api) activeApi = null
            api.recycle()
        }
    }

    override fun cancel() {
        runCatching { activeApi?.stop() }
    }

    private companion object {
        const val LANGUAGE = "kor"
    }
}
