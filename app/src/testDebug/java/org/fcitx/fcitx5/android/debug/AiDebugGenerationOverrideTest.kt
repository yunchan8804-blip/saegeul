/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import org.fcitx.fcitx5.android.input.ai.AiAction
import org.fcitx.fcitx5.android.input.ai.AiDebugE2eResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDebugGenerationOverrideTest {
    @After
    fun reset() {
        AiDebugGenerationOverride.clear()
    }

    @Test
    fun `local responder is explicit one shot and does not need a provider credential`() {
        assertNull(AiDebugGenerationOverride.profileForArmedRequest())

        AiDebugGenerationOverride.armForNextRequest()
        val profile = requireNotNull(AiDebugGenerationOverride.profileForArmedRequest())
        assertTrue(profile.isConfigured)
        assertFalse(profile.apiKey.startsWith("sk-"))

        val result = AiDebugGenerationOverride.consumeIfArmed(
            profile = profile,
            action = AiAction.Proofread,
            source = "SELECT_THIS_EXACT_TEXT"
        ) as? AiDebugE2eResponse

        assertNotNull(result)
        assertEquals(
            listOf("SELECT_THIS_EXACT_TEXT\n[E2E_LOCAL_REPLACE_1]"),
            result?.result?.suggestions
        )
        assertEquals(0L, result?.delayMillis)
        assertNull(AiDebugGenerationOverride.profileForArmedRequest())
    }

    @Test
    fun `no change responder returns the reviewed source exactly once`() {
        val source = "SELECT_THIS_EXACT_TEXT"
        AiDebugGenerationOverride.armNoChangeForNextRequest()
        val profile = requireNotNull(AiDebugGenerationOverride.profileForArmedRequest())

        val result = AiDebugGenerationOverride.consumeIfArmed(
            profile = profile,
            action = AiAction.Proofread,
            source = source
        ) as? AiDebugE2eResponse

        assertEquals(listOf(source), result?.result?.suggestions)
        assertEquals(source.length, result?.result?.inputCharacters)
        assertEquals(source.length, result?.result?.outputCharacters)
        assertEquals(0L, result?.delayMillis)
        assertNull(AiDebugGenerationOverride.profileForArmedRequest())
    }

    @Test
    fun `delayed responder is consumed before its headed loading capture window`() {
        AiDebugGenerationOverride.armDelayedForNextRequest()
        val profile = requireNotNull(AiDebugGenerationOverride.profileForArmedRequest())

        val response = AiDebugGenerationOverride.consumeIfArmed(
            profile = profile,
            action = AiAction.Proofread,
            source = "SELECT_THIS_EXACT_TEXT"
        ) as? AiDebugE2eResponse

        assertEquals(LOADING_E2E_DELAY_MILLIS, response?.delayMillis)
        assertEquals(
            listOf("SELECT_THIS_EXACT_TEXT\n[E2E_LOCAL_REPLACE_1]"),
            response?.result?.suggestions
        )
        assertNull(AiDebugGenerationOverride.profileForArmedRequest())
    }
}
