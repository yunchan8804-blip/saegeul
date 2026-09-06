package org.fcitx.fcitx5.android.input.ai

import java.util.concurrent.ConcurrentHashMap

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
    private val historyMap = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val pendingBufferMap = ConcurrentHashMap<String, StringBuilder>()

    companion object {
        private val SENTENCE_TERMINATORS = setOf('.', '?', '!', '\n', '。', '？', '！')
        private val KOREAN_ENDINGS = listOf(
            "습니다", "드립니다", "세요", "할까요", "인가요", "네요", "죠",
            "해요", "이요", "요",
            "ㅋㅋㅋ", "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅜㅜ"
        )
        private const val MIN_FLUSH_CHARS = 4
    }

    @Synchronized
    fun recordCommittedText(packageName: String, text: String) {
        if (text.isEmpty() || text.all { it.isWhitespace() }) return

        val buffer = pendingBufferMap.getOrPut(packageName) { StringBuilder() }
        buffer.append(text)

        val currentText = buffer.toString()
        val boundary = findSentenceBoundary(currentText) ?: return

        val completedSentence = currentText.substring(0, boundary).trim()
        val remaining = currentText.substring(boundary)

        buffer.clear()
        if (remaining.isNotBlank()) {
            buffer.append(remaining)
        }

        emitSentence(packageName, completedSentence)
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
        emitSentence(packageName, pending)
        return true
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
        } else {
            historyMap.clear()
            pendingBufferMap.clear()
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

    private fun findSentenceBoundary(text: String): Int? {
        val punctIdx = text.indexOfLast { it in SENTENCE_TERMINATORS }
        if (punctIdx >= 0) return punctIdx + 1

        val trimmed = text.trimEnd()
        for (ending in KOREAN_ENDINGS) {
            if (trimmed.endsWith(ending)) {
                return trimmed.length
            }
        }
        return null
    }
}
