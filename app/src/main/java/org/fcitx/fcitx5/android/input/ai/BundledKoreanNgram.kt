/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

data class BundledNgramCandidate(
    val word: String,
    val probability: Float,
    val order: Int
)

/**
 * 앱에 번들된 공개 코퍼스(FineWeb-2 한국어) 어절 bigram/trigram 통계(`korean/ko-ngram.bin`).
 *
 * 파일 전체를 한 번 읽어 ByteBuffer 위의 IntBuffer 뷰로만 조회하고, 어절당 객체를 만들지 않는다.
 * 포맷(KONGRAM1, little-endian u32)은 `scripts/pack-ko-base-assets.py`가 만든다.
 */
class BundledKoreanNgram private constructor(
    private val blob: ByteBuffer,
    private val wordOffsets: IntBuffer,
    private val biStart: IntBuffer,
    private val biNext: IntBuffer,
    private val biCount: IntBuffer,
    private val triA: IntBuffer,
    private val triB: IntBuffer,
    private val triStart: IntBuffer,
    private val triNext: IntBuffer,
    private val triCount: IntBuffer,
    val vocabularySize: Int
) {

    /**
     * [prev1] 다음에 올 어절을 빈도순으로 최대 [limit]개 돌려준다. [prev2]가 있고 (prev2, prev1)
     * trigram이 있으면 그 후보를 먼저 채우고, 남은 자리를 bigram으로 중복 없이 채운다.
     * probability는 각 후보 목록 안에서의 상대 빈도다.
     */
    fun nextWords(prev2: String?, prev1: String, limit: Int): List<BundledNgramCandidate> {
        if (limit <= 0) return emptyList()
        val id1 = indexOf(prev1)
        if (id1 < 0) return emptyList()
        val result = ArrayList<BundledNgramCandidate>(limit)
        val taken = HashSet<Int>()
        if (prev2 != null) {
            val id2 = indexOf(prev2)
            if (id2 >= 0) {
                val pair = pairIndex(id2, id1)
                if (pair >= 0) {
                    collect(triStart.get(pair), triStart.get(pair + 1), triNext, triCount, 3, limit, taken, result)
                }
            }
        }
        if (result.size < limit) {
            collect(biStart.get(id1), biStart.get(id1 + 1), biNext, biCount, 2, limit, taken, result)
        }
        return result
    }

    private fun collect(
        from: Int,
        to: Int,
        next: IntBuffer,
        count: IntBuffer,
        order: Int,
        limit: Int,
        taken: MutableSet<Int>,
        out: MutableList<BundledNgramCandidate>
    ) {
        if (from >= to) return
        var total = 0L
        for (i in from until to) total += count.get(i).toLong()
        if (total <= 0L) return
        for (i in from until to) {
            if (out.size >= limit) return
            val id = next.get(i)
            if (!taken.add(id)) continue
            out.add(BundledNgramCandidate(wordAt(id), (count.get(i).toDouble() / total).toFloat(), order))
        }
    }

    private fun pairIndex(a: Int, b: Int): Int {
        var lo = 0
        var hi = triA.limit() - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val ma = triA.get(mid)
            val cmp = if (ma != a) ma.compareTo(a) else triB.get(mid).compareTo(b)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun indexOf(word: String): Int {
        if (word.isEmpty()) return -1
        val key = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = vocabularySize - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareWordAt(mid, key)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun compareWordAt(id: Int, key: ByteArray): Int {
        val start = wordOffsets.get(id)
        val len = wordOffsets.get(id + 1) - start
        val n = minOf(len, key.size)
        for (i in 0 until n) {
            val a = blob.get(start + i).toInt() and 0xFF
            val b = key[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - key.size
    }

    private fun wordAt(id: Int): String {
        val start = wordOffsets.get(id)
        val len = wordOffsets.get(id + 1) - start
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = blob.get(start + i)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        const val ASSET_PATH = "korean/ko-ngram.bin"

        private const val MAGIC = "KONGRAM1"
        private const val VERSION = 1
        private const val HEADER_BYTES = 28
        private const val MAX_BYTES = 32 * 1024 * 1024

        private val HANGUL_WORD = Regex("^[가-힣]{1,12}$")
        private val SENTENCE_END = charArrayOf('.', '?', '!', '…', '\n', '\r')

        fun read(input: InputStream): BundledKoreanNgram {
            val bytes = input.readBytes()
            if (bytes.size > MAX_BYTES) throw IOException("ko-ngram.bin too large: ${bytes.size}")
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): BundledKoreanNgram {
            if (bytes.size < HEADER_BYTES) throw IOException("ko-ngram.bin truncated header")
            if (String(bytes, 0, 8, Charsets.US_ASCII) != MAGIC) throw IOException("ko-ngram.bin bad magic")
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt(8)
            if (version != VERSION) throw IOException("ko-ngram.bin unsupported version $version")
            val v = buf.getInt(12)
            val b = buf.getInt(16)
            val p = buf.getInt(20)
            val t = buf.getInt(24)
            if (v < 0 || b < 0 || p < 0 || t < 0) throw IOException("ko-ngram.bin bad counts")

            var pos = HEADER_BYTES
            fun ints(n: Int): IntBuffer {
                val byteLen = n.toLong() * 4
                if (pos + byteLen > bytes.size) throw IOException("ko-ngram.bin truncated section")
                val view = ByteBuffer.wrap(bytes, pos, byteLen.toInt()).slice()
                    .order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                pos += byteLen.toInt()
                return view
            }

            val wordOffsets = ints(v + 1)
            val blobLen = wordOffsets.get(v)
            if (blobLen < 0 || pos + blobLen > bytes.size) throw IOException("ko-ngram.bin truncated vocab")
            val blob = ByteBuffer.wrap(bytes, pos, blobLen).slice()
            pos += blobLen
            pos = (pos + 3) and 3.inv()
            val biStart = ints(v + 1)
            val biNext = ints(b)
            val biCount = ints(b)
            val triA = ints(p)
            val triB = ints(p)
            val triStart = ints(p + 1)
            val triNext = ints(t)
            val triCount = ints(t)
            if (biStart.get(v) != b || triStart.get(p) != t) throw IOException("ko-ngram.bin inconsistent ranges")
            return BundledKoreanNgram(
                blob, wordOffsets, biStart, biNext, biCount,
                triA, triB, triStart, triNext, triCount, v
            )
        }

        /**
         * 커서 앞 텍스트에서 코퍼스와 같은 규칙으로 마지막 두 어절을 뽑는다. 앞뒤 구두점·기호만
         * 벗기고, 한글 음절만으로 된 어절이 아니면 연쇄를 끊는다. 문장 끝 부호 뒤에는 문맥이 없다.
         * 반환값은 (prev2, prev1)이며 prev1이 없으면 null.
         */
        fun contextWords(textBeforeCursor: String): Pair<String?, String>? {
            if (textBeforeCursor.isBlank()) return null
            val trimmed = textBeforeCursor.trimEnd(' ', '\t')
            if (trimmed.isEmpty() || trimmed.last() in SENTENCE_END) return null
            val sentence = trimmed.substring(trimmed.lastIndexOfAny(SENTENCE_END) + 1)
            val raw = sentence.split(' ', '\t').filter { it.isNotEmpty() }
            if (raw.isEmpty()) return null
            val prev1 = cleanWord(raw.last()) ?: return null
            val before = raw.getOrNull(raw.size - 2) ?: return null to prev1
            return cleanWord(before) to prev1
        }

        private fun cleanWord(token: String): String? {
            var start = 0
            var end = token.length
            while (start < end && isPunctOrSymbol(token[start])) start++
            while (end > start && isPunctOrSymbol(token[end - 1])) end--
            val word = token.substring(start, end)
            return if (HANGUL_WORD.matches(word)) word else null
        }

        private fun isPunctOrSymbol(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        }
    }
}
