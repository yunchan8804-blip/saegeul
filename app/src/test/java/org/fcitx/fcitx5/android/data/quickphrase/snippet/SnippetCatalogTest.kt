/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.snippet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetCatalogTest {
    @Test
    fun builtInAddressAliasExpandsAtStartOrAfterWhitespace() {
        val catalog = SnippetCatalog.builtIns()

        assertEquals("{주소}", catalog.plan(":주소1")?.template)
        assertEquals("{주소}", catalog.plan("보내기 :주소1")?.template)
        assertNull(catalog.plan("https://example.com/:주소1"))
        assertNull(catalog.plan("앞:주소1"))
    }

    @Test
    fun userDefinitionOverridesBuiltInAndMatchesEnglishCaseInsensitively() {
        val catalog = SnippetCatalog.fromUserDefinitions(
            listOf(
                SnippetDefinition(":주소1", "회사 주소"),
                SnippetDefinition(":SIGN", "Best regards")
            )
        )

        assertEquals("회사 주소", catalog.plan(":주소1")?.template)
        assertEquals("Best regards", catalog.plan(":sign")?.template)
    }

    @Test
    fun conflictingUserDefinitionsRemainLiteral() {
        val catalog = SnippetCatalog.fromUserDefinitions(
            listOf(
                SnippetDefinition(":답장", "확인했습니다"),
                SnippetDefinition(":답장", "감사합니다")
            )
        )

        assertNull(catalog.plan(":답장"))
    }

    @Test
    fun splitBufferedTriggerDeletesOnlyCommittedPart() {
        val plan = SnippetCatalog.builtIns().plan("앞 ", ":주소1")!!

        assertEquals(0, plan.deleteBeforeCursor)
        assertEquals("", plan.pendingPrefix)
        assertEquals("서울 ", plan.replacement("서울", " "))

        val split = SnippetCatalog.builtIns().plan("앞 :주", "소1")!!
        assertEquals(2, split.deleteBeforeCursor)
        assertEquals("", split.pendingPrefix)
    }

    @Test
    fun bufferedPrefixBeforeTriggerIsPreserved() {
        val catalog = SnippetCatalog.fromUserDefinitions(
            listOf(SnippetDefinition(":인사", "안녕하세요"))
        )
        val plan = catalog.plan("", "앞말 :인사")!!

        assertEquals(0, plan.deleteBeforeCursor)
        assertEquals("앞말 ", plan.pendingPrefix)
        assertEquals("앞말 안녕하세요", plan.replacement("안녕하세요", ""))
    }

    @Test
    fun invalidOrIncompleteTriggersDoNotMatch() {
        val catalog = SnippetCatalog.builtIns()

        assertNull(catalog.plan(":주소1 뒤"))
        assertNull(catalog.plan(":"))
        assertNull(catalog.plan(":없는값"))
    }
}
