/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

data class GifLicense(
    val name: String,
    val url: String?,
    val author: String,
    val credit: String,
    val attributionRequired: Boolean
)

data class GifAnalytics(
    val onLoadUrl: String,
    val onClickUrl: String,
    val onSendUrl: String
)

enum class GifAnalyticsEvent { Load, Click, Send }

data class GifResult(
    val providerId: String,
    val id: Long,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val mediaUrl: String,
    val canonicalUrl: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val license: GifLicense,
    val safe: Boolean,
    /** False when a provider has not granted the media-copy approval required by its terms. */
    val attachmentDownloadAllowed: Boolean = true,
    val analytics: GifAnalytics? = null
)

interface GifProvider {
    val displayName: String

    suspend fun search(query: String, limit: Int = 24): List<GifResult>
}

data class GifSearchPage(
    val items: List<GifResult>,
    val hasNext: Boolean
)

/** Provider capability used by the GIF grid to request another provider-owned result page. */
interface PagedGifProvider : GifProvider {
    suspend fun searchPage(query: String, page: Int, limit: Int = 24): GifSearchPage

    override suspend fun search(query: String, limit: Int): List<GifResult> =
        searchPage(query, page = 1, limit = limit).items
}
