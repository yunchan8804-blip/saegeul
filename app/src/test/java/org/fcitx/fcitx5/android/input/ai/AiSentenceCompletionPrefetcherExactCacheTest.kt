/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiSentenceCompletionPrefetcherExactCacheTest {

    @Test
    fun exactContextKeyHit() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        val predictions = listOf("확인 후 알려드리겠습니다.")

        prefetcher.putPredictions("일정 확인 부탁드립니다", predictions)

        assertEquals(predictions, prefetcher.getCachedPredictions("일정 확인 부탁드립니다"))
    }

    @Test
    fun extendedContextMisses() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions("내가 뭘", listOf("해야 할지 모르겠어"))

        assertNull(prefetcher.getCachedPredictions("내가 뭘 해야"))
    }

    @Test
    fun punctuationDifferenceMisses() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions("회의 끝났어?", listOf("응, 방금 끝났어."))

        assertNull(prefetcher.getCachedPredictions("회의 끝났어!"))
    }

    @Test
    fun longContextsWithSameTailButDifferentPrefixMiss() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        val sharedTail = "같은 문맥 꼬리 ".repeat(20)
        prefetcher.putPredictions("첫 번째 앞문맥 $sharedTail", listOf("첫 번째 결과"))

        assertNull(prefetcher.getCachedPredictions("두 번째 앞문맥 $sharedTail"))
    }

    @Test
    fun whitespaceNormalizationHits() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        val predictions = listOf("다음 제안")
        prefetcher.putPredictions("오늘  회의\n안내", predictions)

        assertEquals(predictions, prefetcher.getCachedPredictions(" 오늘 회의   안내"))
        assertNull(prefetcher.getCachedPredictions("오늘 회의 안내 "))
    }

    @Test
    fun scopesSameContextByAppAndInputSession() {
        val prefetcher = AiSentenceCompletionPrefetcher()
        val context = "내가 뭘"
        val predictions = listOf("어떻게")
        val scope = AiSentenceCompletionPrefetcher.Scope("com.example.first", 7L)
        prefetcher.putPredictions(context, predictions, scope)

        assertEquals(predictions, prefetcher.getCachedPredictions(context, scope))
        assertNull(
            prefetcher.getCachedPredictions(
                context,
                AiSentenceCompletionPrefetcher.Scope("com.example.second", 7L)
            )
        )
        assertNull(
            prefetcher.getCachedPredictions(
                context,
                AiSentenceCompletionPrefetcher.Scope("com.example.first", 8L)
            )
        )
    }
}
