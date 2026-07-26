/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

/** One non-overlapping replacement in an [AiTextPatch]. Offsets are UTF-16 indices. */
class AiTextChange internal constructor(
    val id: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val targetStart: Int,
    val targetEnd: Int,
    val original: String,
    val replacement: String
)

/**
 * Immutable diff that can reconstruct either the complete suggestion or any explicit subset.
 *
 * Construction validates all ranges and unchanged gaps. That makes applying a checked subset a
 * pure operation; editor identity, stale-source and exactly-once checks remain at the existing
 * [org.fcitx.fcitx5.android.input.FcitxInputMethodService.applyAiSuggestion] boundary.
 */
class AiTextPatch internal constructor(
    val source: String,
    val target: String,
    val changes: List<AiTextChange>
) {
    private val changeIds = changes.mapTo(mutableSetOf()) { it.id }

    init {
        require(changeIds.size == changes.size) { "Duplicate change id" }
        var sourceCursor = 0
        var targetCursor = 0
        changes.forEachIndexed { index, change ->
            require(change.id == index) { "Change ids must be stable and sequential" }
            require(change.sourceStart in sourceCursor..source.length)
            require(change.sourceEnd in change.sourceStart..source.length)
            require(change.targetStart in targetCursor..target.length)
            require(change.targetEnd in change.targetStart..target.length)
            require(source.substring(sourceCursor, change.sourceStart) ==
                target.substring(targetCursor, change.targetStart)) {
                "Unchanged gap differs"
            }
            require(source.substring(change.sourceStart, change.sourceEnd) == change.original)
            require(target.substring(change.targetStart, change.targetEnd) == change.replacement)
            require(change.original.isNotEmpty() || change.replacement.isNotEmpty())
            sourceCursor = change.sourceEnd
            targetCursor = change.targetEnd
        }
        require(source.substring(sourceCursor) == target.substring(targetCursor)) {
            "Unchanged suffix differs"
        }
    }

    fun applyAll(): String = applySelected(changeIds)

    /** Returns [source] with only the explicitly selected changes applied. */
    fun applySelected(selectedChangeIds: Set<Int>): String {
        require(changeIds.containsAll(selectedChangeIds)) { "Unknown change id" }
        if (selectedChangeIds.isEmpty()) return source
        return buildString(source.length + (target.length - source.length).coerceAtLeast(0)) {
            var cursor = 0
            changes.forEach { change ->
                append(source, cursor, change.sourceStart)
                if (change.id in selectedChangeIds) {
                    append(change.replacement)
                } else {
                    append(change.original)
                }
                cursor = change.sourceEnd
            }
            append(source, cursor, source.length)
        }
    }
}

/** Bounded, Unicode-safe text differ for reviewed AI corrections. */
object AiTextDiff {
    // About 2 MiB for the IntArray used by LCS, small enough for an IME process.
    private const val MAX_LCS_CELLS = 500_000L

    private data class Token(val text: String, val start: Int, val end: Int)

    fun compute(source: String, target: String): AiTextPatch {
        if (source == target) return AiTextPatch(source, target, emptyList())

        val prefixEnd = commonPrefixEnd(source, target)
        val (sourceSuffixStart, targetSuffixStart) = commonSuffixStarts(source, target, prefixEnd)

        val sourceCodePoints = codePointTokens(source, prefixEnd, sourceSuffixStart)
        val targetCodePoints = codePointTokens(target, prefixEnd, targetSuffixStart)
        val useCodePoints = fitsLcs(sourceCodePoints.size, targetCodePoints.size)
        val sourceTokens = if (useCodePoints) {
            sourceCodePoints
        } else {
            lexicalTokens(source, prefixEnd, sourceSuffixStart)
        }
        val targetTokens = if (useCodePoints) {
            targetCodePoints
        } else {
            lexicalTokens(target, prefixEnd, targetSuffixStart)
        }

        val changes = if (fitsLcs(sourceTokens.size, targetTokens.size)) {
            diffTokens(
                source,
                target,
                sourceTokens,
                targetTokens,
                sourceSuffixStart,
                targetSuffixStart
            )
        } else {
            listOf(change(source, target, prefixEnd, sourceSuffixStart, prefixEnd, targetSuffixStart, 0))
        }
        return AiTextPatch(source, target, changes)
    }

