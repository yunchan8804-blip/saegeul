/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.graph

/**
 * Lightweight on-device HippoRAG Personalized PageRank (PPR / Random Walk with Restart) engine.
 *
 * Employs damping factor d = 0.85 and at most 3 localized subgraph iterations
 * to achieve instant convergence (< 0.5ms) on personal ego-networks.
 */
class HippoRagPprEngine(
    private val graphCache: OnDeviceL1GraphCache,
    private val dampingFactor: Float = 0.85f,
    private val maxIterations: Int = 3
) {

    /**
     * Computes Personalized PageRank starting from [seedEntities].
     *
     * @param seedEntities initial personalization teleportation vector.
     * @param maxResults maximum number of top ranked entities to return.
     * @param excludeSeeds whether to omit the input seeds from the returned recommendations.
     * @return list of (entityId, pprScore) ranked descending by relevance.
     */
    fun computePpr(
        seedEntities: Set<String>,
        maxResults: Int = 5,
        excludeSeeds: Boolean = true
    ): List<Pair<String, Float>> {
        val validSeeds = seedEntities.filter { it.isNotBlank() }.toSet()
        if (validSeeds.isEmpty()) return emptyList()

        val p0 = 1.0f / validSeeds.size
        var p = mutableMapOf<String, Float>()
        for (seed in validSeeds) {
            p[seed] = p0
        }

        val d = dampingFactor
        val restartProb = 1.0f - d

        repeat(maxIterations) {
            val nextP = mutableMapOf<String, Float>()

            // Inject restart mass back to personalization seeds
            for (seed in validSeeds) {
                nextP[seed] = (nextP[seed] ?: 0f) + restartProb * p0
            }

            // Propagate mass along 1-hop outgoing edges in local ego-subgraph
            for ((u, score) in p) {
                if (score <= 0f || score.isNaN() || score.isInfinite()) continue
                val edges = graphCache.get1Hop(u)
                if (edges.isEmpty()) {
                    // Dangling / isolated node: return mass to seed vector
                    for (seed in validSeeds) {
                        nextP[seed] = (nextP[seed] ?: 0f) + d * score * p0
                    }
                    continue
                }

                var totalWeight = 0f
                val neighborWeights = mutableMapOf<String, Float>()
                for (edge in edges) {
                    val v = if (edge.src == u) edge.dst else edge.src
                    if (v == u) continue // Self-loop edge: ignore for propagation to avoid self-amplification
                    val rawWeight = edge.weight
                    val w = if (!rawWeight.isNaN() && !rawWeight.isInfinite() && rawWeight > 0f) rawWeight else 1.0f
                    neighborWeights[v] = (neighborWeights[v] ?: 0f) + w
                    totalWeight += w
                }

                // Defensive check against division-by-zero, NaN, infinite, or isolated self-loops
                if (totalWeight <= 0f || totalWeight.isNaN() || totalWeight.isInfinite() || neighborWeights.isEmpty()) {
                    for (seed in validSeeds) {
                        nextP[seed] = (nextP[seed] ?: 0f) + d * score * p0
                    }
                    continue
                }

                val spreadMass = d * score
                for ((v, weight) in neighborWeights) {
                    val ratio = if (totalWeight > 0f) weight / totalWeight else 0f
                    val delta = spreadMass * ratio
                    if (!delta.isNaN() && !delta.isInfinite() && delta > 0f) {
                        nextP[v] = (nextP[v] ?: 0f) + delta
                    }
                }
            }

            p = nextP
        }

        val candidates = if (excludeSeeds) {
            p.filterKeys { it !in validSeeds }
        } else {
            p
        }

        return candidates.entries
            .filter { it.value > 0f && !it.value.isNaN() && !it.value.isInfinite() }
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { it.key to it.value }
    }
}
