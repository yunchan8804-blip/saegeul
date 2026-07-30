/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import org.fcitx.fcitx5.android.input.ai.AiAction
import org.fcitx.fcitx5.android.input.ai.AiDebugE2eResponse
import org.fcitx.fcitx5.android.input.ai.AiGenerationResult
import org.fcitx.fcitx5.android.input.ai.AiProviderKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile

/**
 * One-shot, in-memory responder used only by [AiEditorTestActivity].
 *
 * It neither persists a provider profile nor owns a credential.  The assistant still captures
 * the editor through the real IME [android.view.inputmethod.InputConnection], renders its normal
 * result cards, and invokes the ordinary Replace callback.  It only replaces the remote response
 * after an explicit test-host tap.
 */
object AiDebugGenerationOverride {
    private val localProfile = AiProviderProfile(
        kind = AiProviderKind.OpenAICompatible,
        displayName = "Local AI E2E",
        baseUrl = "https://debug-e2e.invalid/v1",
        // This is a non-secret validation placeholder. It is kept only in this process and is
        // never sent because consumeIfArmed returns before the HTTP client is constructed.
        apiKey = "debug-e2e-placeholder"
    )

    private var armedMode: ResponseMode? = null

    @JvmStatic
    @Synchronized
    fun armForNextRequest() {
        armedMode = ResponseMode.AppendMarker
    }

    /**
     * Arms one exact-source response so headed E2E can prove that a no-op result never exposes
     * Replace or Append. This is deliberately unavailable from production source sets.
     */
    @JvmStatic
    @Synchronized
    fun armNoChangeForNextRequest() {
        armedMode = ResponseMode.ExactSource
    }

    /**
     * Keeps the real assistant's loading surface up long enough for a headed screenshot.  The
     * response is still generated entirely in-memory; no HTTP request or provider credential is
     * involved.
     */
    @JvmStatic
    @Synchronized
    fun armDelayedForNextRequest() {
        armedMode = ResponseMode.DelayedAppendMarker
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        armedMode = null
    }

    @JvmStatic
    @Synchronized
    fun profileForArmedRequest(): AiProviderProfile? = localProfile.takeIf { armedMode != null }

    @JvmStatic
    @Synchronized
    fun consumeIfArmed(
        profile: AiProviderProfile,
        action: AiAction,
        source: String
    ): Any? {
        val responseMode = armedMode ?: return null
        if (profile !== localProfile) return null
        armedMode = null
        val suggestions = when (responseMode) {
            ResponseMode.AppendMarker,
            ResponseMode.DelayedAppendMarker -> List(action.maxSuggestions) { index ->
                buildString {
                    if (source.isNotBlank()) append(source)
                    if (isNotEmpty()) append('\n')
                    append("[E2E_LOCAL_REPLACE_")
                    append(index + 1)
                    append(']')
                }
            }

            ResponseMode.ExactSource -> List(action.maxSuggestions) { source }
        }
        return AiDebugE2eResponse(
            result = AiGenerationResult(
                suggestions = suggestions,
                model = "debug-local-e2e",
                inputCharacters = source.length,
                outputCharacters = suggestions.sumOf(String::length)
            ),
            delayMillis = if (responseMode == ResponseMode.DelayedAppendMarker) {
                LOADING_E2E_DELAY_MILLIS
            } else {
                0L
            }
        )
    }

    private enum class ResponseMode {
        AppendMarker,
        ExactSource,
        DelayedAppendMarker
    }
}

/** Long enough for a headed emulator capture, but never part of a release variant. */
internal const val LOADING_E2E_DELAY_MILLIS = 8_000L
