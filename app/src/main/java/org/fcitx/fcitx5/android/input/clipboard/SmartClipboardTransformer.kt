/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

object SmartClipboardTransformer {
    const val MAX_OUTPUT_CHARACTERS = 8_000

    private val emailRegex = Regex(
        "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])"
    )
    private val phoneRegex = Regex(
        "(?<!\\d)(?:(?:\\+?82)[ -]?)?(?:0?1[016789]|0?2|0?[3-6]\\d)[ -]?\\d{3,4}[ -]?\\d{4}(?!\\d)"
    )
    private val accountRegex = Regex("(?<!\\d)(?:\\d[ -]?){8,20}(?!\\d)")

    fun preview(
        action: SmartClipboardAction,
        items: List<SmartClipboardItem>
    ): SmartClipboardTransformResult {
        if (items.isEmpty()) return failure(SmartClipboardTransformError.NoSelection)
        val transformed = when (action) {
            SmartClipboardAction.PlainText -> {
                if (items.size != 1) return failure(
                    SmartClipboardTransformError.PlainTextNeedsOneItem
                )
                SmartClipboardPreview(action, stripFormatting(items.single().text), 1)
            }
            SmartClipboardAction.Combine -> {
                if (items.size < 2) return failure(
                    SmartClipboardTransformError.CombineNeedsMultipleItems
                )
                SmartClipboardPreview(
                    action,
                    items.joinToString("\n") { stripFormatting(it.text).trim() },
                    items.size
                )
            }
            SmartClipboardAction.PhoneNumber -> {
                if (items.size != 1) return failure(SmartClipboardTransformError.NumberNeedsOneItem)
                val formatted = formatPhone(items.single().text)
                    ?: return failure(SmartClipboardTransformError.InvalidPhoneNumber)
                SmartClipboardPreview(action, formatted, 1)
            }
            SmartClipboardAction.AccountNumber -> {
                if (items.size != 1) return failure(SmartClipboardTransformError.NumberNeedsOneItem)
                val formatted = formatAccount(items.single().text)
                    ?: return failure(SmartClipboardTransformError.InvalidAccountNumber)
                SmartClipboardPreview(action, formatted, 1)
            }
            SmartClipboardAction.MaskPersonalData -> {
                val source = items.joinToString("\n") { stripFormatting(it.text) }
                val candidates = maskCandidates(source)
                if (candidates.isEmpty()) return failure(SmartClipboardTransformError.NoPersonalData)
                SmartClipboardPreview(
                    action,
                    applyMasks(source, candidates),
                    items.size,
                    candidates
                )
            }
        }
        if (transformed.output.isBlank()) return failure(SmartClipboardTransformError.NoSelection)
        if (transformed.output.length > MAX_OUTPUT_CHARACTERS) {
            return failure(SmartClipboardTransformError.OutputTooLong)
        }
        return SmartClipboardTransformResult.Success(transformed)
    }

