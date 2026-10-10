/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnrichmentStagingStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.fcitx.fcitx5.android.input.ai.vault.ImmediateReadRetryRule
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Files that decrypt but do not parse must be kept, and a store must not end up half loaded. */
class UnparseableVaultProtectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val retryRule = ImmediateReadRetryRule()

    private fun plainFile(name: String, content: String): File {
        val file = File(tempFolder.root, name)
        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(name)).writeText(content)
        return file
    }

    private fun preserved(base: File) =
        base.parentFile!!.listFiles { _, name -> name.startsWith("${base.name}.unreadable-") }.orEmpty().toList()

    @Test
    fun personalizedSentenceStoreLoadsAllRecordsOrNoneAndKeepsTheFile() {
        val file = plainFile(
            "personalized_sentences.json",
            """[{"sentence":"첫 번째 문장입니다","intent":"General","tone":"Honorific"},{"intent":"General"}]"""
        )
        val before = file.readBytes()
        val store = PersonalizedSentenceStore(storageFile = file)

        store.load()

        assertEquals(0, store.size())
        store.upsert(PersonalizedSentenceRecord(sentence = "새로 배운 문장입니다", intent = ContextualIntent.General, tone = KoreanTone.Honorific, source = "user", score = 1f))
        store.save()
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun predictionMetricsStoreKeepsAnUnparseableFile() {
        val file = plainFile("prediction_metrics.json", """{"days":{"2026-01-01":"not an object"}}""")
        val before = file.readBytes()
        val store = PredictionMetricsStore(file, cipher = PlainVaultCipher)

        store.recordShown(3)
        store.save()

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun personalGraphStoreKeepsAnUnparseableFile() {
        val file = plainFile("personal_graph.json", """{"nodes":[{"id":"회의"}""")
        val before = file.readBytes()
        val store = PersonalGraphStore(file, cipher = PlainVaultCipher)

        assertEquals(0, store.stats().nodes)
        store.replaceGraph(listOf(PersonalGraphStore.Node("자료", listOf("업무"), 1f)), emptyList(), emptyList(), 1L)
        // The graph store reports a refused save to its caller (the enrichment worker).
        assertTrue(runCatching { store.save() }.exceptionOrNull() is java.io.IOException)

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun graphEnrichmentStagingStoreKeepsAnUnparseableFile() {
        val file = plainFile("personal_graph_staging.json", """{"chunks":[""")
        val before = file.readBytes()
        val store = PersonalGraphEnrichmentStagingStore(file, PlainVaultCipher)

        assertNull(store.load())
        runCatching {
            store.save(
                PersonalGraphEnrichmentStagingStore.State(
                    cycleStartedMs = 1L,
                    sourceSentenceCountAtStart = 1,
                    chunks = listOf("문장"),
                    nextChunkIndex = 0,
                    successCount = 0,
                    failCount = 0
                )
            )
        }

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun threeUnparseableOpensPreserveTheFileAndTheStoreStartsFresh() {
        val file = plainFile("prediction_metrics_three.json", """{"days":{"2026-01-01":"not an object"}}""")
        val before = file.readBytes()

        PredictionMetricsStore(file, cipher = PlainVaultCipher)
        PredictionMetricsStore(file, cipher = PlainVaultCipher)
        val third = PredictionMetricsStore(file, cipher = PlainVaultCipher)

        assertArrayEquals(before, preserved(file).single().readBytes())
        assertFalse(File(file.path + ".readfail").exists())
        third.recordShown(2)
        third.save()
        assertTrue(file.exists())
        assertEquals(1, preserved(file).size)
    }
}
