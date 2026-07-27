/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

class KoreanDictionaryRepository(private val context: Context) {
    @Volatile
    private var cachedIndex: KoreanDictionaryIndex? = null
    private val loadMutex = Mutex()

    suspend fun lookup(query: String, limit: Int = KoreanDictionaryIndex.DEFAULT_LIMIT) =
        withContext(Dispatchers.IO) {
            loadIndex().lookup(query, limit)
        }

    private suspend fun loadIndex(): KoreanDictionaryIndex =
        cachedIndex ?: loadMutex.withLock {
            cachedIndex ?: context.assets.open(ASSET_PATH).use { source ->
                GZIPInputStream(source).bufferedReader(Charsets.UTF_8).use(KoreanDictionaryIndex::read)
            }.also { cachedIndex = it }
        }

    companion object {
        // Android packaging transparently expands assets ending in `.gz` and strips
        // the suffix. Keep a non-special extension so the descriptor and runtime
        // path stay identical while the payload remains gzip-compressed.
        const val ASSET_PATH = "korean/dictionary.tsv.gzip"
    }
}
