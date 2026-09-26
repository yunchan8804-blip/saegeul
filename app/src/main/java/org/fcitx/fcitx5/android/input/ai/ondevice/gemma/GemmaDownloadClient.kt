/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** One (possibly ranged) GET response, abstracted so [GemmaModelDownloadWorker] is unit-testable without real network I/O. */
internal interface GemmaDownloadResponse {
    val responseCode: Int
    val contentLengthLong: Long
    val etag: String?
    val inputStream: InputStream
    fun disconnect()
}

/** Issues the ranged GET request for the fixed model URL. [rangeStartByte] null means a plain GET from the start. */
internal fun interface GemmaDownloadClient {
    fun open(url: String, rangeStartByte: Long?, ifRangeEtag: String?): GemmaDownloadResponse
}

/** [GemmaDownloadClient] backed by a real `HttpsURLConnection`, enforcing HTTPS end-to-end even across redirects. */
internal object HttpsGemmaDownloadClient : GemmaDownloadClient {
    override fun open(url: String, rangeStartByte: Long?, ifRangeEtag: String?): GemmaDownloadResponse {
        val connection = (URL(url).openConnection() as? HttpsURLConnection)
            ?: throw IOException("모델 다운로드 URL이 HTTPS 연결이 아닙니다.")
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECTION_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (rangeStartByte != null) {
            connection.setRequestProperty("Range", "bytes=$rangeStartByte-")
            if (ifRangeEtag != null) connection.setRequestProperty("If-Range", ifRangeEtag)
        }
        connection.connect()
        if (connection.url.protocol != "https") {
            connection.disconnect()
            throw IOException("모델 다운로드가 HTTPS가 아닌 연결로 전환되었습니다.")
        }
        val responseCode = connection.responseCode
        return object : GemmaDownloadResponse {
            override val responseCode: Int = responseCode
            override val contentLengthLong: Long = connection.contentLengthLong
            override val etag: String? = connection.getHeaderField("ETag")
            override val inputStream: InputStream get() = connection.inputStream
            override fun disconnect() = connection.disconnect()
        }
    }

    private const val CONNECTION_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
}
