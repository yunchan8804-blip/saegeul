/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

object GifLicensePolicy {

    private val allowedLicensePatterns = listOf(
        Regex("^public domain$", RegexOption.IGNORE_CASE),
        Regex("^cc0(?: .*)?$", RegexOption.IGNORE_CASE),
        Regex("^cc by(?:[- ]sa)?(?: .*)?$", RegexOption.IGNORE_CASE)
    )

    private val rejectedTextPatterns = listOf(
        "all rights reserved",
        "copyright violation",
        "copyvio",
        "do not use",
        "fair use",
        "no permission",
        "non-free",
        "nonfree",
        "deletion candidate",
        "delete this",
        "porn",
        "pornography",
        "sexually explicit",
        "nudity",
        "nude",
        "genital"
    )

    fun isAllowed(licenseName: String, restrictions: String, searchableText: String): Boolean {
        val normalizedLicense = cleanText(licenseName)
        if (allowedLicensePatterns.none { it.matches(normalizedLicense) }) return false
        if (cleanText(restrictions).isNotEmpty()) return false
        val haystack = cleanText(searchableText).lowercase()
        return rejectedTextPatterns.none(haystack::contains)
    }

    fun cleanText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()
}
