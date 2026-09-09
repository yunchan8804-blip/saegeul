/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchedContinuationTest {
    @Test
    fun `parses typed word continuation and attachment wire values`() {
        val parsed = PrefetchedContinuation.parse(
            listOf(
                "WORD\t어떻게",
                "CONTINUATION\t잘못했는지 모르겠어",
                "CONTINUATION_ATTACH\t에 참석해 주세요."
            ),
            "내가 뭘"
        )

        assertEquals(
            listOf(
                PrefetchedContinuation(PrefetchedContinuation.Kind.WORD, "어떻게"),
                PrefetchedContinuation(PrefetchedContinuation.Kind.CONTINUATION, "잘못했는지 모르겠어"),
                PrefetchedContinuation(PrefetchedContinuation.Kind.CONTINUATION_ATTACH, "에 참석해 주세요.")
            ),
            parsed
        )
        assertEquals("WORD\t어떻게", parsed.first().toWireFormat())
    }

    @Test
    fun `rejects invalid tags repeated context and multiword word payloads`() {
        val parsed = PrefetchedContinuation.parse(
            listOf(
                "어떻게",
                "WORD 어떻게",
                "WORD\t어떻게 하면",
                "CONTINUATION\t내가 뭘 잘못했는지 모르겠어",
                "CONTINUATION\t내가 뭘",
                "UNKNOWN\t후보"
            ),
            "내가 뭘"
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `rejects raw control broken bytes and selected zero width while allowing emoji zwj`() {
        val parsed = PrefetchedContinuation.parse(
            listOf(
                "WORD\t어\t떻게",
                "CONTINUATION\t다음\n문장",
                "WORD\t깨짐\uFFFD",
                "CONTINUATION\t숨김\u200B문자",
                "CONTINUATION\t앞\uFEFF뒤",
                "CONTINUATION\t🙂👨‍👩‍👧‍👦"
            ),
            "내가 뭘"
        )

        assertEquals(
            listOf(PrefetchedContinuation(PrefetchedContinuation.Kind.CONTINUATION, "🙂👨‍👩‍👧‍👦")),
            parsed
        )
    }

    @Test
    fun `deduplicates by kind and payload`() {
        val parsed = PrefetchedContinuation.parse(
            listOf(
                "WORD\t어떻게",
                "WORD\t어떻게",
                "CONTINUATION\t어떻게",
                "CONTINUATION\t어떻게"
            ),
            "내가 뭘"
        )

        assertEquals(
            listOf(
                PrefetchedContinuation(PrefetchedContinuation.Kind.WORD, "어떻게"),
                PrefetchedContinuation(PrefetchedContinuation.Kind.CONTINUATION, "어떻게")
            ),
            parsed
        )
    }
}
