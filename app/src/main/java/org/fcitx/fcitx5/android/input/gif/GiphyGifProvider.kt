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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Optional, provider-isolated GIPHY client. Search order is preserved exactly as returned.
 * `rating=g` is always server-enforced and a higher-rated response fails the entire page closed.
 */
class GiphyGifProvider(
    apiKey: String,
    private val mediaCachingApproved: Boolean,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PagedGifProvider {
    private val apiKey = apiKey.trim().also {
        require(it.isNotEmpty()) { "A GIPHY API key is required" }
    }

    override val displayName: String = POWERED_BY_GIPHY

    override suspend fun searchPage(query: String, page: Int, limit: Int): GifSearchPage {
        val safeLimit = limit.coerceIn(0, MAX_RESULTS)
        if (safeLimit == 0 || !GifSafeSearchPolicy.isAllowedQuery(query)) {
            return GifSearchPage(emptyList(), hasNext = false)
        }
        require(query.length <= MAX_QUERY_LENGTH) { "GIPHY query exceeds 50 characters" }
        val url = buildApiUrl(query, safeLimit, page)
        return withContext(Dispatchers.IO) {
            val connection = try {
                URI(url).toURL().openConnection() as HttpURLConnection
            } catch (_: Exception) {
                throw GifNetworkException("GIPHY request could not be created")
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                // The API key is in the query string. Never forward it to a redirect target.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = try {
                    connection.responseCode
                } catch (_: Exception) {
                    throw GifNetworkException("GIPHY request failed")
                }
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw GifNetworkException("GIPHY API HTTP $status")
                }
                val body = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    throw GifNetworkException("GIPHY response could not be read")
                }
                parsePageResponse(body, safeLimit)
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun buildApiUrl(query: String, limit: Int = MAX_RESULTS, page: Int = 1): String {
        require(query.length <= MAX_QUERY_LENGTH) { "GIPHY query exceeds 50 characters" }
        val normalizedLimit = limit.coerceIn(1, MAX_RESULTS)
        val isTrending = query.isBlank()
        val maxOffset = if (isTrending) MAX_TRENDING_OFFSET else MAX_SEARCH_OFFSET
        val offset = ((page.coerceAtLeast(1) - 1L) * normalizedLimit)
            .coerceAtMost(maxOffset.toLong())
            .toInt()
        val parameters = linkedMapOf(
            "api_key" to apiKey,
            "limit" to normalizedLimit.toString(),
            "offset" to offset.toString(),
            "rating" to SAFE_RATING,
            "bundle" to MESSAGING_BUNDLE,
            "country_code" to KOREAN_COUNTRY,
            "remove_low_contrast" to "true"
        )
        if (!isTrending) {
            // GIPHY requires the exact user query. Do not translate, expand, or autocorrect it.
            parameters["q"] = query
            parameters["lang"] = KOREAN_LANGUAGE
        }
        val endpoint = if (isTrending) "trending" else "search"
        val queryString = parameters.entries.joinToString("&") { (key, value) ->
            "${key.giphyEncode()}=${value.giphyEncode()}"
        }
        return "$API_ROOT/$endpoint?$queryString"
    }

    internal fun parsePageResponse(body: String, limit: Int = MAX_RESULTS): GifSearchPage {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Root is not an object")
        } catch (_: Exception) {
            throw GifNetworkException("GIPHY returned invalid JSON")
        }
        val meta = root.objectValue("meta")
        val status = meta?.int("status") ?: 0
        if (status !in 200..299) throw GifNetworkException("GIPHY API rejected the request")
        val data = root.arrayValue("data")
            ?: throw GifNetworkException("GIPHY response is missing result data")
        val parsed = data.map { element ->
            parseGif(element) ?: throw GifNetworkException(
                "GIPHY returned an invalid or incomplete result"
            )
        }
        if (parsed.any { !it.safe }) throw GifNetworkException(
            "GIPHY response violated the requested safe rating"
        )
        val pagination = root.objectValue("pagination")
        val count = pagination?.int("count") ?: parsed.size
        val offset = pagination?.int("offset") ?: 0
        val total = pagination?.int("total_count") ?: (offset + count)
        return GifSearchPage(
            items = parsed.take(limit.coerceIn(0, MAX_RESULTS)),
            hasNext = count > 0 && offset + count < total
        )
    }

    private fun parseGif(element: kotlinx.serialization.json.JsonElement): GifResult? {
        val item = element as? JsonObject ?: return null
        if (!item.string("type").equals("gif", ignoreCase = true)) return null
        val id = item.string("id").takeIf(String::isNotBlank) ?: return null
        val title = item.string("title").ifBlank { "GIF" }
        val canonical = item.string("url").takeIf(::isHttpsUrl) ?: return null
        val images = item.objectValue("images") ?: return null
        val original = images.objectValue("original") ?: return null
        val mediaUrl = original.string("url").takeIf(::isHttpsUrl) ?: return null
        val preview = listOf("fixed_width_small", "fixed_width", "downsized")
            .asSequence()
            .mapNotNull { renditionName -> images.objectValue(renditionName) }
            .mapNotNull { rendition ->
                rendition.string("webp").takeIf(::isHttpsUrl)
                    ?: rendition.string("url").takeIf(::isHttpsUrl)
            }
            .firstOrNull() ?: mediaUrl
        val analytics = item.objectValue("analytics")?.toAnalytics() ?: return null
        val rating = item.string("rating").lowercase()
        return GifResult(
            providerId = PROVIDER_ID,
            id = stableId(id),
            title = title,
            description = title,
            thumbnailUrl = preview,
            mediaUrl = mediaUrl,
            canonicalUrl = canonical,
            mimeType = GIF_MIME,
            byteSize = original.long("size").coerceAtLeast(0L),
            width = original.int("width").coerceAtLeast(0),
            height = original.int("height").coerceAtLeast(0),
            license = GifLicense(
                name = "GIPHY API Terms",
                url = GIPHY_TERMS,
                author = POWERED_BY_GIPHY,
                credit = POWERED_BY_GIPHY,
                attributionRequired = true
            ),
            safe = rating in SAFE_RESPONSE_RATINGS,
            attachmentDownloadAllowed = mediaCachingApproved,
            analytics = analytics
        )
    }

    private fun JsonObject.toAnalytics(): GifAnalytics? {
        fun event(name: String): String? = objectValue(name)
            ?.string("url")
            ?.takeIf(::isOfficialAnalyticsUrl)
        return GifAnalytics(
            onLoadUrl = event("onload") ?: return null,
            onClickUrl = event("onclick") ?: return null,
            onSendUrl = event("onsent") ?: return null
        )
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(key: String): Long =
        (get(key) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.arrayValue(key: String): JsonArray? = get(key) as? JsonArray

    private fun stableId(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        value.forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    companion object {
        const val PROVIDER_ID = "giphy"
        const val POWERED_BY_GIPHY = "Powered by GIPHY"
        const val GIPHY_TERMS = "https://support.giphy.com/hc/en-us/articles/360020027752-GIPHY-API-Terms-of-Service"
        private const val API_ROOT = "https://api.giphy.com/v1/gifs"
        private const val GIF_MIME = "image/gif"
        private const val SAFE_RATING = "g"
        private const val KOREAN_LANGUAGE = "ko"
        private const val KOREAN_COUNTRY = "KR"
        private const val MESSAGING_BUNDLE = "messaging_non_clips"
        private const val MAX_RESULTS = 24
        private const val MAX_QUERY_LENGTH = 50
        private const val MAX_TRENDING_OFFSET = 499
        private const val MAX_SEARCH_OFFSET = 4999
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
        private val SAFE_RESPONSE_RATINGS = setOf("g")
        private const val USER_AGENT =
            "Saegeul-GiphySearch/0.1 (https://github.com/yunchan8804-blip/saegeul)"

        internal fun isOfficialAnalyticsUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("giphy-analytics.giphy.com", ignoreCase = true)
        }.getOrDefault(false)

        private fun isHttpsUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}

private fun String.giphyEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
