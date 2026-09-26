/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [OnDeviceFailureText.resourceIdFor] directly (no Android [android.content.res.Resources]
 * instance is available in a plain JVM unit test) to fix the failure-code-to-string mapping.
 */
class OnDeviceFailureTextTest {

    @Test
    fun `busy variants map to the waiting message`() {
        assertEquals(R.string.gemma_automatic_blocked_busy, OnDeviceFailureText.resourceIdFor("BUSY"))
        assertEquals(R.string.gemma_automatic_blocked_busy, OnDeviceFailureText.resourceIdFor("BUSY_GENERATING"))
    }

    @Test
    fun `not-allowed maps to its own message, distinct from busy`() {
        assertEquals(R.string.gemma_automatic_blocked_not_allowed, OnDeviceFailureText.resourceIdFor("NOT_ALLOWED"))
    }

    @Test
    fun `missing or invalid model maps to the model setup message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_model_missing,
            OnDeviceFailureText.resourceIdFor("MODEL_MISSING")
        )
        assertEquals(
            R.string.gemma_automatic_blocked_model_missing,
            OnDeviceFailureText.resourceIdFor("MODEL_INVALID")
        )
    }

    @Test
    fun `unrecoverable engine maps to the restart-keyboard message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_unrecoverable,
            OnDeviceFailureText.resourceIdFor("ENGINE_UNRECOVERABLE")
        )
    }

    @Test
    fun `native codes map to the restarting-engine message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_restarting,
            OnDeviceFailureText.resourceIdFor("NATIVE_STOP_TIMEOUT")
        )
        assertEquals(
            R.string.gemma_automatic_blocked_restarting,
            OnDeviceFailureText.resourceIdFor("NATIVE_CLOSE_FAILED")
        )
        assertEquals(
            R.string.gemma_automatic_blocked_restarting,
            OnDeviceFailureText.resourceIdFor("NATIVE_CANCEL_FAILED")
        )
    }

    @Test
    fun `gpu engine initialization failure maps to its own message, distinct from restarting`() {
        assertEquals(
            R.string.gemma_automatic_blocked_engine_init_failed,
            OnDeviceFailureText.resourceIdFor("ENGINE_INITIALIZATION_FAILED")
        )
    }

    @Test
    fun `generation and preparation timeouts map to their own timeout message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_generation_timeout,
            OnDeviceFailureText.resourceIdFor("GENERATION_TIMEOUT")
        )
        assertEquals(
            R.string.gemma_automatic_blocked_preparation_timeout,
            OnDeviceFailureText.resourceIdFor("PREPARATION_TIMEOUT")
        )
    }

    @Test
    fun `empty response and output-too-long map to their own message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_empty_response,
            OnDeviceFailureText.resourceIdFor("EMPTY_RESPONSE")
        )
        assertEquals(
            R.string.gemma_automatic_blocked_output_too_long,
            OnDeviceFailureText.resourceIdFor("OUTPUT_TOO_LONG")
        )
    }

    @Test
    fun `resource codes map to their specific reason`() {
        assertEquals(R.string.gemma_automatic_blocked_thermal, OnDeviceFailureText.resourceIdFor("RESOURCE_THERMAL"))
        assertEquals(R.string.gemma_automatic_blocked_memory, OnDeviceFailureText.resourceIdFor("RESOURCE_LOW_MEMORY"))
        assertEquals(R.string.gemma_automatic_blocked_battery, OnDeviceFailureText.resourceIdFor("RESOURCE_BATTERY"))
        assertEquals(
            R.string.gemma_automatic_blocked_power_save,
            OnDeviceFailureText.resourceIdFor("RESOURCE_POWER_SAVE")
        )
    }

    @Test
    fun `unknown codes fall back to the generic message`() {
        assertEquals(
            R.string.gemma_automatic_blocked_generic,
            OnDeviceFailureText.resourceIdFor("SOMETHING_NEW")
        )
    }
}
