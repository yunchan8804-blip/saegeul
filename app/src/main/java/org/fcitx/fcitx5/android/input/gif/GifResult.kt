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
    val safe: Boolean
)

interface GifProvider {
    val displayName: String

    suspend fun search(query: String, limit: Int = 24): List<GifResult>
}
