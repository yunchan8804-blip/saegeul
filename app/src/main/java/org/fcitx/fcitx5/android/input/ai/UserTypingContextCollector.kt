package org.fcitx.fcitx5.android.input.ai

/**
 * Collects recent typing context within a sliding window per application package.
 * Triggers augmentation callback when contextual sentence boundaries are met.
 */
class UserTypingContextCollector(
    private val maxSentencesPerPackage: Int = 5,
    private val maxCharLength: Int = 300,
    private val minTriggerChars: Int = 6,
    private val onTriggerAugmentation: (packageName: String, context: String) -> Unit = { _, _ -> },
    private val onSentenceCommitted: ((packageName: String, sentence: String) -> Unit)? = null
) {
    private val historyMap = LinkedHashMap<String, ArrayDeque<String>>()
    private val pendingBufferMap = LinkedHashMap<String, StringBuilder>()
    private val pendingEndingBoundaryMap = LinkedHashMap<String, Int>()

    companion object {
        private val SENTENCE_TERMINATORS = setOf('.', '?', '!', '\n', '。', '？', '！')
        private val KOREAN_ENDINGS = listOf(
            "습니다", "드립니다", "니다", "세요", "할까요", "인가요", "네요", "죠",
            "해요", "이요", "요",
            "ㅋㅋㅋ", "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅜㅜ"
        )
        private const val MIN_FLUSH_CHARS = 4
    }

    @Synchronized
    fun recordCommittedText(packageName: String, text: String) {
        if (text.isEmpty()) return

        val buffer = pendingBufferMap.getOrPut(packageName) { StringBuilder() }

        // (a) A pending Korean-ending boundary only becomes a real sentence break once the
        // following chunk starts with whitespace, e.g. "요" (buffered) + " " (this chunk).
        val pendingBoundary = pendingEndingBoundaryMap[packageName]
        if (pendingBoundary != null && text[0].isWhitespace()) {
            val bufferedText = buffer.toString()
            val completedSentence = bufferedText.substring(0, pendingBoundary).trim()
            val remaining = bufferedText.substring(pendingBoundary)
            buffer.clear()
            if (remaining.isNotBlank()) {
                buffer.append(remaining)
            }
            pendingEndingBoundaryMap.remove(packageName)
            emitSentence(packageName, completedSentence)
        }

        // (b) Append the new chunk regardless of whether it just closed a pending boundary.
        buffer.append(text)

        // (c) Latin/CJK punctuation still splits immediately, same as before.
        val currentText = buffer.toString()
        val punctBoundary = findPunctuationBoundary(currentText)
        if (punctBoundary != null) {
            val completedSentence = currentText.substring(0, punctBoundary).trim()
            val remaining = currentText.substring(punctBoundary)
            buffer.clear()
            if (remaining.isNotBlank()) {
                buffer.append(remaining)
            }
            pendingEndingBoundaryMap.remove(packageName)
            emitSentence(packageName, completedSentence)
            return
        }

        // (d) No punctuation boundary. A single-syllable chunk (per-syllable engine commit)
        // only *arms* a Korean-ending boundary for next time, so a leading syllable like "요"
        // cannot be split off. A multi-character chunk (paste, buffered-Hangul segment,
        // candidate selection) already delivered a whole unit of text, so it emits immediately,
        // same as a punctuation boundary.
        val trimmed = buffer.toString().trimEnd()
        if (KOREAN_ENDINGS.any { trimmed.endsWith(it) }) {
            if (text.length >= 2) {
                val completedSentence = trimmed.trim()
                val remaining = currentText.substring(trimmed.length)
                buffer.clear()
                if (remaining.isNotBlank()) {
                    buffer.append(remaining)
                }
                pendingEndingBoundaryMap.remove(packageName)
                emitSentence(packageName, completedSentence)
            } else {
                pendingEndingBoundaryMap[packageName] = trimmed.length
            }
        } else {
            pendingEndingBoundaryMap.remove(packageName)
        }
    }

    @Synchronized
    fun hasPending(packageName: String): Boolean {
        val buffer = pendingBufferMap[packageName] ?: return false
        return buffer.toString().trim().isNotEmpty()
    }

    /**
     * Flushes leftover Korean chat text that never got Latin punctuation.
     * Used when the editor closes or the user sends a message.
     */
    @Synchronized
    fun flushPending(packageName: String): Boolean {
        val buffer = pendingBufferMap[packageName] ?: return false
        val pending = buffer.toString().trim()
        if (pending.length < MIN_FLUSH_CHARS) return false
        buffer.clear()
        pendingEndingBoundaryMap.remove(packageName)
        emitSentence(packageName, pending)
        return true
    }

    @Synchronized
    fun flushAllPending(): Int {
        return pendingBufferMap.keys.toList().count { flushPending(it) }
    }

    @Synchronized
    fun triggerNow(packageName: String): Boolean {
        if (flushPending(packageName)) return true
        val context = getRecentContext(packageName)
        if (context.length >= minTriggerChars) {
            onTriggerAugmentation(packageName, context)
            return true
        }
        return false
    }

    @Synchronized
    fun getRecentContext(packageName: String): String {
        val deque = historyMap[packageName] ?: return ""
        val combined = deque.joinToString("\n")
        return if (combined.length > maxCharLength) {
            combined.takeLast(maxCharLength).substringAfter("\n", combined.takeLast(maxCharLength))
        } else {
            combined
        }
    }

    @Synchronized
    fun getSentences(packageName: String): List<String> {
        return historyMap[packageName]?.toList() ?: emptyList()
    }

    @Synchronized
    fun clear(packageName: String? = null) {
        if (packageName != null) {
            historyMap.remove(packageName)
            pendingBufferMap.remove(packageName)
            pendingEndingBoundaryMap.remove(packageName)
        } else {
            historyMap.clear()
            pendingBufferMap.clear()
            pendingEndingBoundaryMap.clear()
        }
    }

    private fun emitSentence(packageName: String, sentence: String) {
        if (sentence.isBlank()) return
        onSentenceCommitted?.invoke(packageName, sentence)
        val deque = historyMap.getOrPut(packageName) { ArrayDeque() }
        deque.addLast(sentence)
        while (deque.size > maxSentencesPerPackage) {
            deque.removeFirst()
        }
        if (sentence.length >= minTriggerChars) {
            onTriggerAugmentation(packageName, getRecentContext(packageName))
        }
    }

    private fun findPunctuationBoundary(text: String): Int? {
        val punctIdx = text.indexOfLast { it in SENTENCE_TERMINATORS }
        if (punctIdx >= 0) return punctIdx + 1
        return null
    }
}
