/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search over Google's open Animated Noto Emoji catalog. The catalog contains metadata only;
 * user queries are matched locally and are never sent to Google.
 */
class NotoAnimatedEmojiProvider(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val catalogLoader: (suspend () -> String)? = null
) : GifProvider {

    override val displayName: String = "Animated Noto Emoji"

    @Volatile
    private var cachedCatalog: List<NotoIcon>? = null

    override suspend fun search(query: String, limit: Int): List<GifResult> {
        val catalog = cachedCatalog ?: loadCatalog().also { cachedCatalog = it }
        return searchCatalog(catalog, query, limit)
    }

    internal fun parseCatalog(body: String): List<NotoIcon> {
        val root = json.parseToJsonElement(body).jsonObject
        return root["icons"]?.jsonArray.orEmpty().mapNotNull { element ->
            val icon = element.jsonObject
            val codepoint = icon["codepoint"]?.jsonPrimitive?.contentOrNull
                ?.lowercase()
                ?.takeIf { CODEPOINT_PATTERN.matches(it) }
                ?: return@mapNotNull null
            val tags = icon["tags"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonPrimitive.contentOrNull
                    ?.trim(':')
                    ?.replace('-', ' ')
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank)
            }
            val categories = icon["categories"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonPrimitive.contentOrNull?.lowercase()?.takeIf(String::isNotBlank)
            }
            NotoIcon(
                codepoint = codepoint,
                popularity = icon["popularity"]?.jsonPrimitive?.intOrNull ?: 0,
                tags = tags,
                categories = categories
            )
        }
    }

    internal fun searchCatalog(
        catalog: List<NotoIcon>,
        query: String,
        limit: Int = 24
    ): List<GifResult> {
        val safeLimit = limit.coerceIn(0, 40)
        if (safeLimit == 0) return emptyList()
        val trimmed = query.trim().take(MAX_QUERY_LENGTH)
        val selected = if (trimmed.isEmpty()) {
            val byCodepoint = catalog.associateBy(NotoIcon::codepoint)
            FEATURED_CODEPOINTS.mapNotNull(byCodepoint::get)
                .ifEmpty { catalog.sortedByDescending(NotoIcon::popularity) }
                .take(safeLimit)
        } else {
            val normalized = trimmed.lowercase()
            val terms = buildSearchTerms(normalized)
            catalog.mapNotNull { icon ->
                val text = (icon.tags + icon.categories + KOREAN_TAGS[icon.codepoint].orEmpty())
                    .joinToString(" ")
                    .lowercase()
                val score = terms.maxOfOrNull { term ->
                    when {
                        term == text -> 120
                        icon.tags.any { it == term } -> 100
                        icon.tags.any { it.startsWith(term) } -> 75
                        text.contains(term) -> 50
                        else -> 0
                    }
                } ?: 0
                (icon to score).takeIf { score > 0 }
            }.sortedWith(
                compareByDescending<Pair<NotoIcon, Int>> { it.second }
                    .thenByDescending { it.first.popularity }
            ).take(safeLimit).map(Pair<NotoIcon, Int>::first)
        }
        return selected.map(::toResult)
    }

    private suspend fun loadCatalog(): List<NotoIcon> = withContext(Dispatchers.IO) {
        runCatching {
            val body = catalogLoader?.invoke() ?: downloadCatalog()
            parseCatalog(body).takeIf { it.isNotEmpty() }
                ?: error("Animated Noto Emoji catalog is empty")
        }.getOrElse { FALLBACK_ICONS }
    }

    private fun downloadCatalog(): String {
        val connection = URI(CATALOG_URL).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) throw GifNetworkException("Noto catalog HTTP $status")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildSearchTerms(query: String): Set<String> = buildSet {
        add(query)
        query.split(Regex("\\s+")).filter(String::isNotBlank).forEach(::add)
        KOREAN_QUERY_EXPANSIONS.forEach { (korean, expansions) ->
            if (query.contains(korean)) addAll(expansions)
        }
    }

    private fun toResult(icon: NotoIcon): GifResult {
        val codepoint = icon.codepoint
        val emoji = codepoint.split('_').joinToString("") { part ->
            String(Character.toChars(part.toInt(16)))
        }
        val label = icon.tags.firstOrNull().orEmpty()
        return GifResult(
            providerId = PROVIDER_ID,
            id = stableId(codepoint),
            title = listOf(emoji, label).filter(String::isNotBlank).joinToString(" "),
            description = (KOREAN_TAGS[codepoint].orEmpty() + icon.tags).distinct()
                .joinToString(", "),
            thumbnailUrl = "$ASSET_ROOT/$codepoint/512.webp",
            mediaUrl = "$ASSET_ROOT/$codepoint/512.gif",
            canonicalUrl = "$LANDING_PAGE?selected=Animated+Emoji%3Aemoji_u${codepoint}%3A",
            mimeType = GIF_MIME,
            byteSize = 0L,
            width = 512,
            height = 512,
            license = GifLicense(
                name = "CC BY 4.0",
                url = "https://creativecommons.org/licenses/by/4.0/",
                author = "Google",
                credit = "Animated Noto Emoji by Google",
                attributionRequired = true
            ),
            safe = true
        )
    }

    private fun stableId(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        value.forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    internal data class NotoIcon(
        val codepoint: String,
        val popularity: Int,
        val tags: List<String>,
        val categories: List<String>
    )

    companion object {
        private const val PROVIDER_ID = "animated_noto_emoji"
        private const val GIF_MIME = "image/gif"
        private const val MAX_QUERY_LENGTH = 80
        private const val CATALOG_URL =
            "https://googlefonts.github.io/noto-emoji-animation/data/api.json"
        private const val LANDING_PAGE = "https://googlefonts.github.io/noto-emoji-animation/"
        private const val ASSET_ROOT = "https://fonts.gstatic.com/s/e/notoemoji/latest"
        private const val USER_AGENT =
            "Fcitx5Android-GifSearch/0.2 (https://github.com/fcitx5-android/fcitx5-android)"
        private val CODEPOINT_PATTERN = Regex("[0-9a-f]+(?:_[0-9a-f]+)*")

        private val FEATURED_CODEPOINTS = listOf(
            "1f602", "1f923", "1f973", "1f44f", "1f389", "1f970",
            "1f60d", "1f44d", "2764_fe0f", "1f917", "1f64f", "1f929",
            "1f631", "1f914", "1f644", "1f62d", "1f621", "1f97a",
            "1f609", "1f60e", "1f525", "2728", "1f4af", "2705"
        )

        private val KOREAN_QUERY_EXPANSIONS = mapOf(
            "축하" to listOf("partying face", "party popper", "confetti ball", "clapping hands"),
            "생일" to listOf("birthday cake", "partying face", "wrapped gift", "party popper"),
            "박수" to listOf("clapping hands", "raising hands"),
            "웃" to listOf("laughing", "joy", "rofl", "grin", "smile"),
            "ㅋㅋ" to listOf("laughing", "joy", "rofl"),
            "행복" to listOf("smile", "joy", "heart face"),
            "사랑" to listOf("heart eyes", "heart face", "red heart", "kissing heart"),
            "감사" to listOf("folded hands", "clapping hands", "heart hands"),
            "미안" to listOf("folded hands", "pleading", "bow"),
            "놀람" to listOf("screaming", "surprised", "astonished", "exploding head"),
            "화남" to listOf("angry", "rage", "cursing"),
            "슬픔" to listOf("sad", "crying", "loudly crying", "pensive"),
            "울음" to listOf("crying", "loudly crying", "holding back tears"),
            "당황" to listOf("grimacing", "flushed", "melting", "dotted line face"),
            "생각" to listOf("thinking face", "monocle", "raised eyebrow"),
            "졸림" to listOf("sleeping", "yawn", "sleepy"),
            "안녕" to listOf("waving hand", "salute"),
            "최고" to listOf("thumbs up", "hundred points", "fire"),
            "좋아" to listOf("thumbs up", "heart eyes", "check mark"),
            "싫어" to listOf("thumbs down", "unamused", "cross mark"),
            "응원" to listOf("raising hands", "clapping hands", "flexed biceps", "fire"),
            "하트" to listOf("red heart", "heart hands", "sparkling heart", "two hearts")
        )

        private val KOREAN_TAGS = mapOf(
            "1f602" to listOf("웃음", "ㅋㅋ", "눈물"),
            "1f923" to listOf("폭소", "ㅋㅋㅋ"),
            "1f973" to listOf("축하", "파티"),
            "1f44f" to listOf("박수", "축하", "잘했어"),
            "1f389" to listOf("축하", "파티", "생일"),
            "1f970" to listOf("사랑", "행복"),
            "1f60d" to listOf("사랑", "좋아"),
            "1f44d" to listOf("좋아", "최고", "확인"),
            "2764_fe0f" to listOf("사랑", "하트"),
            "1f917" to listOf("포옹", "위로"),
            "1f64f" to listOf("감사", "미안", "부탁"),
            "1f631" to listOf("놀람", "충격"),
            "1f914" to listOf("생각", "고민"),
            "1f644" to listOf("어이없음", "황당"),
            "1f62d" to listOf("슬픔", "울음"),
            "1f621" to listOf("화남", "분노"),
            "1f97a" to listOf("부탁", "미안", "울먹"),
            "1f609" to listOf("윙크", "장난"),
            "1f60e" to listOf("멋짐", "최고"),
            "1f525" to listOf("불", "인기", "최고", "응원")
        )

        private val FALLBACK_ICONS = FEATURED_CODEPOINTS.mapIndexed { index, codepoint ->
            NotoIcon(
                codepoint = codepoint,
                popularity = FEATURED_CODEPOINTS.size - index,
                tags = KOREAN_TAGS[codepoint].orEmpty(),
                categories = listOf("reactions")
            )
        }
    }
}
