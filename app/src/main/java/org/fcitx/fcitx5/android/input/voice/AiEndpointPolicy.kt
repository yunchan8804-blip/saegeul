/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.net.URI

class AiHttpStatusException(
    val status: Int,
    message: String
) : Exception(message)

/** HTTPS is mandatory even on RFC1918, loopback, and Tailscale/MagicDNS addresses. */
object AiEndpointPolicy {
    fun requireHttps(value: String, label: String) {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("$label is invalid") }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "$label must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "$label has no host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$label must not contain credentials, query, or fragment"
        }
    }
}
