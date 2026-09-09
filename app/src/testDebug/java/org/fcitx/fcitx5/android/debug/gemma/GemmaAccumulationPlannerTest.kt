/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAccumulationPlannerTest {
    @Test
    fun `minimum coverage wins before round robin cursor`() {
        val counts = GeneratedMaterialPolicy.PREFIXES.associateWith { 1 }.toMutableMap().apply {
            this[GeneratedMaterialPolicy.PREFIXES.first()] = 0
        }

        val plan = requireNotNull(GemmaAccumulationPlanner.select(counts, emptyMap(), cursor = 9))

        assertEquals(GeneratedMaterialPolicy.PREFIXES.first(), plan.prefix)
        assertEquals(1, plan.nextCursor)
    }

    @Test
    fun `round robin advances among equally covered prefixes`() {
        val counts = GeneratedMaterialPolicy.PREFIXES.associateWith { 0 }

        val plan = requireNotNull(GemmaAccumulationPlanner.select(counts, emptyMap(), cursor = 7))

        assertEquals(GeneratedMaterialPolicy.PREFIXES[7], plan.prefix)
        assertEquals(8, plan.nextCursor)
    }

    @Test
    fun `covered prefixes are skipped and complete coverage has no plan`() {
        val counts = GeneratedMaterialPolicy.PREFIXES.associateWith {
            GeneratedMaterialPolicy.TARGET_PER_PREFIX
        }.toMutableMap().apply {
            this[GeneratedMaterialPolicy.PREFIXES[12]] = GeneratedMaterialPolicy.TARGET_PER_PREFIX - 1
        }

        assertEquals(
            GeneratedMaterialPolicy.PREFIXES[12],
            GemmaAccumulationPlanner.select(counts, emptyMap(), cursor = 0)?.prefix
        )
        counts[GeneratedMaterialPolicy.PREFIXES[12]] = GeneratedMaterialPolicy.TARGET_PER_PREFIX
        assertNull(GemmaAccumulationPlanner.select(counts, emptyMap(), cursor = 0))
        assertFalse(GemmaAccumulationPlanner.hasCoverageDeficit(counts))
    }

    @Test
    fun `attempt cap exhausts only prefixes at the cap`() {
        val counts = GeneratedMaterialPolicy.PREFIXES.associateWith { 0 }
        val attempts = GeneratedMaterialPolicy.PREFIXES.associateWith {
            GeneratedMaterialPolicy.MAX_ATTEMPTS_PER_PREFIX
        }.toMutableMap().apply {
            this[GeneratedMaterialPolicy.PREFIXES[3]] = GeneratedMaterialPolicy.MAX_ATTEMPTS_PER_PREFIX - 1
        }

        assertEquals(
            GeneratedMaterialPolicy.PREFIXES[3],
            GemmaAccumulationPlanner.select(counts, attempts, cursor = 0)?.prefix
        )
        attempts[GeneratedMaterialPolicy.PREFIXES[3]] = GeneratedMaterialPolicy.MAX_ATTEMPTS_PER_PREFIX
        assertNull(GemmaAccumulationPlanner.select(counts, attempts, cursor = 0))
        assertTrue(GemmaAccumulationPlanner.hasCoverageDeficit(counts))
    }
}
