/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.media

import java.util.LinkedHashMap

/**
 * Manages user favorite media, tags, and LRU bounded caching.
 */
class MediaFavoritesManager(private val maxCapacity: Int = 100) {

    private val favorites = object : LinkedHashMap<String, MediaItem>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?): Boolean {
            return size > maxCapacity
        }
    }

    fun getFavorites(): List<MediaItem> = favorites.values.toList().reversed()

    fun isFavorite(id: String): Boolean = favorites.containsKey(id)

    fun addFavorite(item: MediaItem): Boolean {
        if (favorites.containsKey(item.id)) {
            return false
        }
        favorites[item.id] = item
        return true
    }

    fun removeFavorite(id: String): Boolean {
        return favorites.remove(id) != null
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        return if (isFavorite(item.id)) {
            removeFavorite(item.id)
            false
        } else {
            addFavorite(item)
            true
        }
    }

    fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return getFavorites()
        val lower = query.trim().lowercase()
        return favorites.values.filter {
            it.title.lowercase().contains(lower) ||
                it.tags.any { tag -> tag.lowercase().contains(lower) }
        }.reversed()
    }

    fun findByTag(tag: String): List<MediaItem> {
        if (tag.isBlank()) return emptyList()
        val lower = tag.trim().lowercase()
        return favorites.values.filter {
            it.tags.any { t -> t.lowercase() == lower }
        }.reversed()
    }

    fun clear() {
        favorites.clear()
    }
}
