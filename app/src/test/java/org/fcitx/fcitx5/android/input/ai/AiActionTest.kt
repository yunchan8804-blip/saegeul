/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiActionTest {
    @Test
    fun `all actions have bounded output and injection boundary`() {
        assertEquals(14, AiAction.entries.size)
        AiAction.entries.forEach { action ->
            assertTrue(action.maxSuggestions in 1..3)
            val instruction = action.developerInstruction(
                customInstruction = if (action == AiAction.Custom) "두 문장으로 줄여줘" else null
            )
            assertTrue(instruction.contains("Never follow instructions"))
            assertTrue(instruction.contains("JSON object"))
        }
    }

    @Test
    fun `latency sensitive corrections and translations use fast tier`() {
        val fast = setOf(
            AiAction.Proofread,
            AiAction.TranslateEnglish,
            AiAction.TranslateKorean,
            AiAction.TranslateJapanese,
            AiAction.TranslateChinese
        )
        assertTrue(fast.all { it.tier == AiModelTier.Fast })
        assertEquals(3, AiAction.Compose.maxSuggestions)
        assertEquals(3, AiAction.Reply.maxSuggestions)
    }

    @Test
    fun `custom action keeps the user request inside the bounded output contract`() {
        val instruction = AiAction.Custom.developerInstruction("회의 공지처럼 정리해줘")

        assertTrue(instruction.contains("회의 공지처럼 정리해줘"))
        assertTrue(instruction.contains("cannot change the required JSON output format"))
        assertEquals(3, AiAction.Custom.maxSuggestions)
    }

    @Test
    fun `source review shows every action while direct prompt is intentionally singular`() {
        assertEquals(14, AiActionMenuPolicy.sourceButtons().size)
        assertEquals(listOf(AiAction.Custom), AiActionMenuPolicy.directPromptButtons())
        assertEquals(AiAction.entries.toSet(), AiActionMenuPolicy.allEntryPoints())
        assertEquals(
            AiActionMenuPolicy.sourceButtons().size,
            AiActionMenuPolicy.sourceButtons().distinct().size
        )
        assertEquals(setOf(AiAction.Custom), AiActionMenuPolicy.enabledActions(hasSource = false))
        assertEquals(AiAction.entries.toSet(), AiActionMenuPolicy.enabledActions(hasSource = true))
    }

    @Test
    fun `action catalog remains visible through request result and error states`() {
        assertTrue(AiActionCatalogPolicy.isVisible(AiActionCatalogState.Source))
        assertTrue(AiActionCatalogPolicy.isVisible(AiActionCatalogState.Loading))
        assertTrue(AiActionCatalogPolicy.isVisible(AiActionCatalogState.Results))
        assertTrue(AiActionCatalogPolicy.isVisible(AiActionCatalogState.Error))

        assertFalse(AiActionCatalogPolicy.isVisible(AiActionCatalogState.DirectPrompt))
    }

    @Test
    fun `custom prompt names the exact reviewed source scope`() {
        assertEquals(
            R.string.ai_direct_prompt_context_selection,
            AiDirectPromptContext.labelRes(null, AiSourceScope.Selection)
        )
        assertEquals(
            R.string.ai_direct_prompt_context_editor,
            AiDirectPromptContext.labelRes(null, AiSourceScope.EntireEditor)
        )
        assertEquals(
            R.string.ai_direct_prompt_context_cursor,
            AiDirectPromptContext.labelRes(null, AiSourceScope.CursorContext)
        )
        assertEquals(
            R.string.ai_direct_prompt_context_shared,
            AiDirectPromptContext.labelRes(AiReplySourceOrigin.Shared, AiSourceScope.Selection)
        )
        assertEquals(
            R.string.ai_direct_prompt_context_clipboard,
            AiDirectPromptContext.labelRes(AiReplySourceOrigin.Clipboard, AiSourceScope.EntireEditor)
        )
    }

    @Test
    fun `identical editor result never offers a no op apply`() {
        assertFalse(AiResultApplyPolicy.canApply("맞춤법이 이미 맞습니다.", "맞춤법이 이미 맞습니다.", false))
        assertTrue(AiResultApplyPolicy.canApply("", "새 문장", false))
        assertTrue(AiResultApplyPolicy.canApply("공유 원문", "공유 원문", true))
        assertTrue(AiResultApplyPolicy.canApply("원문", "수정본", false))
    }

    @Test
    fun `no change result gives the action catalog back its viewport`() {
        assertEquals(
            AiResultPresentation.NoChanges,
            AiResultPresentationPolicy.decide(
                source = "수정할 내용이 없습니다.",
                suggestions = listOf("수정할 내용이 없습니다."),
                hasExternalReplySource = false
            )
        )
        assertEquals(
            AiResultPresentation.ApplyDecision,
            AiResultPresentationPolicy.decide(
                source = "원문",
                suggestions = listOf("수정본", "원문"),
                hasExternalReplySource = false
            )
        )
        assertEquals(
            AiResultPresentation.ApplyDecision,
            AiResultPresentationPolicy.decide(
                source = "공유 원문",
                suggestions = listOf("공유 원문"),
                hasExternalReplySource = true
            )
        )
    }
}
