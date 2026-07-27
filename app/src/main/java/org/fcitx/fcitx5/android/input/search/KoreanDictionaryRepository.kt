/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

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
            cachedIndex ?: run {
                val startedAt = SystemClock.elapsedRealtime()
                context.assets.open(ASSET_PATH).use(KoreanDictionaryIndex::read).also {
                    cachedIndex = it
                    Timber.i(
                        "Korean dictionary binary index loaded in %d ms",
                        SystemClock.elapsedRealtime() - startedAt
                    )
                }
            }
        }

    companion object {
        const val ASSET_PATH = "korean/dictionary.bin"
    }
}
