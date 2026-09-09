/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

/**
 * Unit tests for [PersonalGraphStore]: capacity trimming, dangling-edge pruning, encrypted
 * persistence round-trip, clear, and the [PersonalGraphStore.proximityBoost] re-ranking signal.
 */
class PersonalGraphStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun replaceGraphAndStatsReflectStoredCounts() {
        val store = PersonalGraphStore(clock = { 5000L })
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", listOf("업무"), 3.0f),
                PersonalGraphStore.Node("참석", listOf("업무"), 2.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 0.8f)),
            topics = listOf(PersonalGraphStore.Topic("t0", "업무", listOf("회의", "참석"))),
            builtMs = 4242L
        )

        val stats = store.stats()
        assertEquals(2, stats.nodes)
        assertEquals(1, stats.edges)
        assertEquals(1, stats.topics)
        assertEquals(4242L, stats.builtMs)
    }

    @Test
    fun replaceGraphTrimsNodesAndEdgesToCapacity() {
        val store = PersonalGraphStore()
        val nodes = (0 until 2500).map { i -> PersonalGraphStore.Node("n$i", emptyList(), i.toFloat()) }
        val edges = (0 until 6500).map { i ->
            PersonalGraphStore.Edge("n${i % 2500}", "n${(i + 1) % 2500}", i.toFloat())
        }

        store.replaceGraph(nodes, edges, emptyList(), builtMs = 1L)

        val stats = store.stats()
        assertEquals(2000, stats.nodes)
        assertTrue(stats.edges <= 6000)
    }

    @Test
    fun replaceGraphDropsEdgesReferringToMissingNodes() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)), // "참석" not in nodes
            topics = emptyList(),
            builtMs = 1L
        )

        assertEquals(0, store.stats().edges)
    }

    @Test
    fun saveAndLoadRoundTripThroughEncryptedVaultFile() {
        val file = tempFolder.newFile("personal_graph_encrypted.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())

        val first = PersonalGraphStore(storeFile = file, cipher = cipher)
        first.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", listOf("업무"), 3.0f),
                PersonalGraphStore.Node("참석", listOf("업무"), 2.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 0.8f)),
            topics = listOf(PersonalGraphStore.Topic("t0", "업무", listOf("회의", "참석"))),
            builtMs = 9999L
        )
        first.save()

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)

        val second = PersonalGraphStore(storeFile = file, cipher = cipher)
        val stats = second.stats()
        assertEquals(2, stats.nodes)
        assertEquals(1, stats.edges)
        assertEquals(1, stats.topics)
        assertEquals(9999L, stats.builtMs)

        // Loaded graph still functions for proximityBoost.
        val boost = second.proximityBoost(setOf("회의"), setOf("참석"))
        assertTrue(boost > 1.0f)
    }

    @Test
    fun clearResetsStatsAndDeletesFile() {
        val file = tempFolder.newFile("personal_graph_clear.json")
        val store = PersonalGraphStore(storeFile = file)
        store.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L
        )
        store.save()
        assertTrue(file.exists())

        store.clear()

        val stats = store.stats()
        assertEquals(0, stats.nodes)
        assertEquals(0, stats.edges)
        assertEquals(0, stats.topics)
        assertFalse(file.exists())
    }

    @Test
    fun savePropagatesCipherWriteFailure() {
        val store = PersonalGraphStore(
            storeFile = tempFolder.newFile("personal_graph_save_failure.json"),
            cipher = FailingVaultCipher()
        )
        store.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L
        )

        try {
            store.save()
            fail("cipher write failure must propagate")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun proximityBoostReturnsBaselineForEmptyGraph() {
        val store = PersonalGraphStore()

        val boost = store.proximityBoost(setOf("회의"), setOf("참석"))

        assertEquals(1.0f, boost)
    }

    @Test
    fun proximityBoostReturnsBaselineWhenNothingConnected() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("날씨", emptyList(), 1.0f)
            ),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L
        )

        val boost = store.proximityBoost(setOf("회의"), setOf("날씨"))

        assertEquals(1.0f, boost)
    }

    @Test
    fun proximityBoostRewardsDirectEdgeConnection() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("참석", emptyList(), 1.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)),
            topics = emptyList(),
            builtMs = 1L
        )

        val boost = store.proximityBoost(setOf("회의"), setOf("참석"))

        assertEquals(1.10f, boost, 0.0001f)
    }

    @Test
    fun proximityBoostRewardsSharedTopicMembership() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("보고서", emptyList(), 1.0f)
            ),
            edges = emptyList(),
            topics = listOf(PersonalGraphStore.Topic("t0", "업무", listOf("회의", "보고서"))),
            builtMs = 1L
        )

        val boost = store.proximityBoost(setOf("회의"), setOf("보고서"))

        assertEquals(1.10f, boost, 0.0001f)
    }

    @Test
    fun proximityBoostCapsAtThreeLinks() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("a1", emptyList(), 1.0f),
                PersonalGraphStore.Node("a2", emptyList(), 1.0f),
                PersonalGraphStore.Node("b1", emptyList(), 1.0f),
                PersonalGraphStore.Node("b2", emptyList(), 1.0f)
            ),
            edges = listOf(
                PersonalGraphStore.Edge("a1", "b1", 1.0f),
                PersonalGraphStore.Edge("a1", "b2", 1.0f),
                PersonalGraphStore.Edge("a2", "b1", 1.0f),
                PersonalGraphStore.Edge("a2", "b2", 1.0f)
            ),
            topics = emptyList(),
            builtMs = 1L
        )

        val boost = store.proximityBoost(setOf("a1", "a2"), setOf("b1", "b2"))

        assertEquals(1.30f, boost, 0.0001f)
    }

    @Test
    fun replaceGraphStoresSourceSentenceCountAndDefaultsToZeroWhenOmitted() {
        val storeWithCount = PersonalGraphStore()
        storeWithCount.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L,
            sourceSentenceCount = 42
        )
        assertEquals(42, storeWithCount.stats().sourceSentenceCount)

        val storeWithoutCount = PersonalGraphStore()
        storeWithoutCount.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L
        )
        assertEquals(0, storeWithoutCount.stats().sourceSentenceCount)
    }

    @Test
    fun sourceSentenceCountSurvivesSaveLoadRoundTrip() {
        val file = tempFolder.newFile("personal_graph_source_count_roundtrip.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())

        val first = PersonalGraphStore(storeFile = file, cipher = cipher)
        first.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L,
            sourceSentenceCount = 42
        )
        first.save()

        val second = PersonalGraphStore(storeFile = file, cipher = cipher)
        assertEquals(42, second.stats().sourceSentenceCount)
    }

    @Test
    fun clearResetsSourceSentenceCountToZero() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(PersonalGraphStore.Node("회의", emptyList(), 1.0f)),
            edges = emptyList(),
            topics = emptyList(),
            builtMs = 1L,
            sourceSentenceCount = 42
        )

        store.clear()

        assertEquals(0, store.stats().sourceSentenceCount)
    }

    @Test
    fun stemOfHoeuiStripsTheEuiParticleDownToHoe() {
        // Confirms the premise the alias index is built to work around: stem() over-strips a
        // non-particle syllable off "회의" because "의" is also a valid standalone particle.
        assertEquals("회", PersonalNgramTokenizer.stem("회의"))
    }

    @Test
    fun proximityBoostMatchesViaStemAliasWhenContextIsParticleStripped() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("참석", emptyList(), 1.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)),
            topics = emptyList(),
            builtMs = 1L
        )

        // "회" is PersonalNgramTokenizer.stem("회의"), not a real node id - only reachable via alias.
        val boost = store.proximityBoost(setOf("회"), setOf("참석"))

        assertTrue(boost > 1.0f)
    }

    @Test
    fun proximityBoostStillMatchesOnUnstemmedCanonicalNodeId() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("참석", emptyList(), 1.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)),
            topics = emptyList(),
            builtMs = 1L
        )

        val boost = store.proximityBoost(setOf("회의"), setOf("참석"))

        assertTrue(boost > 1.0f)
    }

    @Test
    fun stemAliasMatchingSurvivesSaveLoadRoundTrip() {
        val file = tempFolder.newFile("personal_graph_alias_roundtrip.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())

        val first = PersonalGraphStore(storeFile = file, cipher = cipher)
        first.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("참석", emptyList(), 1.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)),
            topics = emptyList(),
            builtMs = 1L
        )
        first.save()

        val second = PersonalGraphStore(storeFile = file, cipher = cipher)
        val boost = second.proximityBoost(setOf("회"), setOf("참석"))

        assertTrue(boost > 1.0f)
    }

    @Test
    fun proximityBoostReturnsBaselineAfterClearEvenForPreviouslyAliasedStem() {
        val store = PersonalGraphStore()
        store.replaceGraph(
            nodes = listOf(
                PersonalGraphStore.Node("회의", emptyList(), 1.0f),
                PersonalGraphStore.Node("참석", emptyList(), 1.0f)
            ),
            edges = listOf(PersonalGraphStore.Edge("회의", "참석", 1.0f)),
            topics = emptyList(),
            builtMs = 1L
        )

        store.clear()

        val boost = store.proximityBoost(setOf("회"), setOf("참석"))

        assertEquals(1.0f, boost)
    }

    private class FailingVaultCipher : VaultCipher {
        override val id: String = "failing"

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            throw GeneralSecurityException("write failure")
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = blob
    }
}
