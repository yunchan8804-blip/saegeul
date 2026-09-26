/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.graph

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory L1 cache for the on-device personal ego-graph.
 * Provides O(1) (< 0.05ms) adjacency list lookup for HippoRAG and direct suggestion bridge.
 */
class OnDeviceL1GraphCache {

    private val adjacencyList = ConcurrentHashMap<String, MutableList<EdgeInfo>>()
    private val entityMap = ConcurrentHashMap<String, EntityInfo>()

    /**
     * Warms up the L1 in-memory cache from database entities and edges.
     */
    fun warmup(entities: List<EntityInfo>, edges: List<EdgeInfo>) {
        adjacencyList.clear()
        entityMap.clear()

        for (entity in entities) {
            entityMap[entity.id] = entity
        }

        for (edge in edges) {
            addEdgeToAdjacency(edge)
        }
    }

    /**
     * O(1) in-memory 1-hop neighbor lookup (< 0.05ms).
     */
    fun get1Hop(entityId: String): List<EdgeInfo> {
        return adjacencyList[entityId] ?: emptyList()
    }

    fun getEntity(entityId: String): EntityInfo? {
        return entityMap[entityId]
    }

    fun getAllEntities(): List<EntityInfo> {
        return entityMap.values.toList()
    }

    fun putEntity(entity: EntityInfo) {
        entityMap[entity.id] = entity
    }

    fun putEdge(edge: EdgeInfo) {
        addEdgeToAdjacency(edge)
    }

    fun clear() {
        adjacencyList.clear()
        entityMap.clear()
    }

    private fun addEdgeToAdjacency(edge: EdgeInfo) {
        val srcList = adjacencyList.computeIfAbsent(edge.src) { CopyOnWriteArrayList() }
        val existingIndexSrc = srcList.indexOfFirst { it.src == edge.src && it.dst == edge.dst && it.relation == edge.relation }
        if (existingIndexSrc >= 0) {
            srcList[existingIndexSrc] = edge
        } else {
            srcList.add(edge)
        }

        if (edge.src != edge.dst) {
            val dstList = adjacencyList.computeIfAbsent(edge.dst) { CopyOnWriteArrayList() }
            val existingIndexDst = dstList.indexOfFirst { it.src == edge.src && it.dst == edge.dst && it.relation == edge.relation }
            if (existingIndexDst >= 0) {
                dstList[existingIndexDst] = edge
            } else {
                dstList.add(edge)
            }
        }
    }
}
