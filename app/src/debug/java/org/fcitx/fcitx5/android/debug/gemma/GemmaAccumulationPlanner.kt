/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy

data class GemmaAccumulationPlan(
    val prefix: String,
    val nextCursor: Int
)

object GemmaAccumulationPlanner {
    fun select(
        counts: Map<String, Int>,
        attempts: Map<String, Int>,
        cursor: Int
    ): GemmaAccumulationPlan? {
        val prefixes = GeneratedMaterialPolicy.PREFIXES
        val eligible = prefixes.filter { prefix ->
            (counts[prefix] ?: 0) < GeneratedMaterialPolicy.TARGET_PER_PREFIX &&
                (attempts[prefix] ?: 0) < GeneratedMaterialPolicy.MAX_ATTEMPTS_PER_PREFIX
        }
        if (eligible.isEmpty()) return null

        val minimum = eligible.minOf { counts[it] ?: 0 }
        val candidates = eligible.filter { (counts[it] ?: 0) == minimum }.toSet()
        val start = cursor.floorMod(prefixes.size)
        val index = (0 until prefixes.size)
            .map { offset -> (start + offset) % prefixes.size }
            .first { prefixes[it] in candidates }
        return GemmaAccumulationPlan(prefixes[index], (index + 1) % prefixes.size)
    }

    fun hasCoverageDeficit(counts: Map<String, Int>): Boolean =
        GeneratedMaterialPolicy.PREFIXES.any { (counts[it] ?: 0) < GeneratedMaterialPolicy.TARGET_PER_PREFIX }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