    private fun diffTokens(
        source: String,
        target: String,
        sourceTokens: List<Token>,
        targetTokens: List<Token>,
        sourceMiddleEnd: Int,
        targetMiddleEnd: Int
    ): List<AiTextChange> {
        val targetWidth = targetTokens.size + 1
        val lcs = IntArray((sourceTokens.size + 1) * targetWidth)
        fun at(sourceIndex: Int, targetIndex: Int): Int = sourceIndex * targetWidth + targetIndex

        for (sourceIndex in sourceTokens.indices.reversed()) {
            for (targetIndex in targetTokens.indices.reversed()) {
                lcs[at(sourceIndex, targetIndex)] =
                    if (sourceTokens[sourceIndex].text == targetTokens[targetIndex].text) {
                        1 + lcs[at(sourceIndex + 1, targetIndex + 1)]
                    } else {
                        maxOf(
                            lcs[at(sourceIndex + 1, targetIndex)],
                            lcs[at(sourceIndex, targetIndex + 1)]
                        )
                    }
            }
        }

        val result = mutableListOf<AiTextChange>()
        var sourceIndex = 0
        var targetIndex = 0
        var pendingSourceStart = -1
        var pendingSourceEnd = -1
        var pendingTargetStart = -1
        var pendingTargetEnd = -1

        fun beginChange() {
            if (pendingSourceStart >= 0) return
            pendingSourceStart = sourceTokens.getOrNull(sourceIndex)?.start ?: sourceMiddleEnd
            pendingSourceEnd = pendingSourceStart
            pendingTargetStart = targetTokens.getOrNull(targetIndex)?.start ?: targetMiddleEnd
            pendingTargetEnd = pendingTargetStart
        }

        fun flushChange() {
            if (pendingSourceStart < 0) return
            result += change(
                source,
                target,
                pendingSourceStart,
                pendingSourceEnd,
                pendingTargetStart,
                pendingTargetEnd,
                result.size
            )
            pendingSourceStart = -1
        }

        while (sourceIndex < sourceTokens.size || targetIndex < targetTokens.size) {
            if (sourceIndex < sourceTokens.size && targetIndex < targetTokens.size &&
                sourceTokens[sourceIndex].text == targetTokens[targetIndex].text
            ) {
                flushChange()
                sourceIndex++
                targetIndex++
                continue
            }

            beginChange()
            val deleteLength = if (sourceIndex < sourceTokens.size) {
                lcs[at(sourceIndex + 1, targetIndex)]
            } else {
                -1
            }
            val insertLength = if (targetIndex < targetTokens.size) {
                lcs[at(sourceIndex, targetIndex + 1)]
            } else {
                -1
            }
            if (sourceIndex < sourceTokens.size && deleteLength >= insertLength) {
                pendingSourceEnd = sourceTokens[sourceIndex].end
                sourceIndex++
            } else {
                pendingTargetEnd = targetTokens[targetIndex].end
                targetIndex++
            }
        }
        flushChange()
        return result
    }

    private fun change(
        source: String,
        target: String,
        sourceStart: Int,
        sourceEnd: Int,
        targetStart: Int,
        targetEnd: Int,
        id: Int
    ) = AiTextChange(
        id = id,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        targetStart = targetStart,
        targetEnd = targetEnd,
        original = source.substring(sourceStart, sourceEnd),
        replacement = target.substring(targetStart, targetEnd)
    )

    private fun fitsLcs(sourceSize: Int, targetSize: Int): Boolean =
        (sourceSize + 1L) * (targetSize + 1L) <= MAX_LCS_CELLS

    private fun commonPrefixEnd(source: String, target: String): Int {
        var offset = 0
        val limit = minOf(source.length, target.length)
        while (offset < limit) {
            val sourceCodePoint = source.codePointAt(offset)
            if (sourceCodePoint != target.codePointAt(offset)) break
            offset += Character.charCount(sourceCodePoint)
        }
        return offset
    }

    private fun commonSuffixStarts(
        source: String,
        target: String,
        prefixEnd: Int
    ): Pair<Int, Int> {
        var sourceOffset = source.length
        var targetOffset = target.length
        while (sourceOffset > prefixEnd && targetOffset > prefixEnd) {
            val sourceCodePoint = source.codePointBefore(sourceOffset)
            val targetCodePoint = target.codePointBefore(targetOffset)
            if (sourceCodePoint != targetCodePoint) break
            sourceOffset -= Character.charCount(sourceCodePoint)
            targetOffset -= Character.charCount(targetCodePoint)
        }
        return sourceOffset to targetOffset
    }

    private fun codePointTokens(text: String, start: Int, end: Int): List<Token> = buildList {
        var offset = start
        while (offset < end) {
            val next = offset + Character.charCount(text.codePointAt(offset))
            add(Token(text.substring(offset, next), offset, next))
            offset = next
        }
    }

    private fun lexicalTokens(text: String, start: Int, end: Int): List<Token> = buildList {
        var offset = start
        while (offset < end) {
            val tokenStart = offset
            val firstCodePoint = text.codePointAt(offset)
            val kind = lexicalKind(firstCodePoint)
            offset += Character.charCount(firstCodePoint)
            if (kind != LexicalKind.Other) {
                while (offset < end) {
                    val codePoint = text.codePointAt(offset)
                    if (lexicalKind(codePoint) != kind) break
                    offset += Character.charCount(codePoint)
                }
            }
            add(Token(text.substring(tokenStart, offset), tokenStart, offset))
        }
    }

    private enum class LexicalKind { Word, Whitespace, Other }

    private fun lexicalKind(codePoint: Int): LexicalKind {
        val type = Character.getType(codePoint)
        return when {
            Character.isLetterOrDigit(codePoint) ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() -> LexicalKind.Word
            Character.isWhitespace(codePoint) -> LexicalKind.Whitespace
            else -> LexicalKind.Other
        }
    }
}

/** Validates that a partial patch still belongs to the currently reviewed source and result set. */
object AiPartialApplyGate {
    fun resolve(
        snapshotSource: String,
        renderedSuggestions: Collection<String>,
        patch: AiTextPatch,
        selectedChangeIds: Set<Int>
    ): String? {
        if (selectedChangeIds.isEmpty() ||
            patch.source != snapshotSource ||
            patch.target !in renderedSuggestions
        ) return null
        val resolved = runCatching { patch.applySelected(selectedChangeIds) }.getOrNull()
        return resolved?.takeUnless { it == snapshotSource }
    }
}