    /** Removes presentation-only Unicode controls while preserving semantic ZWJ emoji sequences. */
    fun stripFormatting(text: String): String = buildString(text.length) {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when (codePoint) {
                '\r'.code -> if (offset + 1 >= text.length || text[offset + 1] != '\n') append('\n')
                0x00A0, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
                0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000 -> append(' ')
                0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
                0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF -> Unit
                else -> appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
    }

    fun formatPhone(text: String): String? {
        val digits = asciiDigits(text)
        return when {
            digits.length == 8 -> "${digits.take(4)}-${digits.takeLast(4)}"
            digits.length == 9 && digits.startsWith("02") ->
                "${digits.take(2)}-${digits.substring(2, 5)}-${digits.takeLast(4)}"
            digits.length == 10 && digits.startsWith("02") ->
                "${digits.take(2)}-${digits.substring(2, 6)}-${digits.takeLast(4)}"
            digits.length == 10 && digits.startsWith("0") ->
                "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            digits.length == 11 && digits.startsWith("0") ->
                "${digits.take(3)}-${digits.substring(3, 7)}-${digits.takeLast(4)}"
            else -> null
        }
    }

    /** Generic readability preset. It does not claim a bank-specific account-number schema. */
    fun formatAccount(text: String): String? {
        val digits = asciiDigits(text)
        if (digits.length !in 8..20) return null
        val firstGroupLength = digits.length % 4
        val firstEnd = if (firstGroupLength == 0) 4 else firstGroupLength
        return buildList {
            add(digits.substring(0, firstEnd))
            var offset = firstEnd
            while (offset < digits.length) {
                add(digits.substring(offset, offset + 4))
                offset += 4
            }
        }.joinToString("-")
    }

    fun maskCandidates(text: String): List<SmartClipboardMaskCandidate> {
        val candidates = mutableListOf<SmartClipboardMaskCandidate>()
        emailRegex.findAll(text).forEach { match ->
            candidates += SmartClipboardMaskCandidate(
                SmartClipboardPersonalDataKind.Email,
                match.range.first,
                match.range.last + 1,
                maskEmail(match.value)
            )
        }
        phoneRegex.findAll(text).forEach { match ->
            if (!overlaps(candidates, match.range.first, match.range.last + 1)) {
                candidates += SmartClipboardMaskCandidate(
                    SmartClipboardPersonalDataKind.Phone,
                    match.range.first,
                    match.range.last + 1,
                    maskDigits(match.value)
                )
            }
        }
        accountRegex.findAll(text).forEach { match ->
            val value = match.value.trimEnd(' ', '-')
            val end = match.range.first + value.length
            if (!overlaps(candidates, match.range.first, end)) {
                candidates += SmartClipboardMaskCandidate(
                    SmartClipboardPersonalDataKind.Account,
                    match.range.first,
                    end,
                    maskDigits(value)
                )
            }
        }
        return candidates.sortedBy(SmartClipboardMaskCandidate::start)
    }

    private fun applyMasks(
        text: String,
        candidates: List<SmartClipboardMaskCandidate>
    ): String = buildString(text.length) {
        var cursor = 0
        candidates.forEach { candidate ->
            append(text, cursor, candidate.start)
            append(candidate.masked)
            cursor = candidate.end
        }
        append(text, cursor, text.length)
    }

    private fun maskEmail(value: String): String {
        val local = value.substringBefore('@')
        val domain = value.substringAfter('@')
        val host = domain.substringBeforeLast('.', domain)
        val suffix = domain.substringAfterLast('.', "")
        val maskedLocal = local.take(1) + "•".repeat((local.length - 1).coerceAtLeast(2))
        val maskedHost = host.take(1) + "•".repeat((host.length - 1).coerceAtLeast(2))
        return "$maskedLocal@$maskedHost" + if (suffix.isEmpty()) "" else ".$suffix"
    }

    private fun maskDigits(value: String): String {
        val digits = asciiDigits(value)
        val prefixLength = if (digits.startsWith("02")) 2 else minOf(3, digits.length)
        val suffixLength = minOf(4, (digits.length - prefixLength).coerceAtLeast(0))
        val hidden = (digits.length - prefixLength - suffixLength).coerceAtLeast(1)
        return buildString {
            append(digits.take(prefixLength))
            append('-')
            append("•".repeat(hidden))
            if (suffixLength > 0) {
                append('-')
                append(digits.takeLast(suffixLength))
            }
        }
    }

    private fun asciiDigits(text: String): String = buildString(text.length) {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val digit = Character.digit(codePoint, 10)
            if (digit >= 0) append(digit)
            offset += Character.charCount(codePoint)
        }
    }

    private fun overlaps(
        candidates: List<SmartClipboardMaskCandidate>,
        start: Int,
        end: Int
    ): Boolean = candidates.any { start < it.end && end > it.start }

    private fun failure(reason: SmartClipboardTransformError) =
        SmartClipboardTransformResult.Failure(reason)
}
