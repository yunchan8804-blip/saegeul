/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테스트용 작은 KONGRAM1 파일을 bigram 행만으로 만든다(trigram 없음). 포맷은
 * `scripts/pack-ko-base-assets.py`의 build_ngram_bin과 같다: 어절은 UTF-8 바이트 순으로 정렬하고,
 * prev마다 next를 (count 내림차순, id 오름차순)으로 저장한다.
 */
internal object SyntheticKoreanNgram {

    fun build(bigrams: Map<String, List<Pair<String, Int>>>): ByteArray {
        val vocab = (bigrams.keys + bigrams.values.flatten().map { it.first })
            .distinct()
            .sortedWith { x, y ->
                java.util.Arrays.compareUnsigned(x.toByteArray(Charsets.UTF_8), y.toByteArray(Charsets.UTF_8))
            }
        val idOf = vocab.withIndex().associate { (i, w) -> w to i }
        val v = vocab.size

        val biStart = IntArray(v + 1)
        val biNext = ArrayList<Int>()
        val biCount = ArrayList<Int>()
        for (i in 0 until v) {
            biStart[i] = biNext.size
            val entries = bigrams[vocab[i]].orEmpty()
                .map { idOf.getValue(it.first) to it.second }
                .sortedWith(compareBy({ -it.second }, { it.first }))
            for ((nid, count) in entries) {
                biNext.add(nid)
                biCount.add(count)
            }
        }
        biStart[v] = biNext.size

        val wordBytes = vocab.map { it.toByteArray(Charsets.UTF_8) }
        val wordOffsets = IntArray(v + 1)
        var offset = 0
        for (i in 0 until v) {
            wordOffsets[i] = offset
            offset += wordBytes[i].size
        }
        wordOffsets[v] = offset
        val blobPadded = (offset + 3) and 3.inv()

        val b = biNext.size
        val total = 28 + (v + 1) * 4 + blobPadded + (v + 1) * 4 + b * 4 * 2 + 4 // triStart(p + 1) with p = 0
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("KONGRAM1".toByteArray(Charsets.US_ASCII))
        buf.putInt(1).putInt(v).putInt(b).putInt(0).putInt(0)
        wordOffsets.forEach(buf::putInt)
        wordBytes.forEach(buf::put)
        buf.position(28 + (v + 1) * 4 + blobPadded)
        biStart.forEach(buf::putInt)
        biNext.forEach(buf::putInt)
        biCount.forEach(buf::putInt)
        buf.putInt(0)
        return buf.array()
    }

    fun parse(bigrams: Map<String, List<Pair<String, Int>>>): BundledKoreanNgram =
        BundledKoreanNgram.parse(build(bigrams))
}
