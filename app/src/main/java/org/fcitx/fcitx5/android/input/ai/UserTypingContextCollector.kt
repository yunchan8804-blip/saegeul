package org.fcitx.fcitx5.android.input.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * Collects recent typing context within a sliding window per application package.
 * Triggers augmentation callback when contextual sentence boundaries (punctuation, newline) are met.
 */
class UserTypingContextCollector(
    private val maxSentencesPerPackage: Int = 5,
    private val maxCharLength: Int = 300,
    private val minTriggerChars: Int = 6,
    private val onTriggerAugmentation: (packageName: String, context: String) -> Unit = { _, _ -> },
    private val onSentenceCommitted: ((packageName: String, sentence: String) -> Unit)? = null
) {
    // Per-package committed sentence history (sliding window)
    private val historyMap = ConcurrentHashMap<String, ArrayDeque<String>>()

    // Per-package current pending uncommitted/partial sentence buffer
    private val pendingBufferMap = ConcurrentHashMap<String, StringBuilder>()

    companion object {
        private val SENTENCE_TERMINATORS = setOf('.', '?', '!', '\n')
    }

    /**
     * Records text committed by the user.
     * Can be called word-by-word or sentence-by-sentence as IME commits text.
     */
    @Synchronized
    fun recordCommittedText(packageName: String, text: String) {
        if (text.isEmpty() || text.all { it.isWhitespace() }) return

        val buffer = pendingBufferMap.getOrPut(packageName) { StringBuilder() }
        buffer.append(text)

        val currentText = buffer.toString()
        // Check if there is any sentence boundary in the buffer
        val lastTerminatorIdx = currentText.indexOfLast { it in SENTENCE_TERMINATORS }

        if (lastTerminatorIdx >= 0) {
            val completedSentence = currentText.substring(0, lastTerminatorIdx + 1).trim()
            val remaining = currentText.substring(lastTerminatorIdx + 1)

            buffer.clear()
            if (remaining.isNotBlank()) {
                buffer.append(remaining)
            }

            if (completedSentence.isNotBlank()) {
                onSentenceCommitted?.invoke(packageName, completedSentence)

                val deque = historyMap.getOrPut(packageName) { ArrayDeque() }
                deque.addLast(completedSentence)
                while (deque.size > maxSentencesPerPackage) {
                    deque.removeFirst()
                }

                if (completedSentence.length >= minTriggerChars) {
                    val fullContext = getRecentContext(packageName)
                    onTriggerAugmentation(packageName, fullContext)
                }
            }
        }
    }

    /**
     * Explicitly trigger augmentation with whatever is currently in buffer/history.
     */
    @Synchronized
    fun triggerNow(packageName: String): Boolean {
        val buffer = pendingBufferMap[packageName]
        if (buffer != null && buffer.isNotBlank()) {
            val pending = buffer.toString().trim()
            buffer.clear()
            val deque = historyMap.getOrPut(packageName) { ArrayDeque() }
            deque.addLast(pending)
            while (deque.size > maxSentencesPerPackage) {
                deque.removeFirst()
            }
        }

        val context = getRecentContext(packageName)
        if (context.length >= minTriggerChars) {
            onTriggerAugmentation(packageName, context)
            return true
        }
        return false
    }

    /**
     * Gets combined recent context string (newline-joined sentences, capped at maxCharLength).
     */
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

    /**
     * Returns list of sentences in the sliding window.
     */
    @Synchronized
    fun getSentences(packageName: String): List<String> {
        return historyMap[packageName]?.toList() ?: emptyList()
    }

    /**
     * Clears context for given package or all packages.
     */
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
}
