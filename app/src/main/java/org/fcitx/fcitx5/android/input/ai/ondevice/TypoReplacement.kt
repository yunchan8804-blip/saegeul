/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * 쓰는 중인 문장의 오타 어절만 모델 응답에서 받아 오는 대체문을 만든다.
 *
 * 모델은 멀쩡한 부분까지 고쳐 쓰므로(`날씨`에 조사를 붙이는 식), 응답을 통째로 대체문으로 쓰지 않는다. 쓰는 중인
 * 문장의 어절과 응답의 어절을 자모 편집거리로 나란히 맞추고(띄어쓰기 차이는 어절을 합치거나 나누어 맞춘다),
 * [TypoEvidence]가 오타라고 한 어절의 자리만 응답의 대응 어절로 바꾼다. 나머지 어절은 쓴 그대로 두고, 응답이 쓴
 * 문장 끝보다 더 이어 쓴 부분은 뒤에 붙인다.
 */
internal object TypoReplacement {

    /** 오타 어절 대신 응답의 대응 어절을 받아들이는 자모 정규화 거리의 상한. 넘으면 교정기 결과를 쓴다. */
    const val MAX_ADOPTED_DISTANCE = 0.5

    private const val MAX_GROUP = 3

    /** 어절을 합치거나 나누어 맞추는 짝은 띄어쓰기 차이여야 하므로, 이 거리를 넘는 어절 묶음은 짝으로 보지 않는다. */
    private const val MAX_GROUP_DISTANCE = 0.3
    private const val GROUP_PENALTY = 0.01
    private const val DROPPED_COST = 1.0
    private const val ADDED_COST = 0.5
    private val whitespaceRun = Regex("\\s+")

    /**
     * 쓴 어절 [typedStart, typedEnd)와 응답 어절 [replyStart, replyEnd)가 서로 맞는다. 쓴 쪽이 비면 응답이 더한 어절이고,
     * 응답 쪽이 비면 응답에 대응이 없는 쓴 어절이다.
     */
    class Link(val typedStart: Int, val typedEnd: Int, val replyStart: Int, val replyEnd: Int) {
        val consumesTyped: Boolean get() = typedEnd > typedStart
    }

    /**
     * 어절 단위 DP 정렬. 어절 하나끼리, 또는 한쪽 어절 하나와 다른 쪽 어절 [MAX_GROUP]개까지(띄어쓰기 차이)를 짝지어
     * 거리 합이 가장 작게 맞춘다. 어절 묶음은 [MAX_GROUP_DISTANCE] 이내일 때만 짝이 된다.
     * 응답에 짝이 없는 쓴 어절은 [DROPPED_COST], 쓴 문장 안에서 응답이 더한 어절은 [ADDED_COST]이고,
     * 쓴 어절이 모두 끝난 뒤 남은 응답 어절(이어쓰기)은 공짜다.
     */
    fun align(typed: List<String>, reply: List<String>): List<Link> {
        val n = typed.size
        val m = reply.size
        val width = m + 1
        val cost = DoubleArray((n + 1) * width) { Double.MAX_VALUE }
        val fromTyped = IntArray((n + 1) * width)
        val fromReply = IntArray((n + 1) * width)
        cost[0] = 0.0

        fun relax(i: Int, j: Int, nextI: Int, nextJ: Int, step: Double) {
            val total = cost[i * width + j] + step
            val next = nextI * width + nextJ
            if (total < cost[next]) {
                cost[next] = total
                fromTyped[next] = i
                fromReply[next] = j
            }
        }

        for (i in 0..n) {
            for (j in 0..m) {
                if (cost[i * width + j] == Double.MAX_VALUE) continue
                if (i < n) relax(i, j, i + 1, j, DROPPED_COST)
                if (j < m) relax(i, j, i, j + 1, if (i == n) 0.0 else ADDED_COST)
                for (a in 1..MAX_GROUP) {
                    for (b in 1..MAX_GROUP) {
                        if (a != 1 && b != 1) continue
                        if (i + a > n || j + b > m) continue
                        val distance = TypoRestatement.normalizedDistance(
                            typed.subList(i, i + a).joinToString(""),
                            reply.subList(j, j + b).joinToString("")
                        )
                        if (a + b > 2 && distance > MAX_GROUP_DISTANCE) continue
                        relax(i, j, i + a, j + b, distance + GROUP_PENALTY * (a + b - 2))
                    }
                }
            }
        }

        val links = ArrayDeque<Link>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val index = i * width + j
            val previousI = fromTyped[index]
            val previousJ = fromReply[index]
            links.addFirst(Link(previousI, i, previousJ, j))
            i = previousI
            j = previousJ
        }
        return links
    }

    /**
     * [typed] 문장에서 오타 증거가 있는 어절만 [reply]의 대응 어절(없거나 너무 다르면 교정기 결과)로 바꾼 문장. 증거가
     * 하나도 없거나 바뀐 것이 없으면 null이다.
     */
    fun compose(typed: String, reply: String, evidence: TypoEvidence): String? {
        val typedTokens = tokens(typed)
        val replyTokens = tokens(reply)
        if (typedTokens.isEmpty() || replyTokens.isEmpty()) return null
        val stillTyping = !typed.last().isWhitespace()
        val corrections = typedTokens.mapIndexed { index, token ->
            evidence.correctionFor(token, stillTyping && index == typedTokens.lastIndex)
        }
        if (corrections.all { it == null }) return null

        val links = align(typedTokens, replyTokens)
        val lastTypedLink = links.indexOfLast(Link::consumesTyped)
        val composed = buildList {
            links.forEachIndexed { index, link ->
                when {
                    link.consumesTyped -> addAll(tokensFor(link, typedTokens, replyTokens, corrections))
                    index > lastTypedLink -> addAll(replyTokens.subList(link.replyStart, link.replyEnd))
                }
            }
        }.joinToString(" ")
        return composed.takeUnless { withoutPunctuation(it) == withoutPunctuation(typedTokens.joinToString(" ")) }
    }

    /** 문장 부호만 다른 것은 바뀐 것으로 치지 않으므로, 글자·숫자·공백만 남겨 견준다. */
    private fun withoutPunctuation(text: String): String = text.filter { it.isLetterOrDigit() || it == ' ' }

    private fun tokensFor(
        link: Link,
        typedTokens: List<String>,
        replyTokens: List<String>,
        corrections: List<String?>
    ): List<String> {
        val group = typedTokens.subList(link.typedStart, link.typedEnd)
        val groupCorrections = corrections.subList(link.typedStart, link.typedEnd)
        if (groupCorrections.all { it == null }) return group
        val counterpart = replyTokens.subList(link.replyStart, link.replyEnd)
        if (counterpart.isNotEmpty() &&
            TypoRestatement.normalizedDistance(group.joinToString(""), counterpart.joinToString("")) <= MAX_ADOPTED_DISTANCE
        ) {
            return counterpart
        }
        return group.mapIndexed { index, token -> groupCorrections[index] ?: token }
    }

    private fun tokens(text: String): List<String> = text.trim().split(whitespaceRun).filter(String::isNotEmpty)
}
