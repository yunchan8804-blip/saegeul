/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderManifestFailureTest {
    @Test
    fun `tailnet hostname lookup gives Tailscale guidance`() {
        assertEquals(
            AiProviderManifestFailure.TailnetAddressUnavailable,
            AiProviderManifestFailure.classify(
                "https://desktop.example.ts.net/.well-known/fcitx-ai-provider",
                UnknownHostException("redacted")
            )
        )
    }

    @Test
    fun `ordinary hostname lookup gives address guidance`() {
        assertEquals(
            AiProviderManifestFailure.AddressUnavailable,
            AiProviderManifestFailure.classify(
                "https://computer.example/.well-known/fcitx-ai-provider",
                UnknownHostException("redacted")
            )
        )
    }

    @Test
    fun `TLS and network failures stay distinct through wrappers`() {
        assertEquals(
            AiProviderManifestFailure.CertificateUntrusted,
            AiProviderManifestFailure.classify(
                "https://computer.example/.well-known/fcitx-ai-provider",
                IllegalStateException("redacted", SSLHandshakeException("redacted"))
            )
        )
        assertEquals(
            AiProviderManifestFailure.NetworkUnavailable,
            AiProviderManifestFailure.classify(
                "https://computer.example/.well-known/fcitx-ai-provider",
                SocketTimeoutException("redacted")
            )
        )
        assertEquals(
            AiProviderManifestFailure.NetworkUnavailable,
            AiProviderManifestFailure.classify(
                "https://computer.example/.well-known/fcitx-ai-provider",
                ConnectException("redacted")
            )
        )
    }

    @Test
    fun `manifest contract errors do not masquerade as certificate errors`() {
        assertEquals(
            AiProviderManifestFailure.InvalidManifest,
            AiProviderManifestFailure.classify(
                "https://computer.example/.well-known/fcitx-ai-provider",
                IllegalArgumentException("redacted")
            )
        )
    }
}
