/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiphyAnalyticsTrackerTest {
    @Test
    fun sendsRequestedActionToOfficialHostWithCustomerAndTimestamp() = runBlocking {
        val sent = mutableListOf<String>()
        val tracker = GiphyAnalyticsTracker(
            customerId = { "local-customer-123" },
            clock = { 123456L },
            sender = { sent += it; true }
        )

        assertTrue(tracker.track(result(), GifAnalyticsEvent.Click))
        assertEquals(1, sent.size)
        assertEquals(
            "https://giphy-analytics.giphy.com/t?event=click&customer_id=local-customer-123&ts=123456",
            sent.single()
        )
    }

    @Test
    fun nonGiphyOrUnofficialTrackingUrlMakesZeroRequests() = runBlocking {
        var requests = 0
        val tracker = GiphyAnalyticsTracker(
            customerId = { "local-customer-123" },
            sender = { requests++; true }
        )

        assertFalse(tracker.track(result().copy(providerId = "klipy"), GifAnalyticsEvent.Load))
        assertFalse(
            tracker.track(
                result().copy(
                    analytics = result().analytics?.copy(onSendUrl = "https://example.test/t")
                ),
                GifAnalyticsEvent.Send
            )
        )
        assertEquals(0, requests)
    }

    private fun result() = GifResult(
        providerId = "giphy",
        id = 1,
        title = "test",
        description = "test",
        thumbnailUrl = "https://media.giphy.com/a.webp",
        mediaUrl = "https://media.giphy.com/a.gif",
        canonicalUrl = "https://giphy.com/gifs/a",
        mimeType = "image/gif",
        byteSize = 10,
        width = 10,
        height = 10,
        license = GifLicense("GIPHY", "https://giphy.com", "GIPHY", "Powered by GIPHY", true),
        safe = true,
        analytics = GifAnalytics(
            onLoadUrl = "https://giphy-analytics.giphy.com/t?event=load",
            onClickUrl = "https://giphy-analytics.giphy.com/t?event=click",
            onSendUrl = "https://giphy-analytics.giphy.com/t?event=send"
        )
    )
}
