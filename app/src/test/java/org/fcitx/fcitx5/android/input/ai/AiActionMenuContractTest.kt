/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiActionMenuContractTest {
    @Test
    fun `source review exposes the full Korean writing catalog in its intended groups and order`() {
        assertEquals(
            listOf(AiAction.Proofread, AiAction.Compose, AiAction.Reply, AiAction.Custom),
            AiActionMenuPolicy.primary
        )
        assertEquals(
            listOf(
                AiAction.Polite,
                AiAction.Casual,
                AiAction.Business,
                AiAction.Decline,
                AiAction.Apology,
                AiAction.CustomerService
            ),
            AiActionMenuPolicy.tone
        )
        assertEquals(
            listOf(
                AiAction.TranslateEnglish,
                AiAction.TranslateKorean,
                AiAction.TranslateJapanese,
                AiAction.TranslateChinese
            ),
            AiActionMenuPolicy.translation
        )
        assertEquals(
            AiActionMenuPolicy.primary + AiActionMenuPolicy.tone + AiActionMenuPolicy.translation,
            AiActionMenuPolicy.sourceButtons()
        )
        assertEquals(14, AiActionMenuPolicy.sourceButtons().size)
        assertEquals(AiAction.entries.toSet(), AiActionMenuPolicy.sourceButtons().toSet())
    }

    @Test
    fun `only purpose built prompt state hides the source action catalog`() {
        val visibleStates = AiActionCatalogState.entries.filter(AiActionCatalogPolicy::isVisible)

        assertEquals(
            listOf(
                AiActionCatalogState.Source,
                AiActionCatalogState.Loading,
                AiActionCatalogState.Results,
                AiActionCatalogState.Error
            ),
            visibleStates
        )
        assertEquals(listOf(AiAction.Custom), AiActionMenuPolicy.directPromptButtons())
    }
}
