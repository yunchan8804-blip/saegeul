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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * GIF-only access to KLIPY's localized search and trending endpoints.
 *
 * The API key is supplied by the caller and is used only to build the HTTPS request path. It is
 * deliberately absent from error messages and attribution metadata.
 */
class KlipyGifProvider(
    apiKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val responseLoader: (suspend (String) -> String)? = null
) : PagedGifProvider {

    private val encodedApiKey: String = apiKey.trim().also {
        require(it.isNotEmpty()) { "A KLIPY API key is required" }
    }.encodePathSegment()

    /** Existing GIF cards surface this value alongside each provider's result set. */
    override val displayName: String = POWERED_BY_KLIPY

    override suspend fun searchPage(query: String, page: Int, limit: Int): GifSearchPage {
        val safeLimit = limit.coerceIn(0, MAX_RESULTS)
        if (safeLimit == 0 || !GifSafeSearchPolicy.isAllowedQuery(query)) {
            return GifSearchPage(emptyList(), hasNext = false)
        }
        val safePage = page.coerceIn(1, MAX_PAGE)
        val exact = requestPage(query, safeLimit, safePage)
        if (safePage != 1 || query.isBlank() || exact.items.isNotEmpty()) return exact

        // Preserve the provider's ranking. Retry only after a successful empty first page, and
        // never combine the exact and recovery queries into one grid.
        KoreanGifQueryPlanner.emptyResultFallbacks(query).forEach { fallback ->
            val recovered = requestPage(fallback, safeLimit, page = 1)
            if (recovered.items.isNotEmpty()) {
                // A recovery page intentionally stops here so page 2 cannot switch query identity.
                return recovered.copy(hasNext = false)
            }
        }
        return exact
    }

    private suspend fun requestPage(query: String, limit: Int, page: Int): GifSearchPage {
        val url = buildApiUrl(query, limit, page)
        val body = responseLoader?.invoke(url) ?: withContext(Dispatchers.IO) {
            val connection = try {
                URI(url).toURL().openConnection() as HttpURLConnection
            } catch (_: Exception) {
                throw GifNetworkException("KLIPY request could not be created")
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                // The API key is in the path. Never forward it to a redirect target.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    throw GifNetworkException("KLIPY request failed")
                }
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw GifNetworkException("KLIPY API HTTP $status")
                }
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    throw GifNetworkException("KLIPY response could not be read")
                }
            } finally {
                connection.disconnect()
            }
        }
        return parsePageResponse(body, limit)
    }

    internal fun buildApiUrl(
        query: String,
        limit: Int = MAX_RESULTS,
        page: Int = 1
    ): String {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        val endpoint = if (normalizedQuery.isEmpty()) "trending" else "search"
        val parameters = linkedMapOf(
            "page" to page.coerceIn(1, MAX_PAGE).toString(),
            "per_page" to limit.coerceIn(1, MAX_RESULTS).toString(),
            "locale" to KOREAN_LOCALE
        )
        if (normalizedQuery.isNotEmpty()) parameters["q"] = normalizedQuery
        val queryString = parameters.entries.joinToString("&") { (key, value) ->
            "${key.encodeQueryComponent()}=${value.encodeQueryComponent()}"
        }
        return "$API_ROOT/$encodedApiKey/gifs/$endpoint?$queryString"
    }

    internal fun parseResponse(body: String, limit: Int = MAX_RESULTS): List<GifResult> =
        parsePageResponse(body, limit).items

    internal fun parsePageResponse(body: String, limit: Int = MAX_RESULTS): GifSearchPage {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Root is not an object")
        } catch (_: Exception) {
            throw GifNetworkException("KLIPY returned invalid JSON")
        }
        if (root.boolean("result") != true) {
            throw GifNetworkException("KLIPY API rejected the request")
        }
        val data = root.objectValue("data")
            ?: throw GifNetworkException("KLIPY response is missing result data")
        val items = data.arrayValue("data")
            ?: throw GifNetworkException("KLIPY response is missing result data")
        val results = items.asSequence()
            .mapNotNull(::parseGif)
            .filter { GifSafeSearchPolicy.isAllowedResult(it.title, it.canonicalUrl) }
            .distinctBy { it.id }
            .distinctBy { it.mediaUrl }
            .take(limit.coerceIn(0, MAX_RESULTS))
            .toList()
        return GifSearchPage(
            items = results,
            hasNext = data.boolean("has_next") == true && results.isNotEmpty()
        )
    }

    private fun parseGif(element: kotlinx.serialization.json.JsonElement): GifResult? {
        val item = element as? JsonObject ?: return null
        if (!item.string("type").equals(GIF_TYPE, ignoreCase = true)) return null
        val slug = item.string("slug").trim().takeIf(String::isNotEmpty) ?: return null
        val files = item.objectValue("file") ?: return null
        val medium = files.objectValue("md")
        val small = files.objectValue("sm")
        val media = medium?.rendition(GIF_TYPE) ?: small?.rendition(GIF_TYPE) ?: return null
        val preview = small?.rendition(WEBP_TYPE)
            ?: medium?.rendition(WEBP_TYPE)
            ?: small?.rendition(GIF_TYPE)
            ?: media
        if (!media.url.isHttpsUrl() || !preview.url.isHttpsUrl()) return null

        return GifResult(
            providerId = PROVIDER_ID,
            id = stableId(slug),
            title = item.string("title").trim().ifEmpty { "GIF" },
            description = item.string("title").trim(),
            thumbnailUrl = preview.url,
            mediaUrl = media.url,
            canonicalUrl = "$CANONICAL_ROOT/${slug.encodePathSegment()}",
            mimeType = GIF_MIME,
            byteSize = media.byteSize.coerceAtLeast(0L),
            width = media.width.coerceAtLeast(0),
            height = media.height.coerceAtLeast(0),
            license = GifLicense(
                name = "KLIPY API Terms",
                url = KLIPY_TERMS,
                author = POWERED_BY_KLIPY,
                credit = POWERED_BY_KLIPY,
                attributionRequired = true
            ),
            // KLIPY account-level content filters are the source of truth for delivered results.
            safe = true
        )
    }

    private fun JsonObject.rendition(type: String): Rendition? {
        val rendition = objectValue(type) ?: return null
        val url = rendition.string("url").trim().takeIf(String::isNotEmpty) ?: return null
        return Rendition(
            url = url,
            width = rendition.int("width"),
            height = rendition.int("height"),
            byteSize = rendition.long("size")
        )
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(key: String): Long =
        (get(key) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.arrayValue(key: String): JsonArray? = get(key) as? JsonArray

    private fun String.isHttpsUrl(): Boolean = runCatching {
        val uri = URI(this)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    }.getOrDefault(false)

    private fun stableId(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        value.forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    private data class Rendition(
        val url: String,
        val width: Int,
        val height: Int,
        val byteSize: Long
    )

    private companion object {
        const val MAX_RESULTS = 24
        const val MAX_PAGE = 100
        const val MAX_QUERY_LENGTH = 80
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val KOREAN_LOCALE = "ko"
        const val GIF_TYPE = "gif"
        const val WEBP_TYPE = "webp"
        const val GIF_MIME = "image/gif"
        const val PROVIDER_ID = "klipy"
        const val POWERED_BY_KLIPY = "Powered by KLIPY"
        const val API_ROOT = "https://api.klipy.com/api/v1"
        const val CANONICAL_ROOT = "https://klipy.com/gifs"
        const val KLIPY_TERMS = "https://klipy.com/support/api-terms"
        const val USER_AGENT =
            "Fcitx5Android-GifSearch/0.3 (https://github.com/fcitx5-android/fcitx5-android)"
    }
}

private fun String.encodeQueryComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.encodePathSegment(): String = encodeQueryComponent()
