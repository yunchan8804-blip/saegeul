/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

enum class MediaType {
    GIF,
    VIDEO,
    STICKER
}

data class MediaItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val mediaType: MediaType = MediaType.GIF,
    val width: Int = 0,
    val height: Int = 0,
    val tags: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class MediaSearchRequest(
    val id: String,
    val query: String,
    val mediaType: MediaType = MediaType.GIF,
    val timestamp: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    var lastFailureMessage: String? = null,
    var nextRetryTime: Long = timestamp
)
