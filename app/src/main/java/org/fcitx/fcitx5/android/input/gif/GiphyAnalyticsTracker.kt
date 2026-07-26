/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends only provider-issued action URLs, a random app identifier, and a timestamp. */
class GiphyAnalyticsTracker internal constructor(
    private val customerId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sender: suspend (String) -> Boolean = ::sendTrackingRequest
) {
    constructor(context: Context) : this(customerId = { GiphyCustomerIdStore(context).getOrCreate() })

    suspend fun track(result: GifResult, event: GifAnalyticsEvent): Boolean {
        if (result.providerId != GiphyGifProvider.PROVIDER_ID) return false
        val analytics = result.analytics ?: return false
        val baseUrl = when (event) {
            GifAnalyticsEvent.Load -> analytics.onLoadUrl
            GifAnalyticsEvent.Click -> analytics.onClickUrl
            GifAnalyticsEvent.Send -> analytics.onSendUrl
        }
        val url = buildTrackingUrl(baseUrl, customerId(), clock()) ?: return false
        return sender(url)
    }

    internal fun buildTrackingUrl(baseUrl: String, customerId: String, timestamp: Long): String? {
        if (!GiphyGifProvider.isOfficialAnalyticsUrl(baseUrl)) return null
        val normalizedId = customerId.trim().takeIf { CUSTOMER_ID.matches(it) } ?: return null
        if (timestamp <= 0L) return null
        val separator = if (URI(baseUrl).rawQuery.isNullOrEmpty()) "?" else "&"
        return baseUrl + separator + "customer_id=${normalizedId.analyticsEncode()}&ts=$timestamp"
    }

    companion object {
        internal val CUSTOMER_ID = Regex("[A-Za-z0-9._-]{8,128}")

        private suspend fun sendTrackingRequest(url: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 8_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }

        const val USER_AGENT =
            "Fcitx5Android-GiphyAnalytics/0.1 (https://github.com/fcitx5-android/fcitx5-android)"
    }
}

internal class GiphyCustomerIdStore(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH))

    fun getOrCreate(): String {
        runCatching { file.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { GiphyAnalyticsTracker.CUSTOMER_ID.matches(it) }
            ?.let { return it }
        val generated = UUID.randomUUID().toString()
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.new").apply { delete() }
        FileOutputStream(pending).use { output ->
            output.write(generated.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) file.delete()
        check(pending.renameTo(file)) { "Could not store GIPHY customer identifier" }
        return generated
    }

    private companion object {
        const val RELATIVE_PATH = "gif/giphy-customer-id"
    }
}

private fun String.analyticsEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
