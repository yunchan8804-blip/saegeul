/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedSpacingIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun suggestsStoredTargetForSeparatedAndUnspacedSource() {
        val index = GeneratedSpacingIndex.build(listOf("회의 자료를 검토했습니다."))

        assertEquals("회의 자료를 검토했습니다.", index.suggestSpacing("회의자료를검토했습니다."))
        assertEquals("회의 자료를 검토했습니다.", index.suggestSpacing("회의 자료를검토했습니다."))
        assertEquals("회의 자료를 검토했습니다.", index.suggestSpacing(" 회의자료를검토했습니다. "))
    }

    @Test
    fun rejectsUnchangedAndNonExactSource() {
        val index = GeneratedSpacingIndex.build(listOf("회의 자료를 검토했습니다."))

        assertNull(index.suggestSpacing("회의 자료를 검토했습니다."))
        assertNull(index.suggestSpacing("회의자료를검투했습니다."))
        assertNull(index.suggestSpacing("회의자료를검토했습니다!"))
    }

    @Test
    fun rejectsAmbiguousTargetsButAllowsIdenticalDuplicates() {
        val ambiguous = GeneratedSpacingIndex.build(
            listOf("회의 자료를 검토했습니다.", "회의 자료를검토했습니다.", "회의 자료를 검토했습니다.")
        )
        val duplicate = GeneratedSpacingIndex.build(
            listOf("회의 자료를 검토했습니다.", "회의 자료를 검토했습니다.")
        )

        assertNull(ambiguous.suggestSpacing("회의자료를검토했습니다."))
        assertEquals("회의 자료를 검토했습니다.", duplicate.suggestSpacing("회의자료를검토했습니다."))
    }

    @Test
    fun rejectsInvalidWhitespacePiiAndOversizedTargets() {
        val target = "회의 자료를 검토했습니다."
        val tooLongTarget = "${"가".repeat(80)} ${"나".repeat(79)}."
        val index = GeneratedSpacingIndex.build(
            listOf(target, "연락처는 010-1234-5678입니다.", tooLongTarget)
        )

        assertNull(index.suggestSpacing("회의 자료를\n검토했습니다."))
        assertNull(index.suggestSpacing("회의 자료를\t검토했습니다."))
        assertNull(index.suggestSpacing("회의\u00A0자료를검토했습니다."))
        assertNull(index.suggestSpacing("연락처는010-1234-5678입니다."))
        assertNull(index.suggestSpacing(tooLongTarget.replace(" ", "")))
    }

    @Test
    fun rebuildsSpacingIndexWhenBankReloadsStoredEntries() {
        val file = File(tempFolder.root, "spacing.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        GeneratedSentenceBank(file, cipher).addGenerated(
            "[\"회의 자료를 검토했습니다.\"]",
            MODEL_ID,
            SHA
        )

        val reloaded = GeneratedSentenceBank(file, cipher)
        reloaded.load()

        assertEquals("회의 자료를 검토했습니다.", reloaded.suggestSpacing("회의자료를검토했습니다."))
    }

    private companion object {
        const val MODEL_ID = "gemma-4-e2b-it"
        const val SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
