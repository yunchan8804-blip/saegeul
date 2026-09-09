/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TypingDnaEndingRetentionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `new observed ending is retained ahead of the oldest retained ending`() {
        val repo = repository("recent-ending.json")
        val existingEndings = fifteenKoreanEndings()
        repo.updatePersona(persona(existingEndings))

        repo.updatePersona(persona(listOf("부탁드립니다")))

        val endings = repo.load().personas.getValue("work").habitualEndings
        assertEquals(15, endings.size)
        assertEquals("부탁드립니다", endings.first())
        assertFalse(endings.contains(existingEndings.last()))
        assertTrue(endings.containsAll(existingEndings.dropLast(1)))
    }

    @Test
    fun `observing an existing ending restores its recent priority without duplicates`() {
        val repo = repository("reobserved-ending.json")
        repo.updatePersona(persona(fifteenKoreanEndings()))
        repo.updatePersona(persona(listOf("부탁드립니다")))

        repo.updatePersona(persona(listOf("했습니다")))

        val endings = repo.load().personas.getValue("work").habitualEndings
        assertEquals("했습니다", endings.first())
        assertEquals(15, endings.size)
        assertEquals(endings.size, endings.distinct().size)
        assertTrue(endings.contains("부탁드립니다"))
    }

    @Test
    fun `first persona retains at most fifteen observed endings`() {
        val repo = repository("first-persona-cap.json")
        val endings = fifteenKoreanEndings() + "겠어요"

        repo.updatePersona(persona(endings))

        assertEquals(fifteenKoreanEndings(), repo.load().personas.getValue("work").habitualEndings)
    }

    @Test
    fun `empty observed ending batch preserves retained endings`() {
        val repo = repository("empty-ending-batch.json")
        val existingEndings = fifteenKoreanEndings()
        repo.updatePersona(persona(existingEndings))

        repo.updatePersona(persona(emptyList()))

        assertEquals(existingEndings, repo.load().personas.getValue("work").habitualEndings)
    }

    @Test
    fun `retained endings survive save and load`() {
        val storageFile = File(tempFolder.root, "save-load-ending.json")
        val repo = TypingDnaRepository(storageFile)
        repo.updatePersona(persona(fifteenKoreanEndings()))
        repo.updatePersona(persona(listOf("부탁드립니다")))

        val loaded = TypingDnaRepository(storageFile).load(forceReload = true)

        assertEquals(
            listOf("부탁드립니다") + fifteenKoreanEndings().dropLast(1),
            loaded.personas.getValue("work").habitualEndings
        )
    }

    private fun repository(fileName: String): TypingDnaRepository =
        TypingDnaRepository(File(tempFolder.root, fileName))

    private fun persona(endings: List<String>) = PersonaDna(
        category = "work",
        dominantTone = "Honorific",
        habitualEndings = endings
    )

    private fun fifteenKoreanEndings() = listOf(
        "합니다", "입니다", "됩니다", "드립니다", "했습니다",
        "하겠습니다", "할까요", "인가요", "네요", "죠",
        "세요", "어요", "아요", "거든요", "할까"
    )
}
