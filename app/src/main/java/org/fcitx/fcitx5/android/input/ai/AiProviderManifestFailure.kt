/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Product-safe reasons for a failed HTTPS manifest check.
 *
 * The setup flow deliberately does not expose an address, response body, or exception message.
 * Those values can contain private network information, while the category is enough to explain
 * the next action without weakening HTTPS verification.
 */
enum class AiProviderManifestFailure {
    TailnetAddressUnavailable,
    AddressUnavailable,
    NetworkUnavailable,
    CertificateUntrusted,
    InvalidManifest,
    Unknown;

    companion object {
        fun classify(manifestUrl: String, error: Throwable): AiProviderManifestFailure {
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            if (causes.any { it is UnknownHostException }) {
                return if (isTailnetManifest(manifestUrl)) {
                    TailnetAddressUnavailable
                } else {
                    AddressUnavailable
                }
            }
            if (causes.any {
                    it is SSLHandshakeException ||
                        it is SSLPeerUnverifiedException ||
                        it is CertificateException ||
                        it is CertPathValidatorException
                }
            ) {
                return CertificateUntrusted
            }
            if (causes.any {
                    it is ConnectException ||
                        it is NoRouteToHostException ||
                        it is SocketTimeoutException ||
                        it is SocketException
                }
            ) {
                return NetworkUnavailable
            }
            if (causes.any { it is IllegalArgumentException || it is SecurityException }) {
                return InvalidManifest
            }
            return Unknown
        }

        private fun isTailnetManifest(manifestUrl: String): Boolean {
            val host = runCatching { URI(manifestUrl).host }
                .getOrNull()
                ?.lowercase(Locale.ROOT)
                ?: return false
            return host == "ts.net" || host.endsWith(".ts.net")
        }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
