/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.content.res.Resources
import org.fcitx.fcitx5.android.R

/**
 * Maps an automatic-suggestion failure [code] to human-readable text (ko/en via string
 * resources) instead of surfacing the raw code to the user.
 */
object OnDeviceFailureText {

    fun of(code: String, res: Resources): String {
        val resId = resourceIdFor(code)
        return if (resId == R.string.gemma_automatic_blocked_generic) {
            res.getString(resId, code)
        } else {
            res.getString(resId)
        }
    }

    /** Exposed so the code-to-resource mapping can be unit tested without an Android [Resources]. */
    internal fun resourceIdFor(code: String): Int = when (code) {
        "BUSY", "BUSY_GENERATING" -> R.string.gemma_automatic_blocked_busy
        "NOT_ALLOWED" -> R.string.gemma_automatic_blocked_not_allowed
        "MODEL_MISSING", "MODEL_INVALID" -> R.string.gemma_automatic_blocked_model_missing
        "ENGINE_UNRECOVERABLE" -> R.string.gemma_automatic_blocked_unrecoverable
        "NATIVE_STOP_TIMEOUT", "NATIVE_CLOSE_FAILED", "NATIVE_CANCEL_FAILED" ->
            R.string.gemma_automatic_blocked_restarting
        "ENGINE_INITIALIZATION_FAILED" -> R.string.gemma_automatic_blocked_engine_init_failed
        "GENERATION_TIMEOUT" -> R.string.gemma_automatic_blocked_generation_timeout
        "PREPARATION_TIMEOUT" -> R.string.gemma_automatic_blocked_preparation_timeout
        "EMPTY_RESPONSE" -> R.string.gemma_automatic_blocked_empty_response
        "OUTPUT_TOO_LONG" -> R.string.gemma_automatic_blocked_output_too_long
        "RESOURCE_THERMAL" -> R.string.gemma_automatic_blocked_thermal
        "RESOURCE_LOW_MEMORY" -> R.string.gemma_automatic_blocked_memory
        "RESOURCE_BATTERY" -> R.string.gemma_automatic_blocked_battery
        "RESOURCE_POWER_SAVE" -> R.string.gemma_automatic_blocked_power_save
        else -> R.string.gemma_automatic_blocked_generic
    }
}
