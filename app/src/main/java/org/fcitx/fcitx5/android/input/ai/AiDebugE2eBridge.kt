/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.BuildConfig
import java.lang.reflect.Method

/**
 * Narrow bridge to the debug-source-set-only AI E2E responder.
 *
 * The implementation class is absent from release APKs.  Every call is additionally guarded by
 * [BuildConfig.DEBUG], so a release build never substitutes a provider, reads a test setting, or
 * changes its network path.  Debug code can arm exactly one explicit result from the dedicated
 * test host; normal debug usage follows the real provider path as well.
 */
/**
 * A result produced by the debug-only responder.  [delayMillis] exists solely so a headed test
 * can capture the real loading state before the already-local result is rendered.  Release
 * builds never receive one because [AiDebugE2eBridge] does not load its debug implementation.
 */
internal data class AiDebugE2eResponse(
    val result: AiGenerationResult,
    val delayMillis: Long = 0L
) {
    init {
        require(delayMillis >= 0L) { "Debug E2E delay must not be negative" }
    }
}

internal object AiDebugE2eBridge {
    fun profileForArmedRequest(): AiProviderProfile? =
        invoke("profileForArmedRequest") as? AiProviderProfile

    fun consumeIfArmed(
        profile: AiProviderProfile,
        action: AiAction,
        source: String
    ): AiDebugE2eResponse? = invoke(
        "consumeIfArmed",
        AiProviderProfile::class.java to profile,
        AiAction::class.java to action,
        String::class.java to source
    ) as? AiDebugE2eResponse

    private fun invoke(name: String, vararg arguments: Pair<Class<*>, Any>): Any? {
        if (!BuildConfig.DEBUG) return null
        val method = implementationClass()
            ?.methods
            ?.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.contentEquals(arguments.map(Pair<Class<*>, Any>::first).toTypedArray())
            }
            ?: return null
        return runCatching {
            method.invoke(null, *arguments.map(Pair<Class<*>, Any>::second).toTypedArray())
        }.getOrNull()
    }

    private fun implementationClass(): Class<*>? = runCatching {
        Class.forName(DEBUG_IMPLEMENTATION_CLASS)
    }.getOrNull()

    private const val DEBUG_IMPLEMENTATION_CLASS =
        "org.fcitx.fcitx5.android.debug.AiDebugGenerationOverride"
}
