/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnricher
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.fcitx.fcitx5.android.input.ai.typingdna.DynamicBigram
import org.fcitx.fcitx5.android.input.ai.typingdna.PersonaDna
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaCompiler
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Work that was already running when the user erased the data must not bring the data back. */
class EraseDuringWorkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun persona() = PersonaDna(
        category = "messenger",
        dominantTone = "Honorific",
        habitualEndings = listOf("합니다"),
        frequentBigrams = listOf(DynamicBigram("감사", "합니다", 0.8f)),
        cannedPhrases = listOf("감사합니다")
    )

    private fun graphNode(id: String) = PersonalGraphStore.Node(id, listOf("업무"), 1f)

    @Test
    fun graphMergeBuiltBeforeAnEraseIsDropped() {
        val file = File(tempFolder.root, "personal_graph.json")
        val store = PersonalGraphStore(file)
        store.replaceGraph(listOf(graphNode("회의")), emptyList(), emptyList(), 1L)
        store.save()

        val snapshot = store.snapshot()
        store.clear()
        val (mergedNodes, mergedEdges, mergedTopics) = PersonalGraphEnricher.mergeGraphs(
            snapshot.nodes, snapshot.edges, snapshot.topics,
            listOf(graphNode("자료")), emptyList(), emptyList()
        )
        val applied = store.replaceGraph(
            mergedNodes, mergedEdges, mergedTopics, 2L,
            expectedGeneration = snapshot.generation
        )

        assertFalse(applied)
        assertEquals(0, store.stats().nodes)
        assertFalse(file.exists())
    }

    @Test
    fun graphMergeOnAnUnchangedGenerationIsApplied() {
        val store = PersonalGraphStore()
        store.replaceGraph(listOf(graphNode("회의")), emptyList(), emptyList(), 1L)
        val snapshot = store.snapshot()

        val applied = store.replaceGraph(
            snapshot.nodes + graphNode("자료"), snapshot.edges, snapshot.topics, 2L,
            expectedGeneration = snapshot.generation
        )

        assertTrue(applied)
        assertEquals(2, store.stats().nodes)
    }

    @Test
    fun batchWhoseProfileIsErasedInsideItsProcessorIsNotSaved() {
        val storage = File(tempFolder.root, "typing_dna.json")
        val repository = TypingDnaRepository(storage)
        repository.updatePersona(persona(), 40)
        val vault = TypingDnaVault(thresholdPerCategory = 20)
        vault.recordSentence("com.kakao.talk", "친구야 오늘 저녁에 만나자")

        vault.processPending { _, sentences ->
            val generation = repository.generation()
            repository.clear()
            repository.updatePersona(persona(), sentences.size, generation)
        }

        assertEquals(0, repository.load(forceReload = true).totalAnalyzedSentences)
        assertTrue(repository.load().personas.isEmpty())
        assertFalse(storage.exists())
    }

    @Test
    fun compilerDropsABatchWhoseProfileWasErasedWhileItWasBuilt() {
        val storage = File(tempFolder.root, "typing_dna_compiler.json")
        val repository = TypingDnaRepository(storage)
        val compiler = TypingDnaCompiler(KoreanCollocationModel(), PersonalizedSentenceStore(), repository)
        val generation = repository.generation()

        repository.clear()
        compiler.compilePersona(persona(), persist = true, analyzedSentenceCount = 7, expectedGeneration = generation)

        assertEquals(0, repository.load(forceReload = true).totalAnalyzedSentences)
        assertFalse(storage.exists())

        compiler.compilePersona(persona(), persist = true, analyzedSentenceCount = 7, expectedGeneration = repository.generation())
        assertEquals(7, repository.load(forceReload = true).totalAnalyzedSentences)
    }
}
