/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class WikimediaCommonsGifProvider(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : GifProvider {

    override val displayName: String = "Wikimedia Commons"

    override suspend fun search(query: String, limit: Int): List<GifResult> =
        withContext(Dispatchers.IO) {
            val connection = URI(buildApiUrl(query, limit)).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Language", "ko,en;q=0.8")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw GifNetworkException("Commons API HTTP $status")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseResponse(body, limit)
            } finally {
                connection.disconnect()
            }
        }

    internal fun buildApiUrl(query: String, limit: Int): String {
        val search = buildSearchExpression(query)
        val params = linkedMapOf(
            "action" to "query",
            "generator" to "search",
            "gsrsearch" to search,
            "gsrnamespace" to "6",
            "gsrlimit" to limit.coerceIn(1, 40).toString(),
            "prop" to "imageinfo|info",
            "inprop" to "url",
            "iiprop" to "url|mime|size|extmetadata",
            "iiurlwidth" to "360",
            "iiextmetadatalanguage" to "ko",
            "iiextmetadatafilter" to METADATA_FILTER,
            "format" to "json",
            "formatversion" to "2",
            "uselang" to "ko"
        )
        return API_ENDPOINT + "?" + params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
    }

    internal fun buildSearchExpression(query: String): String {
        val trimmed = query.trim().take(MAX_QUERY_LENGTH)
        val base = if (trimmed.isEmpty()) {
            // A narrow query with consistently open-licensed, genuinely animated icon results.
            "happy animated icon"
        } else {
            "${expandKoreanQuery(trimmed)} animated"
        }
        return "$base filemime:\"image/gif\""
    }

    internal fun parseResponse(body: String, limit: Int = 24): List<GifResult> {
        val root = json.parseToJsonElement(body).jsonObject
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray ?: return emptyList()
        return pages.mapNotNull(::parsePage).take(limit.coerceAtLeast(0))
    }

    private fun parsePage(page: kotlinx.serialization.json.JsonElement): GifResult? {
        val obj = page.jsonObject
        val info = obj["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        if (info.string("mime") != GIF_MIME) return null
        val mediaUrl = info.string("url")
        val thumbnailUrl = info.string("thumburl").ifEmpty { mediaUrl }
        val canonicalUrl = obj.string("canonicalurl").ifEmpty { info.string("descriptionurl") }
        val metadata = info["extmetadata"]?.jsonObject ?: return null
        val licenseName = metadata.metadataValue("LicenseShortName")
        val author = GifLicensePolicy.cleanText(metadata.metadataValue("Artist"))
        val credit = GifLicensePolicy.cleanText(metadata.metadataValue("Credit"))
        val restrictions = metadata.metadataValue("Restrictions")
        val description = GifLicensePolicy.cleanText(metadata.metadataValue("ImageDescription"))
        val title = GifLicensePolicy.cleanText(obj.string("title").removePrefix("File:"))
        val searchableText = listOf(title, description, credit, metadata.metadataValue("UsageTerms"))
            .joinToString(" ")
        val bytes = info["size"]?.jsonPrimitive?.longOrNull ?: return null
        if (mediaUrl.isEmpty() || thumbnailUrl.isEmpty() || canonicalUrl.isEmpty() ||
            author.isEmpty() || bytes !in 1..MAX_GIF_BYTES ||
            !GifLicensePolicy.isAllowed(licenseName, restrictions, searchableText)
        ) return null
        return GifResult(
            providerId = PROVIDER_ID,
            id = obj["pageid"]?.jsonPrimitive?.longOrNull ?: return null,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            mediaUrl = mediaUrl,
            canonicalUrl = canonicalUrl,
            mimeType = GIF_MIME,
            byteSize = bytes,
            width = info["width"]?.jsonPrimitive?.intOrNull ?: 0,
            height = info["height"]?.jsonPrimitive?.intOrNull ?: 0,
            license = GifLicense(
                name = licenseName,
                url = metadata.metadataValue("LicenseUrl").ifBlank { null },
                author = author,
                credit = credit,
                attributionRequired = metadata.metadataValue("AttributionRequired")
                    .equals("true", ignoreCase = true)
            ),
            safe = true
        )
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.metadataValue(key: String): String =
        get(key)?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun expandKoreanQuery(query: String): String = KOREAN_QUERY_EXPANSIONS.entries
        .fold(query) { current, (key, expansion) ->
            current.replace(key, expansion, ignoreCase = true)
        }

    companion object {
        const val MAX_GIF_BYTES = 20L * 1024L * 1024L
        private const val MAX_QUERY_LENGTH = 80
        private const val GIF_MIME = "image/gif"
        private const val PROVIDER_ID = "wikimedia_commons"
        private const val API_ENDPOINT = "https://commons.wikimedia.org/w/api.php"
        private const val USER_AGENT =
            "Saegeul-GifSearch/0.1 (https://github.com/yunchan8804/saegeul)"
        private const val METADATA_FILTER =
            "LicenseShortName|LicenseUrl|Artist|Credit|AttributionRequired|Copyrighted|" +
                "Restrictions|UsageTerms|ImageDescription"
        private val KOREAN_QUERY_EXPANSIONS = linkedMapOf(
            "축하" to "happy icon",
            "생일" to "happy birthday",
            "박수" to "clapping hands",
            "웃" to "laugh icon",
            "기쁨" to "joy icon",
            "사랑" to "love heart icon",
            "감사" to "thank you",
            "놀람" to "surprised wow icon",
            "화남" to "angry icon",
            "슬픔" to "sad crying icon",
            "안녕" to "hello waving"
        )
    }
}

class GifNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
