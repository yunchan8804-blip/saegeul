/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.AiProviderException
import org.fcitx.fcitx5.android.input.ai.AiProviderFailureKind
import org.fcitx.fcitx5.android.input.ai.AiReauthenticationRequiredException
import org.fcitx.fcitx5.android.input.ai.AiSuggestionContractException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

class GraphEnrichmentRunnerTest {

    @Test
    fun classifiesAuthenticationTimeoutNetworkAndStorageFailures() {
        assertEquals(
            GraphEnrichmentFailure.REAUTH_REQUIRED,
            GraphEnrichmentRunner.classifyFailure(AiReauthenticationRequiredException())
        )
        assertEquals(
            GraphEnrichmentFailure.TIMEOUT,
            GraphEnrichmentRunner.classifyFailure(SocketTimeoutException("redacted"))
        )
        listOf(UnknownHostException("redacted"), ConnectException("redacted"), SSLException("redacted"))
            .forEach { error ->
                assertEquals(GraphEnrichmentFailure.NETWORK, GraphEnrichmentRunner.classifyFailure(error))
            }
        assertEquals(
            GraphEnrichmentFailure.STORAGE,
            GraphEnrichmentRunner.classifyFailure(GeneralSecurityException("redacted"))
        )
    }

    @Test
    fun classifiesProviderFailureKindsWithoutInspectingMessages() {
        assertEquals(
            GraphEnrichmentFailure.PROVIDER_BUSY,
            GraphEnrichmentRunner.classifyFailure(AiProviderException("redacted", httpStatus = 429))
        )
        listOf(
            AiProviderFailureKind.InvalidJson,
            AiProviderFailureKind.EmptyOutput,
            AiProviderFailureKind.NotCompleted
        ).forEach { kind ->
            assertEquals(
                GraphEnrichmentFailure.INVALID_RESPONSE,
                GraphEnrichmentRunner.classifyFailure(AiProviderException("redacted", failureKind = kind))
            )
        }
        assertEquals(
            GraphEnrichmentFailure.INVALID_RESPONSE,
            GraphEnrichmentRunner.classifyFailure(AiSuggestionContractException())
        )
        assertEquals(
            GraphEnrichmentFailure.PROVIDER_ERROR,
            GraphEnrichmentRunner.classifyFailure(AiProviderException("redacted"))
        )
    }

    @Test
    fun keepsGenericIoFailuresUnknown() {
        assertEquals(
            GraphEnrichmentFailure.UNKNOWN,
            GraphEnrichmentRunner.classifyFailure(IOException("redacted"))
        )
    }
}
