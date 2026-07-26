/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartClipboardTransformerTest {
    @Test
    fun `plain text removes formatting controls but preserves emoji joiner`() {
        val source = "A\u00a0B\r\nC\u200e 👨‍👩‍👧"
        val result = success(SmartClipboardAction.PlainText, listOf(item(1, source)))

        assertEquals("A B\nC 👨‍👩‍👧", result.output)
    }

    @Test
    fun `combine follows explicit selection order`() {
        val state = SmartClipboardSelectionState()
        state.toggle(item(2, "둘째"))
        state.toggle(item(1, "첫째"))

        val result = success(SmartClipboardAction.Combine, state.items)
        assertEquals("둘째\n첫째", result.output)
    }

    @Test
    fun `selection toggles and enforces bounded item count`() {
        val state = SmartClipboardSelectionState(maxItems = 2)

        assertEquals(SmartClipboardSelectionResult.Added, state.toggle(item(1, "a")))
        assertEquals(SmartClipboardSelectionResult.Added, state.toggle(item(2, "b")))
        assertEquals(SmartClipboardSelectionResult.LimitReached, state.toggle(item(3, "c")))
        assertEquals(SmartClipboardSelectionResult.Removed, state.toggle(item(1, "a")))
        assertEquals(setOf(2), state.ids)
    }

    @Test
    fun `phone preset uses Korean number grouping`() {
        assertEquals(
            "010-1234-5678",
            success(SmartClipboardAction.PhoneNumber, listOf(item(1, "010 1234 5678"))).output
        )
        assertEquals(
            "02-1234-5678",
            success(SmartClipboardAction.PhoneNumber, listOf(item(1, "02.1234.5678"))).output
        )
    }

    @Test
    fun `account preset clearly uses generic groups of four from the right`() {
        assertEquals(
            "110-1234-5678",
            success(SmartClipboardAction.AccountNumber, listOf(item(1, "11012345678"))).output
        )
    }

    @Test
    fun `mask preview recognizes email phone and account without exposing their middle`() {
        val source = "메일 user@example.com 전화 010-1234-5678 계좌 1234-5678-9012"
        val result = success(SmartClipboardAction.MaskPersonalData, listOf(item(1, source)))

        assertEquals(3, result.maskCandidates.size)
        assertEquals(
            listOf(
                SmartClipboardPersonalDataKind.Email,
                SmartClipboardPersonalDataKind.Phone,
                SmartClipboardPersonalDataKind.Account
            ),
            result.maskCandidates.map(SmartClipboardMaskCandidate::kind)
        )
        assertFalse(result.output.contains("user@example.com"))
        assertFalse(result.output.contains("1234-5678"))
        assertTrue(result.output.contains("010-••••-5678"))
    }

    @Test
    fun `invalid explicit preset fails instead of changing or falling back`() {
        val result = SmartClipboardTransformer.preview(
            SmartClipboardAction.PhoneNumber,
            listOf(item(1, "123"))
        )

        assertEquals(
            SmartClipboardTransformResult.Failure(
                SmartClipboardTransformError.InvalidPhoneNumber
            ),
            result
        )
    }

    private fun success(
        action: SmartClipboardAction,
        items: List<SmartClipboardItem>
    ): SmartClipboardPreview =
        (SmartClipboardTransformer.preview(action, items) as SmartClipboardTransformResult.Success)
            .preview

    private fun item(id: Int, text: String) = SmartClipboardItem(id, text)
}
